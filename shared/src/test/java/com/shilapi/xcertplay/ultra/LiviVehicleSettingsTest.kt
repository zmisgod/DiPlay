package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.airplay.rcs.caf.*
import org.junit.Assert.*
import org.junit.Test

class LiviVehicleSettingsTest {
    private class Backend : HvacPowerBackend {
        override val extendedClimate = true
        override val settingsLabels = mapOf("range" to "剩余续航", "unknown" to "未知")
        var data = mapOf("range" to "111 km", "coolant" to "50%")
        var power = false
        var pending: Boolean? = null
        var writes = 0
        override fun connect() {}
        override fun close() {}
        override fun readPower() = power
        override fun readVehicleSettings() = LiviVehicleSettings.values(data, "未知")
        override fun setPower(on: Boolean, active: () -> Boolean): Boolean {
            writes++; pending = on; return true
        }
    }
    private fun request(state: HvacPowerCaf, command: CafCommand, values: Any? = null, tx: Long = 1, lane: Long = 1) =
        CafRcsCodec.decodeOpack(state.handle(lane, CafRcsCodec.encodeOpack(CafEnvelope(0,
            CafMessage(command, tx, values))))!!).message

    @Test fun settingsMatchLiviInfoLineAndSectionVocabularyWithUniqueIdsAndValidInitialValues() {
        val cfg = LiviClimateSchema.configuration(emptyMap(), labels = mapOf("range" to "剩余续航"))
        CafConfigTree.decode(cfg)
        val accessories = cfg["accessories"] as List<Map<String, Any?>>
        val settings = accessories.single { it["iid"] == 1280L }
        assertEquals(0x05000001L, settings["type"])
        val services = settings["services"] as List<Map<String, Any?>>
        val rows = services.filter { it["type"] == 0x16000015L }
        assertEquals(20, rows.size)
        val sections = services.filter { it["type"] == 0x16000020L }
        assertEquals(2, sections.size)
        val ids = mutableListOf<Long>()
        accessories.forEach { a ->
            ids += a["iid"] as Long
            (a["services"] as List<Map<String, Any?>>).forEach { s ->
                ids += s["iid"] as Long
                (s["characteristics"] as List<Map<String, Any?>>).forEach { c -> ids += c["iid"] as Long }
            }
        }
        assertEquals(ids.size, ids.toSet().size)
        val chars = rows.flatMap { it["characteristics"] as List<Map<String, Any?>> }.associateBy { it["iid"] }
        assertEquals("剩余续航", chars.getValue(1307L)["initialValue"])
        assertEquals("Unavailable", chars.getValue(1308L)["initialValue"])
        assertTrue(chars.values.all { it["writable"] == false })
        // Sort orders above nine must not be lost by the climate fan's former hard-coded 0..9 check.
        assertEquals(19L, chars.getValue(1447L)["initialValue"])
    }

    @Test fun twoLanesReceiveChangingVehicleRowsAndUnknownAfterFeedbackLossWithoutWritingHardware() {
        val backend = Backend(); val state = HvacPowerCaf(backend)
        assertEquals(mapOf(1308L to "111 km", 1316L to "未知"), request(state, CafCommand.REGISTER_REQUEST, listOf(1308L, 1316L)).values)
        request(state, CafCommand.REGISTER_REQUEST, listOf(1308L), lane = 2)
        backend.data = emptyMap(); state.refresh()
        val changes = state.updates()
        assertEquals(setOf(1L, 2L), changes.keys)
        changes.values.forEach { assertEquals(mapOf(1308L to "未知"), CafRcsCodec.decodeOpack(it).message.values) }
        assertEquals("剩余续航", (request(state, CafCommand.READ_REQUEST, listOf(1307L)).values as Map<*, *>)[1307L])
        val result = request(state, CafCommand.WRITE_REQUEST, mapOf(1308L to "999 km"), tx = 2)
        assertEquals(HvacPowerCaf.NO_VALUE, result.errors!![1308L]); assertEquals(0, backend.writes)
        state.close()
    }

    @Test fun delayedClimateFeedbackIsConfirmedWithoutResendingAndDuplicateLaneWriteIsIdempotent() {
        val backend = Backend(); var polls = 0
        val state = HvacPowerCaf(backend, waitForFeedback = {
            polls++
            if (polls == 3) backend.power = backend.pending!!
        })
        val result = request(state, CafCommand.WRITE_REQUEST, mapOf(514L to true), tx = 10)
        assertEquals(0L, result.errors!![514L]); assertEquals(3, polls); assertEquals(1, backend.writes)
        assertEquals(result, request(state, CafCommand.WRITE_REQUEST, mapOf(514L to true), tx = 10, lane = 2))
        assertEquals(1, backend.writes)
        state.close()
    }

    @Test fun unconfirmedFeedbackTimesOutAndCancellationNeverResends() {
        val backend = Backend(); var waits = 0
        val log = mutableListOf<String>()
        val state = HvacPowerCaf(backend, log::add, waitForFeedback = { waits++ })
        assertEquals(HvacPowerCaf.NO_VALUE, request(state, CafCommand.WRITE_REQUEST, mapOf(514L to true)).errors!![514L])
        assertEquals(12, waits); assertEquals(1, backend.writes)
        assertTrue(log.any { "iid=514 accepted=true confirmed=false" in it })
        assertFalse(backend.power)
        state.close()
    }
}
