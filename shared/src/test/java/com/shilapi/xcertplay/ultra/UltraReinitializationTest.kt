package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.airplay.rcs.*
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.caf.*
import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec
import com.shilapi.xcertplay.transport.*
import java.net.Socket
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [29])
class UltraReinitializationTest {
    @Test fun fixedLayoutMetadataHasInitialValuesEvenBeforeVehicleReadings() {
        val config = LiviClimateSchema.configuration(emptyMap())
        val tree = CafConfigTree.decode(config)
        assertNotNull(tree)
        for (iid in listOf(522L, 531L, 536L, 770L)) {
            val field = LiviClimateSchema.fields.getValue(iid)
            assertEquals(field.fixed, field.describe(null)["initialValue"])
            assertFalse(field.describe(null).containsKey("supportsInvalid"))
        }
        assertFalse(LiviClimateSchema.fields.getValue(514).describe(null).containsKey("initialValue"))
    }

    @Test fun nativeThemeUsesTheSameNamesForVideoPanelsHidAndViewAreaCommands() {
        val theme = UltraThemeProfile.FX11_TEST
        val config = AirPlayConfig("test", "00", "00", "1", AirPlayDisplayConfig(1200,800),
            cluster = AirPlayDisplayConfig(1920,532),
            ultra = AirPlayUltraConfig(null, theme = theme))
        val info = AirPlayInfoPlist.build(config)
        val displays = info["displays"] as List<Map<String,Any?>>
        assertEquals(listOf(theme.mainPanel, theme.clusterPanel), displays.map { it["uuid"] })
        val hid = info["hidDevices"] as List<Map<String,Any?>>
        assertTrue(hid.all { it["displayUUID"] == theme.mainPanel })
        val command = AirPlayInfoPlist.viewAreaCommand(0,1,AirPlayInfoPlist.mainUuid(config))
        assertEquals(theme.mainPanel,(command["params"] as Map<*,*>)["uuid"])
        val ordinary = AirPlayInfoPlist.build(config.copy(ultra = null))
        assertEquals(AirPlayInfoPlist.MAIN_UUID, ((ordinary["displays"] as List<*>)[0] as Map<*,*>)["uuid"])
    }

