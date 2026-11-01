package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Esp32TransportTest {
    private fun exchange(response: String, verify: (String,ByteArray)->Unit = { _,_ -> }, request: (Esp32HttpClient)->Unit) {
        val server=ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())
        val executor=Executors.newSingleThreadExecutor()
        val received=executor.submit<Unit> {
            server.accept().use { socket ->
                socket.soTimeout=3000
                val input=socket.getInputStream();val header=StringBuilder()
                while(!header.endsWith("\r\n\r\n")) { val v=input.read();check(v>=0);header.append(v.toChar());check(header.length<8192) }
                val length=Regex("(?im)^Content-Length: (\\d+)").find(header)?.groupValues?.get(1)?.toInt() ?: 0
                val body=ByteArray(length)
                for(i in body.indices) { val v=input.read();check(v>=0);body[i]=v.toByte() }
                verify(header.toString(),body)
                socket.getOutputStream().write(response.toByteArray())
            }
        }
        val client=Esp32HttpClient(server.localPort)
        try { request(client);received.get(5,TimeUnit.SECONDS) }
        finally { client.close();server.close();executor.shutdownNow() }
    }
    @Test fun sendsPairedFrameAndReadsOnlyBoundedReply() {
        exchange("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK",{header,body ->
            assertTrue(header.startsWith("POST /frame?revision=7 HTTP/1.1"))
            assertTrue(header.contains("X-DiPlay-Key: test-key"))
            assertTrue(header.contains("Content-Type: image/jpeg"))
            assertArrayEquals(byteArrayOf(1,2,3),body)
        }) { client -> assertEquals("OK",client.request("127.0.0.1","test-key","/frame?revision=7",byteArrayOf(1,2,3)).toString(Charsets.UTF_8)) }
    }
    @Test fun rejectsRedirectAndOversizedResponseRatherThanForwardingPairingKey() {
        exchange("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:1/stolen\r\nContent-Length: 0\r\nConnection: close\r\n\r\n") { client ->
            assertThrows(IllegalStateException::class.java) { client.request("127.0.0.1","test-key","/state",null) }
        }
        exchange("HTTP/1.1 200 OK\r\nContent-Length: 4097\r\nConnection: close\r\n\r\n"+"x".repeat(4097)) { client ->
            assertThrows(IllegalStateException::class.java) { client.request("127.0.0.1","test-key","/state",null) }
        }
    }
    @Test fun stoppedClientCannotOpenAnotherConnection() {
        val client=Esp32HttpClient(1);client.close()
        assertThrows(IllegalStateException::class.java) { client.request("127.0.0.1","test-key","/state",null) }
    }
}
