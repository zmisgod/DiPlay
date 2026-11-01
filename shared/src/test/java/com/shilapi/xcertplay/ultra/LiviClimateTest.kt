package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.airplay.rcs.*
import com.shilapi.xcertplay.airplay.rcs.caf.*
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec
import com.shilapi.xcertplay.airplay.rcs.transport.*
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [29])
class LiviClimateTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/livi-ultra/$name.opack")!!.use { it.readBytes() }
    private fun header(bytes: ByteArray): Map<*, *> {
        val outer = OpackCodec.decode(bytes) as Map<*, *>
        assertEquals("Cluster_Display", outer["displayUUID"])
        return BplistCodec.decode(outer["data"] as ByteArray) as Map<*, *>
    }
    private fun semantic(bytes: ByteArray): Map<*, *> = header(bytes).mapValues { (k,v) ->
        if (k == "data") BplistCodec.decode(v as ByteArray) else v
    }

    @Test fun handshakeMatchesIndependentRustReferenceAndDoesNotRequestInstrumentLayout() {
        val sync = LiviUiSync()
        val reset = fixture("01-phone-reset")
        val first = sync.receive(reset)
        assertEquals(semantic(fixture("02-car-reset")), semantic(first.single()))
        assertArrayEquals(first.single(), sync.receive(reset).single())
        assertFalse(sync.ready)
        val completion = sync.receive(fixture("03-phone-reset-complete"))
        assertTrue(sync.ready)
        assertEquals(3, completion.size)
        assertEquals(semantic(fixture("04-car-reset-complete")), semantic(completion[0]))
        assertEquals(semantic(fixture("05-car-allow-layout")), semantic(completion[1]))
        val expectedFocus = semantic(fixture("07-car-give-focus")).toMutableMap()
        expectedFocus["psn"] = 4L // No requestLayout: leave the existing native instruments alone.
        assertEquals(expectedFocus, semantic(completion[2]))
        assertEquals(3, sync.receive(fixture("03-phone-reset-complete")).size)
    }

    @Test fun foreignSessionsMalformedMessagesAndFocusRetriesDoNotAdvanceState() {
        val sync = LiviUiSync()
        sync.receive(fixture("01-phone-reset")); sync.receive(fixture("03-phone-reset-complete"))
        fun focus(session: Long, sequence: Long): ByteArray = OpackCodec.encode(mapOf(
            "displayUUID" to "Cluster_Display", "data" to BplistCodec.encode(mapOf(
                "type" to 2L, "ssn" to session, "psn" to sequence,
                "data" to BplistCodec.encode(mapOf("type" to 8L, "data" to emptyMap<String, Any?>()))))))
        assertTrue(sync.receive(focus(99, 2)).isEmpty())
        val command = focus(1, 2)
        val replies = sync.receive(command)
        assertEquals(2, replies.size)
        assertEquals(3L, header(replies[0])["type"])
        assertArrayEquals(replies[1], sync.receive(command)[1])
        assertTrue(sync.receive(focus(1, 0)).isEmpty())
        assertTrue(runCatching { sync.receive(byteArrayOf(1, 2)) }.isFailure)
    }

    private class Backend : HvacPowerBackend {
        @Volatile var power: Boolean? = false
        var writes = 0
        var accept = true
        var apply = true
        var failRead = false
        val closed = CountDownLatch(1)
        override fun connect() {}
        override fun readPower(): Boolean? { if (failRead) error("read failed"); return power }
        override fun setPower(on: Boolean, active: () -> Boolean): Boolean {
            check(active()); writes++
            if (accept && apply) power = on
            return accept
        }
        override fun close() { closed.countDown() }
    }
    private fun answer(state: HvacPowerCaf, name: String, lane: Long = 1) =
        CafRcsCodec.decodeOpack(state.handle(lane, fixture(name))!!).message
    private fun notify(bytes: ByteArray) = CafRcsCodec.decodeOpack(bytes).message.values as Map<*, *>

    @Test fun rawRustCafRequestsControlPowerAndBothLanesFollowRealFeedback() {
        val backend = Backend(); val state = HvacPowerCaf(backend)
        assertEquals(HvacPowerCaf.configuration(false), answer(state, "08-caf-config").values)
        java.io.File("build/reports/climate-config.opack").apply { parentFile.mkdirs();writeBytes(state.handle(1,fixture("08-caf-config"))!!) }
        assertEquals(mapOf(514L to false), answer(state, "09-caf-register").values)
        answer(state, "09-caf-register", 2)
        val write = answer(state, "10-caf-power-on")
        assertEquals(mapOf(514L to 0L), write.errors)
        assertEquals(1, backend.writes)
        val updates = state.updates()
        assertEquals(setOf(1L, 2L), updates.keys)
        updates.values.forEach { assertEquals(mapOf(514L to true), notify(it)) }
        assertEquals(write, answer(state, "10-caf-power-on", 2))
        assertEquals(1, backend.writes)
        assertTrue(state.updates().isEmpty())
        backend.power = false // Original HVAC panel operation.
        state.refresh()
        state.updates().values.forEach { assertEquals(mapOf(514L to false), notify(it)) }
        assertEquals(1, backend.writes)
        backend.failRead = true
        state.refresh()
        assertEquals(mapOf(514L to null), notify(state.updates().getValue(1)))
        assertTrue(state.updates().isEmpty())
        state.detach(1); backend.failRead = false; state.refresh()
        assertEquals(setOf(2L), state.updates().keys)
        state.close(); assertEquals(0, backend.closed.count)
    }

    @Test fun configurationInitialValueTracksFreshFeedbackAndNeverInventsUnknownPower() {
        val backend=Backend();val state=HvacPowerCaf(backend)
        fun characteristic():Map<*,*> {
            val values=answer(state,"08-caf-config").values as Map<*,*>
            val accessory=(values["accessories"] as List<*>).single() as Map<*,*>
            val service=(accessory["services"] as List<*>).single() as Map<*,*>
            return (service["characteristics"] as List<*>).single() as Map<*,*>
        }
        assertEquals(false,characteristic()["initialValue"])
        backend.power=true;assertEquals(true,characteristic()["initialValue"])
        backend.failRead=true;assertFalse(characteristic().containsKey("initialValue"))
        assertEquals(0,backend.writes)
        state.close()
    }

    @Test fun writesRequireConfirmedFeedbackAndNeverRetryOrAcceptOtherControls() {
        for (failure in listOf("reject", "unchanged", "unavailable", "exception")) {
            val backend = Backend().apply {
                accept = failure != "reject"; apply = failure != "unchanged"
                if (failure == "unavailable") power = null
                failRead = failure == "exception"
            }
            val state = HvacPowerCaf(backend)
            assertEquals(mapOf(514L to HvacPowerCaf.NO_VALUE), answer(state, "10-caf-power-on").errors)
            val count = backend.writes
            answer(state, "10-caf-power-on"); state.refresh(); state.refresh()
            assertEquals(count, backend.writes)
            if (failure == "unavailable" || failure == "exception") assertEquals(0, count)
        }
        val backend = Backend(); val state = HvacPowerCaf(backend)
        fun write(tx: Long, values: Map<Long, Any?>) = CafRcsCodec.encodeOpack(CafEnvelope(0,
            CafMessage(CafCommand.WRITE_REQUEST, tx, values)))
        for ((index, values) in listOf(mapOf(515L to true), mapOf(514L to 1L), mapOf(514L to "true")).withIndex()) {
            val result = CafRcsCodec.decodeOpack(state.handle(1, write(8L + index, values))!!)
            assertTrue(result.message.errors!!.values.all { it != 0L })
        }
        assertEquals(0, backend.writes)
        assertNull(state.handle(1, fixture("10-caf-power-on")) { false })
        assertEquals(0, backend.writes)
        answer(state, "10-caf-power-on")
        val collision = state.handle(1, write(3, mapOf(514L to false)))!!
        assertEquals(HvacPowerCaf.NO_VALUE, CafRcsCodec.decodeOpack(collision).message.errors!![514L])
        assertEquals(1, backend.writes)
        assertEquals(true, backend.power)
    }

    @Test fun unsubscribeForeignPluginAndClosedSessionCannotWriteOrLeakSubscriptions() {
        val backend = Backend(); val state = HvacPowerCaf(backend)
        answer(state, "09-caf-register")
        fun request(command: CafCommand, tx: Long, values: Any?, plugin: Long = 0) =
            CafRcsCodec.encodeOpack(CafEnvelope(plugin, CafMessage(command, tx, values)))
        val control = state.handle(1, request(CafCommand.CONTROL_REQUEST, 10, mapOf(514L to true)))!!
        assertEquals(mapOf(514L to HvacPowerCaf.NO_VALUE), CafRcsCodec.decodeOpack(control).message.errors)
        assertNull(state.handle(1, request(CafCommand.WRITE_REQUEST, 11, mapOf(514L to true), 5)))
        state.handle(1, request(CafCommand.UNREGISTER_REQUEST, 12, listOf(514L)))
        backend.power = true; state.refresh(); assertTrue(state.updates().isEmpty())
        state.close()
        assertNull(state.handle(1, fixture("11-caf-power-off")))
        state.refresh()
        assertEquals(0, backend.writes)
    }

    @Test fun cancellationWhileReadingPreventsTheSubsequentSetter() {
        var active = true
        var writes = 0
        val state = HvacPowerCaf(object : HvacPowerBackend {
            override fun connect() {}
            override fun readPower(): Boolean { active = false; return false }
            override fun setPower(on: Boolean, active: () -> Boolean): Boolean { writes++; return true }
            override fun close() {}
        })
        state.handle(1, fixture("10-caf-power-on")) { active }
        assertEquals(0, writes)
    }

    @Test fun encryptedStreamConnectsIndependentCafFixtureToHardwareAndReleasesIt() {
        val backend = Backend()
        val runtime = ClimatePowerProvisioning.create({ backend }).createRuntime()
        val a = ByteArray(32) { it.toByte() }; val b = ByteArray(32) { (it + 32).toByte() }
        val stream = RcsDataStream.open(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, a, b, InetAddress.getLoopbackAddress())
        stream.listen(runtime.create(stream))
        val phone = RcsChannel.open(Socket(InetAddress.getLoopbackAddress(), stream.port), b, a)
        try {
            phone.send(RcsMessage.command(fixture("09-caf-register")))
            val registration = phone.receiveNext(5000)!!
            assertEquals(ApTransportPackageCodec.PACKAGE_ASYNC, registration.packageKind)
            assertEquals(ApTransportPackageCodec.MESSAGE_TYPE_COMMAND, registration.messageType)
            assertEquals(mapOf(514L to false), CafRcsCodec.decodeOpack(registration.body).message.values)
            phone.send(RcsMessage.command(fixture("10-caf-power-on")))
            assertEquals(mapOf(514L to 0L), CafRcsCodec.decodeOpack(phone.receiveNext(5000)!!.body).message.errors)
            assertEquals(mapOf(514L to true), notify(phone.receiveNext(5000)!!.body))
            assertEquals(1, backend.writes)
        } finally { phone.close(); stream.close() }
        assertTrue(backend.closed.await(5, TimeUnit.SECONDS))
    }

    @Test fun commCafPeerReceivesConfigAndSubscriptionsUsingItsOwnCarrier() {
        val backend = Backend()
        val provisioning = ClimatePowerProvisioning.create({ backend })
        val a=ByteArray(32){1};val b=ByteArray(32){2}
        val stream=RcsDataStream.open(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2,a,b,InetAddress.getLoopbackAddress())
        stream.listen(provisioning.createRuntime().create(stream))
        val phone=RcsChannel.open(Socket(InetAddress.getLoopbackAddress(),stream.port),b,a)
        try {
            phone.send(RcsMessage.comm(fixture("08-caf-config")))
            val response=phone.receiveNext(5000)!!
            assertEquals(ApTransportPackageCodec.MESSAGE_TYPE_COMM,response.messageType)
            assertEquals(0,response.packageKind)
            assertEquals(HvacPowerCaf.configuration(false),CafRcsCodec.decodeOpack(response.body).message.values)
            phone.send(RcsMessage.comm(fixture("09-caf-register")))
            val registration=phone.receiveNext(5000)!!
            assertEquals(ApTransportPackageCodec.MESSAGE_TYPE_COMM,registration.messageType)
            assertEquals(mapOf(514L to false),CafRcsCodec.decodeOpack(registration.body).message.values)
        } finally {phone.close();stream.close()}
        assertTrue(backend.closed.await(5,TimeUnit.SECONDS))
    }

    @Test fun iphoneAsyncCommRequestReceivesLiviAsyncCommandResponse() {
        val backend = Backend()
        val runtime = ClimatePowerProvisioning.create({ backend }).createRuntime()
        val a = ByteArray(32) { 1 }; val b = ByteArray(32) { 2 }
        val stream = RcsDataStream.open(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2, a, b, InetAddress.getLoopbackAddress())
        stream.listen(runtime.create(stream))
        val phone = RcsChannel.open(Socket(InetAddress.getLoopbackAddress(), stream.port), b, a)
        try {
            phone.send(RcsMessage(ApTransportPackageCodec.MESSAGE_TYPE_COMM, fixture("08-caf-config"),
                ApTransportPackageCodec.PACKAGE_ASYNC))
            val reply = phone.receiveNext(5000)!!
            assertEquals(ApTransportPackageCodec.PACKAGE_ASYNC, reply.packageKind)
            assertEquals(ApTransportPackageCodec.MESSAGE_TYPE_COMMAND, reply.messageType)
            assertEquals(HvacPowerCaf.configuration(false), CafRcsCodec.decodeOpack(reply.body).message.values)
        } finally { phone.close(); stream.close() }
        assertTrue(backend.closed.await(5, TimeUnit.SECONDS))
    }

    @Test fun syncPackageIsAcknowledgedWithOriginal64BitTokenBeforeApplicationResponse() {
        val a = ByteArray(32) { 1 }; val b = ByteArray(32) { 2 }
        val stream = RcsDataStream.open(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL, a, b, InetAddress.getLoopbackAddress())
        stream.listen(object : RcsDataStreamHandler {
            override fun onMessage(stream: RcsDataStream, message: RcsMessage) { stream.send(RcsMessage.command(message.body)) }
        })
        Socket(InetAddress.getLoopbackAddress(), stream.port).use { phone ->
            try {
                phone.soTimeout = 5000
                val codec = RcsFrameCodec.duplex(b, a)
                val token = -0x123456789123456L
                phone.getOutputStream().write(codec.encrypt(RcsMessage(0x636d6e64, byteArrayOf(4, 5),
                    ApTransportPackageCodec.PACKAGE_SYNC, token).encoded()))
                var sealed = byteArrayOf(); var plain = byteArrayOf()
                val packets = mutableListOf<ApTransportPackage>()
                while (packets.size < 2) {
                    val buffer = ByteArray(4096); val count = phone.getInputStream().read(buffer); assertTrue(count > 0)
                    val clear = codec.decrypt(sealed + buffer.copyOf(count)); sealed = clear.rest
                    val decoded = ApTransportPackageCodec.decodeAvailable(plain + clear.data)
                    packets += decoded.packages; plain = decoded.remainder
                }
                assertEquals(ApTransportPackageCodec.PACKAGE_REPLY, packets[0].kind)
                assertEquals(token, packets[0].replyToken)
                assertTrue(packets[0].body.isEmpty())
                assertEquals(ApTransportPackageCodec.PACKAGE_ASYNC, packets[1].kind)
                assertArrayEquals(byteArrayOf(4, 5), packets[1].body)
            } finally { stream.close() }
        }
    }
}
