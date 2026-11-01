package com.shilapi.xcertplay.ultra

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.DashboardSnapshot
import com.shilapi.xcertplay.NativeDashboardRuntime
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Share the dashboard's normalized read-only source, including OEM range and real AutoHold lamp.
 * No second VHAL poller, no main-thread waiting, and no snapshot retained after disconnect. */
internal class Fx11VehicleSettingsSource(private val context: Context) : Closeable {
    private val handler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean()
    private val closed = AtomicBoolean()
    private var removeObserver: (() -> Unit)? = null
    private data class Sample(val snapshot: DashboardSnapshot, val at: Long)
    @Volatile private var sample: Sample? = null
    fun values(): Map<String, String> {
        if (closed.get()) return emptyMap()
        if (started.compareAndSet(false, true)) handler.post {
            if (!closed.get()) removeObserver = NativeDashboardRuntime.observe(context) {
                sample = Sample(it, SystemClock.elapsedRealtime())
            }
        }
        val latest = sample ?: return emptyMap()
        val now = SystemClock.elapsedRealtime()
        if (now - latest.at !in 0..1500) return emptyMap()
        return format(latest.snapshot.copy(vehicle = latest.snapshot.vehicle.filterValues {
            now - it.receivedMillis in 0..2500
        }))
    }
    override fun close() {
        closed.set(true); sample = null
        handler.post { removeObserver?.invoke(); removeObserver = null }
    }
    companion object {
        internal fun format(s: DashboardSnapshot): Map<String, String> = buildMap {
            fun number(key: String, value: Double?, unit: String, digits: Int = 0) {
                if (value != null) put(key, String.format(Locale.ROOT, "%.${digits}f%s", value, unit))
            }
            number("range", s.rangeKm, " km")
            number("fuel", s.number(VehicleSignal.FUEL_PERCENT)?.takeIf { it in 0.0..100.0 }, "%")
            number("coolant", s.coolantGaugePercent, "%")
            number("ambient", s.ambientC, " °C", 1)
            if (s.gear != "—") put("gear", s.gear)
            fun tire(signal: VehicleSignal) = s.number(signal)?.takeIf { it in 0.5..5.0 }
                ?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—"
            put("tires_front", "${tire(VehicleSignal.TIRE_FL_BAR)} / ${tire(VehicleSignal.TIRE_FR_BAR)} bar")
            put("tires_rear", "${tire(VehicleSignal.TIRE_RL_BAR)} / ${tire(VehicleSignal.TIRE_RR_BAR)} bar")
            for ((key, signal) in mapOf("low_beam" to VehicleSignal.LOW_BEAM,
                "auto_hold" to VehicleSignal.AUTO_HOLD_ACTIVE, "parking_brake" to VehicleSignal.PARKING_BRAKE_ON)) {
                (s.vehicle[signal]?.value as? Boolean)?.let { put(key, if (it) "on" else "off") }
            }
        }
    }
}
