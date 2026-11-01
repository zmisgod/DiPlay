package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.ultra.Fx11HvacPowerBackend
import com.shilapi.xcertplay.ultra.HvacPowerCaf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Fx11ClimatePowerTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun climateIsOptInSurvivesMigrationAndKeepsEveryExistingDashboardStyle() {
        assertNull(BoyueUltraIntegration.config(app, null))
        BoyueLSettings.setClimatePower(app, true)
        assertFalse(BoyueLSettings.climatePower(app))
        BoyueLSettings.select(app, true)
        BoyueLSettings.migrateDashboardControls(app)
        assertTrue(BoyueLSettings.climatePower(app))
        assertFalse(BoyueLSettings.ultra(app))
        for (style in DashboardStyle.entries.filter { it != DashboardStyle.CARPLAY_ULTRA }) {
            NativeDashboardSettings.setStyle(app, style)
            val config = BoyueUltraIntegration.config(app, null)!!
            assertEquals(style, NativeDashboardSettings.style(app))
            assertNull(config.cluster)
            assertFalse(AirPlayFeature.ALT_SCREEN in config.readyFeatures)
            assertFalse(AirPlayFeature.UI_SYNC in config.readyFeatures)
            assertNull(config.uiSyncInfo)
            assertTrue(AirPlayFeature.VEHICLE_STATE_PROTOCOL in config.readyFeatures)
        }
        BoyueLSettings.setClimatePower(app, false)
        assertNull(BoyueUltraIntegration.config(app, null))
    }

    @Test fun climateInfoAddsOnlyItsSidecarsAndPreservesExistingDisplayAudioAndHid() {
        BoyueLSettings.select(app, true); BoyueLSettings.setClimatePower(app, true)
        val normal = AirPlayConfig("DiPlay", "00", "00", "1", AirPlayDisplayConfig(1200, 800),
            cluster = AirPlayDisplayConfig(1920, 532, initialUrl = "maps:/"))
        val ultra = normal.copy(ultra = BoyueUltraIntegration.config(app, normal.cluster))
        val before = AirPlayInfoPlist.build(normal)
        val after = AirPlayInfoPlist.build(ultra)
        assertEquals(before.keys + setOf("vehicleStateProtocolInfo", "displayPanels"), after.keys)
        // Bplist bytes compare nested byte arrays (HID descriptors) by value, not array identity.
        assertArrayEquals(BplistCodec.encode(before), BplistCodec.encode(after.filterKeys { it in before }))
        assertEquals(HvacPowerCaf.PLUGIN_ID, ultra.ultra!!.vehicleStateProtocolInfo!!.pluginConfigs.single().pluginId)
    }

    @Test fun climatePanelsPreserveVideoMappingAndAddLiviInstrumentPlugin() {
        BoyueLSettings.select(app, true); BoyueLSettings.setClimatePower(app, true)
        for (cluster in listOf(null, AirPlayDisplayConfig(1920, 532, initialUrl = "maps:/"))) {
            val config = AirPlayConfig("DiPlay", "00", "00", "1", AirPlayDisplayConfig(1200, 800),
                cluster = cluster, ultra = BoyueUltraIntegration.config(app, cluster))
            val info = AirPlayInfoPlist.build(config)
            val displays = info["displays"] as List<*>
            val panels = info["displayPanels"] as List<*>
            assertEquals(displays.size + 1, panels.size)
            val plugin = panels.last() as Map<*, *>
            assertEquals(emptyList<Any>(), plugin["initialVideoStreams"])
            assertEquals(listOf("showsInstruments"), plugin["properties"])
            assertNotNull(plugin["displayPlugins"])
            panels.zip(displays).forEach { (p, d) ->
                val panel = p as Map<*, *>; val display = d as Map<*, *>
                assertEquals(display["widthPixels"], panel["widthPixels"])
                assertEquals(display["heightPixels"], panel["heightPixels"])
                val stream = (panel["initialVideoStreams"] as List<*>).single() as Map<*, *>
                assertEquals(display["uuid"], stream["uuid"])
                assertFalse(panel.containsKey("displayPlugins"))
            }
        }
    }

    @Test fun originalInstrumentsSelectThemeWithoutChangingOrdinaryMainScreenOrSavedStyles() {
        BoyueLSettings.select(app,true);BoyueLSettings.setClimatePower(app,false)
        NativeDashboardSettings.setStyle(app,DashboardStyle.CARPLAY_ULTRA)
        val display=NativeDashboardMapConfig.config(DashboardStyle.CARPLAY_ULTRA,DashboardMapSize.LARGE)
        assertNull(display.initialUrl);assertNull(display.safeArea)
        assertEquals(1920,display.widthPixels);assertEquals(532,display.heightPixels)
        val config=BoyueUltraIntegration.config(app,display)!!
        assertEquals("44C9FA31ECCN",config.theme!!.assetId)
        assertTrue(AirPlayFeature.UI_SYNC in config.readyFeatures)
        assertTrue(AirPlayFeature.ALT_SCREEN in config.readyFeatures)
        val air=AirPlayConfig("test","00","00","1",AirPlayDisplayConfig(1200,800),cluster=display,ultra=config)
        val panels=AirPlayInfoPlist.build(air)["displayPanels"] as List<*>
        assertEquals(2,panels.size)
        val cluster=panels.last() as Map<*,*>
        assertEquals(config.theme!!.clusterPanel,cluster["uid"])
        val plugin=(cluster["displayPlugins"] as List<*>).single() as Map<*,*>
        assertEquals(listOf(config.theme!!.clusterPanel),plugin["videoStreams"])
        NativeDashboardSettings.setStyle(app,DashboardStyle.REPLICA)
        assertNull(BoyueUltraIntegration.theme(app));assertNull(BoyueUltraIntegration.config(app,display))
        assertEquals(3,DashboardStyle.REPLICA.ordinal);assertEquals(8,DashboardStyle.STARFIELD.ordinal)
    }
    @Test fun climateChangesRejectStaleUiAndDoNotDependOnThemeOrHardwarePower() {
        BoyueLSettings.select(app, true)
        assertTrue(BoyueLSettings.setClimatePower(app, true, expected = false))
        assertTrue(BoyueLSettings.setClimatePower(app, false, expected = true))
        assertFalse(BoyueLSettings.setClimatePower(app, true, expected = true))
        for (style in DashboardStyle.entries) {
            NativeDashboardSettings.setStyle(app, style)
            BoyueLSettings.migrateDashboardControls(app)
            assertFalse(BoyueLSettings.climatePower(app))
        }
        assertTrue(BoyueLSettings.climateDiagnostic(app).contains("revision=2"))
        assertTrue(BoyueLSettings.climateDiagnostic(app).contains("saved=false"))
    }

    @Test fun additionalClimateDecodesVendorEnumsWithoutInventingValues() {
        assertEquals(9L,Fx11HvacPowerBackend.decode(525,0x10020109))
        assertNull(Fx11HvacPowerBackend.decode(525,0x1002010a))
        assertEquals(true,Fx11HvacPowerBackend.decode(526,0x1002010a))
        assertEquals(true,Fx11HvacPowerBackend.decode(528,0x10030101))
        assertNull(Fx11HvacPowerBackend.decode(528,255))
        assertEquals(0x10020103,Fx11HvacPowerBackend.encode(525,3))
        assertNull(Fx11HvacPowerBackend.encode(525,3.4))
        assertNull(Fx11HvacPowerBackend.encode(525,Double.NaN))
        assertNull(Fx11HvacPowerBackend.encode(524,true))
    }

    class ExpandedFunctions {
        var celsius = true
        var supported = true
        val writes = mutableListOf<Triple<Int, Int?, Any>>()
        fun isFunctionSupported(id: Int, zone: Int): String = if (supported) "active" else "notavailable"
        fun isFunctionSupported(id: Int): String { assertEquals(0x10030100, id); return "active" }
        fun getSupportedFunctionZones(id: Int): IntArray = when (id) {
            0x10060100 -> intArrayOf(1, 4)
            0x10020100 -> intArrayOf(8)
            0x10010200 -> intArrayOf(1)
            else -> intArrayOf(Int.MIN_VALUE)
        }
        fun getFunctionValue(id: Int, zone: Int): Int = when (id) {
            268830208 -> if (celsius) 268830209 else 268830210
            0x10020100 -> { assertEquals(8, zone); 0x10020103 }
            0x10010200 -> { assertEquals(1, zone); 1 }
            0x10010300 -> { assertEquals(Int.MIN_VALUE, zone); 1 }
            else -> error("Unexpected function $id/$zone")
        }
        fun getFunctionValue(id: Int): Int { assertEquals(0x10030100, id); return 0x10030101 }
        fun getCustomizeFunctionValue(id: Int, zone: Int): Float = when (id) {
            268829440 -> 16f
            268829184 -> 32f
            268829696 -> .5f
            0x10060100 -> if (zone == 1) 22.5f else { assertEquals(4, zone); 23f }
            else -> error("Unexpected customize function $id/$zone")
        }
        fun getSupportedFunctionValue(id: Int, zone: Int): IntArray =
            if (id == 0x10020100) (0x10020101..0x10020109).toList().toIntArray() else intArrayOf(0, 1)
        fun getSupportedFunctionValue(id: Int): IntArray { assertEquals(0x10030100, id); return intArrayOf(0x10030101, 0x10030102) }
        fun setFunctionValue(id: Int, zone: Int, value: Int): Boolean { writes += Triple(id, zone, value); return true }
        fun setFunctionValue(id: Int, value: Int): Boolean { writes += Triple(id, null, value); return true }
        fun setCustomizeFunctionValue(id: Int, zone: Int, value: Float): Boolean { writes += Triple(id, zone, value); return true }
    }
    @Test fun expandedBackendUsesVerifiedZonesUnitsAndNonZonedRecirculationOverload() {
        val functions = ExpandedFunctions()
        val backend = Fx11HvacPowerBackend(app)
        ReflectionHelpers.setField(backend, "functionType", ExpandedFunctions::class.java)
        ReflectionHelpers.setField(backend, "function", functions)
        assertEquals(mapOf(515L to true, 520L to 22.5, 534L to 23.0, 524L to true,
            525L to 3L, 526L to false, 528L to true, 530L to true), backend.readAdditional())
        assertTrue(backend.setAdditional(520, 24.5) { true })
        assertTrue(backend.setAdditional(534, 25.0) { true })
        assertTrue(backend.setAdditional(525, 4) { true })
        assertTrue(backend.setAdditional(528, false) { true })
        assertEquals(listOf(Triple(0x10060100, 1, 24.5f), Triple(0x10060100, 4, 25f),
            Triple(0x10020100, 8, 0x10020104), Triple(0x10030100, null, 0x10030102)), functions.writes)
        assertFalse(backend.setAdditional(520, 24.25) { true })
        assertFalse(backend.setAdditional(525, 4) { false })
        functions.celsius = false
        assertNull(backend.readAdditional()[520L])
        assertFalse(backend.setAdditional(520, 25) { true })
        functions.supported = false
        assertFalse(backend.setAdditional(515, true) { true })
        assertEquals(4, functions.writes.size)
        backend.close()
    }

    class FakeFunctions {
        var status = "active"
        var raw = 0
        var writes = 0
        var accepted = true
        fun isFunctionSupported(function: Int, zone: Int): String {
            assertEquals(0x10010100, function); assertEquals(Int.MIN_VALUE, zone)
            return status
        }
        fun getFunctionValue(function: Int, zone: Int): Int {
            assertEquals(0x10010100, function); assertEquals(Int.MIN_VALUE, zone)
            return raw
        }
        fun setFunctionValue(function: Int, zone: Int, value: Int): Boolean {
            assertEquals(0x10010100, function); assertEquals(Int.MIN_VALUE, zone)
            assertTrue(value in 0..1); writes++
            if (accepted) raw = value
            return accepted
        }
    }
    @Test fun backendUsesVerifiedEcarxOverloadAndRejectsUnavailableOrCancelledWrites() {
        val functions = FakeFunctions()
        val backend = Fx11HvacPowerBackend(app)
        ReflectionHelpers.setField(backend, "functionType", FakeFunctions::class.java)
        ReflectionHelpers.setField(backend, "function", functions)
        assertEquals(false, backend.readPower())
        assertTrue(backend.setPower(true) { true })
        assertEquals(true, backend.readPower()); assertEquals(1, functions.writes)
        assertFalse(backend.setPower(false) { false }); assertEquals(1, functions.writes)
        functions.status = "notavailable"
        assertNull(backend.readPower()); assertFalse(backend.setPower(false) { true })
        functions.status = "active"; functions.raw = 255
        assertNull(backend.readPower())
        functions.raw = 1; functions.accepted = false
        assertFalse(backend.setPower(false) { true }); assertEquals(2, functions.writes)
        backend.close()
        assertNull(backend.readPower()); assertFalse(backend.setPower(true) { true })
        assertEquals(2, functions.writes)
    }
}
