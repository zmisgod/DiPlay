package com.shilapi.xcertplay

import android.app.Activity
import android.os.Bundle
import android.content.*
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDevice
import androidx.core.content.IntentCompat

/** Only a physically enumerated ESP32 is eligible; never handles Apple/other USB devices. */
internal object Esp32UsbAttachment {
    fun enabled(context:Context)=Esp32Settings.enabled(context) && Esp32Settings.transport(context)==1
    fun device(context:Context,intent:Intent):UsbDevice? {
        if(intent.action!=UsbManager.ACTION_USB_DEVICE_ATTACHED)return null
        val proposed=IntentCompat.getParcelableExtra(intent,UsbManager.EXTRA_DEVICE,UsbDevice::class.java) ?: return null
        val manager=context.getSystemService(Context.USB_SERVICE) as UsbManager
        return manager.deviceList[proposed.deviceName]?.takeIf { Esp32UsbDevices.supported(it) }
    }
}

/** Android's USB chooser can remember this activity as the handler without replacing CarPlay. */
class Esp32UsbAttachActivity:Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if(Esp32UsbAttachment.device(this,intent)!=null) {
            if(Esp32UsbAttachment.enabled(this))Esp32DisplayService.refresh(this)
            else startActivity(Intent(this,DiPlayActivity::class.java).putExtra("page","settings")
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        finish()
    }
}

/** One automatic permission prompt per attachment; denial never creates a retry-dialog loop. */
internal class Esp32UsbAttachmentMonitor(private val context:Context,private val request:(Context)->Unit={Esp32UsbDevices.requestPermission(it);Unit}) {
    private val requested=mutableSetOf<String>()
    private var registered=false
    private val receiver=object:BroadcastReceiver() {
        override fun onReceive(c:Context,intent:Intent) {
            val device=IntentCompat.getParcelableExtra(intent,UsbManager.EXTRA_DEVICE,UsbDevice::class.java)
            if(intent.action==UsbManager.ACTION_USB_DEVICE_DETACHED) {
                device?.let { requested.remove(it.deviceName) };return
            }
            if(intent.action==UsbManager.ACTION_USB_DEVICE_ATTACHED && Esp32UsbAttachment.device(c,intent)!=null)scan()
        }
    }
    fun start() {
        if(registered)return
        val filter=IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // Both are protected system broadcasts. Avoid a custom signature permission on older cars.
        if(android.os.Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED)
        else { @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver,filter) }
        registered=true;scan()
    }
    fun scan() {
        if(!Esp32UsbAttachment.enabled(context))return
        val manager=context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device=Esp32UsbDevices.find(manager) ?: return
        if(!manager.hasPermission(device) && requested.add(device.deviceName))request(context)
    }
    fun close() { if(registered){context.unregisterReceiver(receiver);registered=false};requested.clear() }
}
