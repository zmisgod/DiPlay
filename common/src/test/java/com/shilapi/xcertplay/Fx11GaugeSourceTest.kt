package com.shilapi.xcertplay

import com.shilapi.xcertplay.ultra.VehicleSignal
import com.shilapi.xcertplay.ultra.VehicleReading
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class Fx11GaugeSourceTest {
    private fun frame(percent:Int=50,level:Int=4,extra:Int=0,mode:Int=1):String = "FRAME 0 "+ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(percent).putInt(level).putInt(extra).putInt(mode).array().joinToString("") { "%02x".format(it.toInt() and 255) }
    @Test fun verifiedGaugeFrameMeansPercentNotCelsius() {
        val state=Fx11GaugeState({0L});state.accept("FRAME 1 32000000040000000000000001000000")
        val values=state.snapshot()
        assertEquals(50,values[VehicleSignal.COOLANT_GAUGE_PERCENT]?.value)
        assertFalse(values.containsKey(VehicleSignal.ENGINE_COOLANT_C))
        assertEquals(50.0,DashboardSnapshot(vehicle=values).coolantGaugePercent!!,0.01)
        val cabin=mapOf(VehicleSignal.FRONT_COMPARTMENT_C to VehicleReading(23,0,0),VehicleSignal.ENGINE_COOLANT_C to VehicleReading(90,0,0))
        assertNull(DashboardSnapshot(vehicle=cabin).coolantGaugePercent)
    }
    @Test fun invalidModeBoundsDisconnectAndStaleBecomeUnknown() {
        var now=0L;val state=Fx11GaugeState({now})
        for(bad in listOf(frame(0,0,0,4),frame(-1),frame(101),frame(level=9),frame(extra=1),"FRAME 0 1234","OFFLINE","ONLINE","INVALID","STATUS -13")) {
            state.accept(frame());state.accept(bad);assertTrue(bad,state.snapshot().isEmpty())
        }
        state.accept(frame());now=20000;state.accept("ALIVE");now=40000
        assertEquals(50,state.snapshot()[VehicleSignal.COOLANT_GAUGE_PERCENT]?.value)
        now=46000;state.accept("ALIVE");assertTrue(state.snapshot().isEmpty())
        state.accept(frame());now=72000;assertTrue(state.snapshot().isEmpty())
    }
    @Test fun serviceDiscoveryOnlyUsesVerifiedPeerAndCurrentPort() {
        val listing="[Host: qnx, IP: 192.168.118.2]\n    [GaugeModule]\n        > tcp://0:65479 udp://65515"
        assertEquals("tcp://192.168.118.2:65479",Fx11GaugeSource.endpoint(listing))
        assertNull(Fx11GaugeSource.endpoint(listing.replace("118.2","118.3")))
        assertNull(Fx11GaugeSource.endpoint(listing.replace("65479","99999")))
        assertNull(Fx11GaugeSource.endpoint(listing.replace("GaugeModule","Other")))
    }
}
