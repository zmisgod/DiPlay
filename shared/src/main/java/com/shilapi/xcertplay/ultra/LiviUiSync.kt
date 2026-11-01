package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec

/** Wire/state-machine reference: f-io/LIVI 3f86ad7, ui_sync.rs (GPL-3.0-or-later).
 * A layout is requested only for an explicitly selected theme; ordinary climate keeps no layout.
 */
class LiviUiSync(private val theme: UltraThemeProfile? = null, private val light: Boolean = false) {
    private var session = 0L
    private var sent = 1L
    private var received = 0L
    private var awaiting = false
    var ready = false
        private set
    private var panel = emptyMap<String, Any?>()
    private var phoneId: Any? = null
    private var version = 2L
    private var lastRequest: ByteArray? = null
    private var lastReplies = emptyList<ByteArray>()

    fun receive(body: ByteArray): List<ByteArray> {
        require(body.size <= 512 * 1024) { "UI Sync payload too large" }
        // Retransmissions must not advance the session or repeat focus/layout side effects.
        if (lastRequest?.contentEquals(body) == true) return lastReplies
        val outer = stringMap(OpackCodec.decode(body))
        val header = stringMap(BplistCodec.decode(outer["data"] as? ByteArray ?: error("UI Sync data missing")))
        fun number(key: String) = (header[key] as? Number)?.toLong()
        val type = number("type") ?: error("UI Sync type must be numeric")
        val sequence = number("psn") ?: error("UI Sync psn missing")
        require(sequence >= 0)
        val replies = mutableListOf<ByteArray>()
        if (type == 0L) {
            if (awaiting) return emptyList()
            panel = outer.filterKeys { it != "data" }
            session++; sent = 1; received = 0; ready = false; awaiting = true
            phoneId = header["id"]
            replies += message(0, extra = identity() + ("version" to 2L))
        } else {
            if (number("ssn") != session) return emptyList()
            when (type) {
                1L -> if (awaiting) {
                    panel = outer.filterKeys { it != "data" }
                    version = (number("version") ?: 1).coerceIn(1, 2)
                    awaiting = false; ready = true; received = sequence
                    replies += message(1, extra = (if (version >= 2) identity() else emptyMap()) + ("version" to version))
                    replies += command(0x11, mapOf("allow" to true))
                    theme?.let { replies += command(0x05, it.layout(light)) }
                    replies += command(0x07, mapOf("tokenID" to 4711L))
                }
                2L -> if (ready && sequence > received) {
                    val command = stringMap(BplistCodec.decode(header["data"] as? ByteArray ?: error("UI Sync command missing")))
                    received = sequence
                    if (header["noAck"] != true) replies += message(3)
                    if ((command["type"] as? Number)?.toLong() == 0x08L) {
                        replies += command(0x07, mapOf("tokenID" to 4711L))
                    }
                }
                3L -> if (ready) received = maxOf(received, sequence)
            }
        }
        lastRequest = body.copyOf(); lastReplies = replies
        return replies
    }

    private fun identity(): Map<String, Any?> = phoneId?.let { mapOf("id" to it) } ?: emptyMap()
    private fun command(type: Long, data: Map<String, Any?>) =
        message(2, mapOf("type" to type, "data" to data))
    private fun message(type: Long, data: Map<String, Any?>? = null, extra: Map<String, Any?> = emptyMap()): ByteArray {
        val header = linkedMapOf<String, Any?>("type" to type, "ssn" to session, "psn" to sent++, "asn" to received)
        header.putAll(extra)
        data?.let { header["data"] = BplistCodec.encode(it) }
        return OpackCodec.encode(panel + ("data" to BplistCodec.encode(header)))
    }
    private fun stringMap(value: Any?): Map<String, Any?> {
        val map = value as? Map<*, *> ?: error("UI Sync dictionary required")
        return map.entries.associate { (k, v) -> (k as? String ?: error("UI Sync string key required")) to v }
    }
}