    @Test fun bothStartTransportsAndIdentificationCarryMatchingThemeMetadata() {
        val t=UltraThemeProfile.FX11_TEST
        val wired=Iap2WiredCarPlayEndpoint(listOf("fe80::1"),7000,"abcd","950.7.1",theme=t)
        val wifi=Iap2WirelessCarPlayEndpoint("test","password",6,Iap2WirelessSecurity.WPA_WPA2,
            listOf("192.168.1.1"),7000,"device","abcd","950.7.1",theme=t)
        val frames=listOf(Iap2WiredControlClient.carPlayStartSession(wired),Iap2WirelessControlClient.carPlayStartSession(wifi))
        frames.forEach {
            val body=com.shilapi.xcertplay.iap2.body.Iap2BodyReader.of(it)
            assertEquals(t.sdkVersion,body.string(6));assertEquals(t.assetId,body.group(7).string(0))
            assertEquals(t.version,body.group(7).u32(1))
        }
        val base=Iap2IdentificationConfig("test","model","maker","serial","1","1",3,theme=t)
        listOf(base to 16,base.copy(wireless=Iap2WirelessIdentification("00:11:22:33:44:55","test")) to 24).forEach { (cfg,id) ->
            val body=com.shilapi.xcertplay.iap2.body.Iap2BodyReader.of(Iap2IdentificationClient.identificationInformation(cfg))
            assertEquals(t.assetId,body.group(id).string(6))
        }
        val ordinary=com.shilapi.xcertplay.iap2.body.Iap2BodyReader.of(Iap2WiredControlClient.carPlayStartSession(
            Iap2WiredCarPlayEndpoint(listOf("fe80::1"),7000,"abcd","950.7.1")))
        assertFalse(ordinary.has(6));assertFalse(ordinary.has(7))
    }
    @Test fun repeatedSetupReplacesOnlyItsLaneAndPeerEofReleasesTheEntry() {
        val captured = java.util.concurrent.CopyOnWriteArrayList<RcsDataStream>()
        val closed = java.util.concurrent.atomic.AtomicInteger()
        val types = CafPluginRegistry.DEFAULT_CLIENT_TYPES
        val factory = RcsDataStreamHandlerFactory { stream ->
            captured += stream
            object : RcsDataStreamHandler { override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) { closed.incrementAndGet() } }
        }
        val runtime = AirPlayUltraRuntime.builder().rcs(types, factory).build()
        val config = AirPlayConfig("test","00","00","1",AirPlayDisplayConfig(800,480),
            ultra=AirPlayUltraConfig(null,runtime=runtime))
        val session = AirPlaySession(Socket(),config,AirPlayIdentity.generate(),PairingStore(),null,
            object:AirPlaySessionListener{},object:AirPlayMediaHandler{})
        PairVerify::class.java.getDeclaredField("sharedSecret").apply{isAccessible=true}.set(session.pairVerify,ByteArray(32))
        val streams = UltraDataStreams(session,config)
        streams.negotiated(listOf("vehicleStateProtocol"))
        fun request(type: com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType) = mapOf("clientTypeUUID" to type.uuid.toString(),"seed" to 1L)
        try {
            val peerType = types.last()
            val peer = streams.open(request(peerType))!!
            val peerStream = captured.last()
            val lane = types.first()
            var first: Map<String,Any?>? = null
            repeat(20) {
                val reply=streams.open(request(lane))!!
                if(first==null) first=reply
                streams.responseSent()
            }
            assertEquals(19,closed.get())
            assertFalse(peerStream.isClosed)
            streams.teardown(mapOf("streams" to listOf(mapOf("type" to 130,"streamID" to first!!["streamID"]))))
            assertFalse(captured.last().isClosed) // late TEARDOWN must not close the replacement
            val current=captured.last()
            Socket(InetAddress.getLoopbackAddress(),current.port).use {
                assertTrue(current.awaitPeerConnection(2000))
            }
            val until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
            while(closed.get()<20 && System.nanoTime()<until) Thread.sleep(10)
            assertEquals(20,closed.get())
            assertNotNull(streams.open(request(lane)))
            streams.teardown(mapOf("streams" to listOf(mapOf("type" to 130,"streamID" to peer["streamID"]))))
            assertTrue(peerStream.isClosed)
        } finally { streams.close();session.close() }
        assertEquals(captured.size,closed.get())
    }
    @Test fun themeResetRequestsRealLayoutAndNewChannelRestartsHandshake() {
        val theme=UltraThemeProfile.FX11_TEST
        fun fixture(name:String)=javaClass.getResourceAsStream("/livi-ultra/$name.opack")!!.readBytes()
        repeat(2) {
            val sync=LiviUiSync(theme)
            sync.receive(fixture("01-phone-reset"))
            val replies=sync.receive(fixture("03-phone-reset-complete"))
            assertEquals(4,replies.size)
            val outer=OpackCodec.decode(replies[2]) as Map<*,*>
            val head=BplistCodec.decode(outer["data"] as ByteArray) as Map<*,*>
            val command=BplistCodec.decode(head["data"] as ByteArray) as Map<*,*>
            assertEquals(5L,command["type"])
            assertEquals(theme.layout(false),command["data"])
            assertArrayEquals(replies[2],sync.receive(fixture("03-phone-reset-complete"))[2])
        }
    }
    @Test fun climateRepliesOmitEmptyErrorsAndDiagnoseUnavailableIidsWithoutValues() {
        val diagnostics = mutableListOf<String>()
        val backend = object : HvacPowerBackend {
            override val extendedClimate = true
            override fun connect() {}
            override fun close() {}
            override fun readPower() = false
            override fun setPower(on: Boolean, active: () -> Boolean) = false
            override fun readAdditional() = mapOf(520L to 22.5)
        }
        val state = HvacPowerCaf(backend, diagnostics::add)
        fun request(ids: List<Long>, tx: Long) = CafRcsCodec.decodeOpack(state.handle(1,
            CafRcsCodec.encodeOpack(CafEnvelope(0, CafMessage(CafCommand.REGISTER_REQUEST, tx, ids))))!!).message
        assertNull(request(listOf(514, 520), 1).errors)
        val partial = request(listOf(514, 525), 2)
        assertEquals(mapOf(514L to false), partial.values)
        assertEquals(mapOf(525L to HvacPowerCaf.NO_VALUE), partial.errors)
        assertTrue(diagnostics.any { "unavailable=[525]" in it })
        assertFalse(diagnostics.any { "22.5" in it })
        val layout = LiviClimateSchema.fields.getValue(522).describe("seat_front_left")
        assertEquals(65535L, layout["maximumLength"])
        state.close()
    }
    @Test fun disabledClimatePublishesOnlyResourcesAndNeverTouchesHardware() {
        val state = HvacPowerCaf(object : HvacPowerBackend {
            override val extendedClimate = true
            override val climateEnabled = false
            override fun connect() { error("Climate disabled") }
            override fun readPower(): Boolean? = error("Climate disabled")
            override fun setPower(on: Boolean, active: () -> Boolean): Boolean = error("Climate disabled")
            override fun close() {}
        })
        fun call(command: CafCommand, tx: Long, value: Any? = null) = CafRcsCodec.decodeOpack(state.handle(1,
            CafRcsCodec.encodeOpack(CafEnvelope(0, CafMessage(command, tx, value))))!!).message
        val config = call(CafCommand.CONFIG_REQUEST, 1).values as Map<String, Any?>
        assertEquals(1, CafConfigTree.decode(config).accessories.size)
        assertEquals(mapOf(514L to HvacPowerCaf.NO_VALUE), call(CafCommand.WRITE_REQUEST, 2, mapOf(514L to true)).errors)
        assertEquals(mapOf(770L to "5Seater"), call(CafCommand.READ_REQUEST, 3, listOf(770L)).values)
        state.close()
    }
    @Test fun fullClimateSchemaUsesRealValuesAndDoesNotAcceptUnknownOrReadonlyWrites() {
        val backend=object:HvacPowerBackend {
            override val extendedClimate=true
            var temperature:Double?=22.5
            var writes=0
            override fun connect(){}
            override fun close(){}
            override fun readPower()=false
            override fun setPower(on:Boolean,active:()->Boolean)=false
            override fun readAdditional()=mapOf(520L to temperature,525L to 3L,515L to true)
            override fun setAdditional(iid:Long,value:Any,active:()->Boolean):Boolean { if(!active())return false;writes++;temperature=(value as Number).toDouble();return true }
        }
        val state=HvacPowerCaf(backend)
        fun call(command:CafCommand,tx:Long,value:Any?=null)=CafRcsCodec.decodeOpack(state.handle(1,
            CafRcsCodec.encodeOpack(CafEnvelope(0,CafMessage(command,tx,value))))!!).message
        val configuration=call(CafCommand.CONFIG_REQUEST,1).values as Map<String,Any?>
        assertEquals(3,CafConfigTree.decode(configuration).accessories.size) // climate, interior, automaker settings
        assertEquals(mapOf(520L to 22.5,525L to 3L,770L to "5Seater"),
            call(CafCommand.REGISTER_REQUEST,2,listOf(520L,525L,770L)).values)
        assertEquals(mapOf(520L to 0L),call(CafCommand.WRITE_REQUEST,3,mapOf(520L to 23.0)).errors)
        call(CafCommand.WRITE_REQUEST,3,mapOf(520L to 23.0));assertEquals(1,backend.writes)
        assertEquals(mapOf(520L to 23.0),CafRcsCodec.decodeOpack(state.updates().getValue(1)).message.values)
        backend.temperature=null;state.refresh()
        assertEquals(mapOf(520L to null),CafRcsCodec.decodeOpack(state.updates().getValue(1)).message.values)
        assertEquals(mapOf(520L to HvacPowerCaf.NO_VALUE),call(CafCommand.WRITE_REQUEST,4,mapOf(520L to 24.0)).errors)
        assertEquals(mapOf(770L to HvacPowerCaf.NO_VALUE),call(CafCommand.WRITE_REQUEST,5,mapOf(770L to "bad")).errors)
        assertEquals(1,backend.writes);state.close()
    }
}
