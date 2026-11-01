package com.shilapi.xcertplay

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

internal class Esp32UsbUnavailable : Exception()

internal object Esp32UsbDevices {
    fun permissionAction(context: Context)="${context.packageName}.ESP32_USB_PERMISSION"
    fun supported(device: UsbDevice) = device.vendorId==0x303a && device.productId==0x1001
    fun find(manager: UsbManager) = manager.deviceList.values.filter(::supported).minByOrNull { it.deviceId }
    fun requestPermission(context: Context): Boolean {
        val manager=context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device=find(manager) ?: return false
        if(!manager.hasPermission(device)) manager.requestPermission(device,PendingIntent.getBroadcast(context,630,
            Intent(permissionAction(context)).setPackage(context.packageName),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        return true
    }
}

/** Dedicated USB CDC channel. Never changes Wi-Fi or routes, never claims iPhone USB interfaces. */
internal class Esp32UsbClient(context: Context) {
    private val manager=context.applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
    @Volatile private var connection: UsbDeviceConnection?=null
    private var deviceName: String?=null
    private var input: UsbEndpoint?=null
    private var output: UsbEndpoint?=null
    @Volatile private var closed=false
    private val sequence=AtomicInteger()

    fun close() { closed=true;connection?.close();connection=null }
    private fun open(): UsbDeviceConnection {
        check(!closed)
        val device=Esp32UsbDevices.find(manager) ?: throw Esp32UsbUnavailable()
        if(!manager.hasPermission(device))throw Esp32UsbUnavailable()
        connection?.takeIf { deviceName==device.deviceName }?.let { return it }
        connection?.close();connection=null
        val data=(0 until device.interfaceCount).map(device::getInterface).firstOrNull { it.interfaceClass==UsbConstants.USB_CLASS_CDC_DATA } ?: throw Esp32UsbUnavailable()
        val endpoints=(0 until data.endpointCount).map(data::getEndpoint).filter { it.type==UsbConstants.USB_ENDPOINT_XFER_BULK }
        val incoming=endpoints.firstOrNull { it.direction==UsbConstants.USB_DIR_IN } ?: throw Esp32UsbUnavailable()
        val outgoing=endpoints.firstOrNull { it.direction==UsbConstants.USB_DIR_OUT } ?: throw Esp32UsbUnavailable()
        val c=manager.openDevice(device) ?: throw Esp32UsbUnavailable()
        try {
            check(c.claimInterface(data,true))
            val control=(0 until device.interfaceCount).map(device::getInterface).firstOrNull { it.interfaceClass==UsbConstants.USB_CLASS_COMM }
            if(control!=null) {
                check(c.claimInterface(control,true))
                check(c.controlTransfer(0x21,0x20,0,control.id,byteArrayOf(0,0xc2.toByte(),1,0,0,0,8),7,1000)>=0)
                check(c.controlTransfer(0x21,0x22,1,control.id,null,0,1000)>=0)
            }
            input=incoming;output=outgoing;deviceName=device.deviceName;connection=c
            return c
        } catch(e: Exception) { c.close();throw e }
    }
    fun request(key: String,path: String,body: ByteArray?): ByteArray {
        val c=open();val seq=sequence.incrementAndGet()
        try {
            val packet=Esp32UsbProtocol.request(seq,key,path,body)
            var offset=0
            while(offset<packet.size) {
                check(!closed)
                val n=c.bulkTransfer(output!!,packet,offset,minOf(4096,packet.size-offset),1500)
                check(n>0);offset+=n
            }
            val decoder=Esp32UsbReplyDecoder(seq)
            val bytes=ByteArray(4096)
            val deadline=android.os.SystemClock.elapsedRealtime()+5000
            while(!closed && android.os.SystemClock.elapsedRealtime()<deadline) {
                val n=c.bulkTransfer(input!!,bytes,bytes.size,500)
                if(n>0) decoder.feed(bytes,n)?.let { return it }
            }
            error("ESP32 USB response timeout")
        } catch(e: Exception) { c.close();connection=null;throw e }
    }
}

internal object Esp32UsbProtocol {
    fun request(sequence: Int,key: String,path: String,body: ByteArray?): ByteArray {
        val header=JSONObject().put("key",key).put("path",path).toString().toByteArray(Charsets.UTF_8)
        val payload=body ?: byteArrayOf()
        require(header.size in 1..1024 && payload.size<=128*1024)
        return ByteBuffer.allocate(16+header.size+payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("DPL3".toByteArray()).putInt(sequence).putInt(header.size).putInt(payload.size).put(header).put(payload).array()
    }
}

/** Bounds and reassembles split USB bulk packets, ignores boot noise and late previous replies. */
internal class Esp32UsbReplyDecoder(private val sequence: Int) {
    private val pending=ByteArrayOutputStream()
    fun feed(bytes: ByteArray,count: Int): ByteArray? {
        require(count in 0..bytes.size)
        pending.write(bytes,0,count)
        var b=pending.toByteArray()
        while(b.size>=4) {
            if(!b.copyOfRange(0,4).contentEquals("DPR3".toByteArray())) { b=b.copyOfRange(1,b.size);continue }
            if(b.size<16)break
            val header=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            val id=header.getInt(4);val status=header.getInt(8);val length=header.getInt(12)
            check(length in 0..4096)
            if(b.size<16+length)break
            val payload=b.copyOfRange(16,16+length)
            b=b.copyOfRange(16+length,b.size)
            if(id==sequence) { pending.reset();check(status==200) { "ESP32 USB status=$status" };return payload }
        }
        check(b.size<=4112);pending.reset();pending.write(b)
        return null
    }
}
