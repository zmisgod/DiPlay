package com.shilapi.xcertplay

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class Esp32UsbProtocolTest {
    private fun reply(id:Int,status:Int=200,payload:ByteArray="OK".toByteArray()) = ByteBuffer.allocate(16+payload.size).order(ByteOrder.LITTLE_ENDIAN).put("DPR3".toByteArray()).putInt(id).putInt(status).putInt(payload.size).put(payload).array()
    @Test fun requestPreservesBinaryPayloadAndUsesBoundedLittleEndianHeader() {
        val payload=byteArrayOf(0,-1,123,5)
        val bytes=Esp32UsbProtocol.request(17,"12345689","/frame?revision=2",payload)
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("DPL3",bytes.copyOfRange(0,4).toString(Charsets.UTF_8));assertEquals(17,b.getInt(4))
        val size=b.getInt(8);assertEquals(4,b.getInt(12))
        val j=JSONObject(bytes.copyOfRange(16,16+size).toString(Charsets.UTF_8))
        assertEquals("/frame?revision=2",j.getString("path"));assertEquals("12345689",j.getString("key"))
        assertArrayEquals(payload,bytes.copyOfRange(16+size,bytes.size))
        assertThrows(IllegalArgumentException::class.java) { Esp32UsbProtocol.request(1,"12345689","/frame",ByteArray(131073)) }
    }
    @Test fun noiseOldRepliesAndFragmentedPacketsDoNotCorruptCurrentReply() {
        val packet="boot noise".toByteArray()+reply(16)+reply(17,payload="current".toByteArray())
        for(chunk in listOf(1,3,8,4096)) {
            val d=Esp32UsbReplyDecoder(17);var result:ByteArray?=null
            for(part in packet.toList().chunked(chunk)) { val b=part.toByteArray();result=d.feed(b,b.size) ?: result }
            assertEquals("current",result!!.toString(Charsets.UTF_8))
        }
    }
    @Test fun rejectsFailuresAndUnboundedReplyLengths() {
        val forbidden=reply(1,401)
        assertThrows(IllegalStateException::class.java) { Esp32UsbReplyDecoder(1).feed(forbidden,forbidden.size) }
        for(length in listOf(-1,4097,Int.MAX_VALUE)) {
            val b=reply(1);ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(12,length)
            assertThrows(IllegalStateException::class.java) { Esp32UsbReplyDecoder(1).feed(b,b.size) }
        }
    }
}
