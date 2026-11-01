package com.shilapi.xcertplay

import com.shilapi.xcertplay.ultra.*
import org.junit.Assert.*
import org.junit.Test

class Fx11VehicleSettingsTest {
    @Test fun vehicleSettingsUseOemSignalsAndActualHoldNotStandardFallbackOrSwitchState() {
        fun reading(value: Any) = VehicleReading(value, 0, 1)
        val v = mutableMapOf(
            VehicleSignal.RANGE_KM to reading(50.0), VehicleSignal.INDICATED_RANGE_KM to reading(111.0),
            VehicleSignal.AMBIENT_C to reading(25.0), VehicleSignal.INDICATED_AMBIENT_C to reading(23.0),
            VehicleSignal.COOLANT_GAUGE_PERCENT to reading(50.0), VehicleSignal.FRONT_COMPARTMENT_C to reading(89.0),
            VehicleSignal.AUTO_HOLD_ACTIVE to reading(false), VehicleSignal.TIRE_FL_BAR to reading(2.3),
            VehicleSignal.TIRE_FR_BAR to reading(2.3), VehicleSignal.GEAR to reading("P"))
        val values = Fx11VehicleSettingsSource.format(DashboardSnapshot(v))
        assertEquals("111 km", values["range"]); assertEquals("23.0 °C", values["ambient"])
        assertEquals("50%", values["coolant"]); assertEquals("off", values["auto_hold"])
        assertEquals("2.3 / 2.3 bar", values["tires_front"]); assertEquals("P", values["gear"])
        v.remove(VehicleSignal.INDICATED_RANGE_KM); v.remove(VehicleSignal.INDICATED_AMBIENT_C)
        v.remove(VehicleSignal.COOLANT_GAUGE_PERCENT); v.remove(VehicleSignal.AUTO_HOLD_ACTIVE)
        val missing = Fx11VehicleSettingsSource.format(DashboardSnapshot(v))
        for (key in listOf("range", "ambient", "coolant", "auto_hold")) assertFalse(missing.containsKey(key))
    }
    @Test fun portraitClimateDeclaresSideDockAndPreservesOrdinaryAndLandscapePreferences() {
        for (requested in CarPlayDock.entries) {
            assertEquals(CarPlayDock.DRIVER_SIDE, UltraPortraitLayout.dock(requested, true, 1440, 1824))
            assertEquals(requested, UltraPortraitLayout.dock(requested, false, 1440, 1824))
            assertEquals(requested, UltraPortraitLayout.dock(requested, true, 1920, 1080))
        }
        val dock = UltraPortraitLayout.dock(CarPlayDock.AUTOMATIC, true, 1440, 1824)
        val areas = CarPlayViewAreas.build(1440, 1824, dock, null)!!
        assertEquals(CarPlayDock.DRIVER_SIDE.edge, areas.areas[areas.current].dockEdge)
        assertEquals(1440, areas.areas[areas.current].width); assertEquals(1824, areas.areas[areas.current].height)
    }
}
