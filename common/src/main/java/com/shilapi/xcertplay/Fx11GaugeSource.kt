package com.shilapi.xcertplay

import android.content.Context
import android.os.SystemClock
import com.shilapi.xcertplay.ultra.UltraVehicleSource
import com.shilapi.xcertplay.ultra.VehicleReading
import com.shilapi.xcertplay.ultra.VehicleSignal
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Verified GaugeModule/event5 display percentage, never Celsius or front-compartment temperature. */
internal class Fx11GaugeState(private val clock: ()->Long,private val timeoutMs: Long=25_000) {
    private var value: Int?=null
    private var contactAt: Long?=null
    private var observedAt=0L
    private var status="waiting"
    @Synchronized fun reset(reason: String="offline") { value=null;contactAt=null;status=reason }
    @Synchronized fun expired() = contactAt?.let { clock()-it !in 0..timeoutMs } ?: false
    @Synchronized fun accept(line: String) {
        if(expired()) reset("stale")
        if(line=="ALIVE") { contactAt=clock();return }
        if(line in listOf("ONLINE","OFFLINE","INVALID") || line.startsWith("STATUS -")) { reset(line);return }
        if(!line.startsWith("FRAME ")) return
        val parts=line.split(' ')
        val decoded=if(parts.size==3 && parts[1] in listOf("0","1")) decode(parts[2]) else null
        if(decoded==null) { reset("invalid-gauge");return }
        value=decoded;observedAt=clock();contactAt=clock();status="subscribed"
    }
    @Synchronized fun snapshot(): Map<VehicleSignal,VehicleReading> {
        if(expired())reset("stale")
        return value?.let { mapOf(VehicleSignal.COOLANT_GAUGE_PERCENT to VehicleReading(it,observedAt,5)) }.orEmpty()
    }
    @Synchronized fun report() = "GaugeModule/event5 coolant gauge percent status=$status value=${snapshot()[VehicleSignal.COOLANT_GAUGE_PERCENT]?.value ?: "unknown"}; not Celsius"
    companion object {
        fun decode(hex: String): Int? {
            if(!hex.matches(Regex("[0-9a-fA-F]{32}")))return null
            val b=ByteArray(16) { hex.substring(it*2,it*2+2).toInt(16).toByte() }
            val p=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            val percent=p.int;val level=p.int;val extra=p.int;val mode=p.int
            return percent.takeIf { it in 0..100 && level in 0..8 && extra==0 && mode==1 }
        }
    }
}

/** Ordinary APK UID, read-only discovery/subscription. Owns and tears down its child processes. */
internal class Fx11GaugeSource(context: Context) : UltraVehicleSource {
    private val executable = File(context.applicationInfo.nativeLibraryDir, "libfx11_gauge.so")
    private val state = Fx11GaugeState(SystemClock::elapsedRealtime)
    @Volatile private var closed = false
    @Volatile private var child: Process? = null
    private var worker: Thread? = null
    private var watchdog: java.util.concurrent.ScheduledExecutorService? = null
    @Synchronized override fun start() {
        if (worker != null || closed) return
        if (android.os.Build.VERSION.SDK_INT < 30 || !executable.canExecute() || !File("/system/bin/lssvc").canExecute()) {
            state.reset("unavailable-reader-or-discovery"); return
        }
        watchdog = Executors.newSingleThreadScheduledExecutor { Thread(it, "fx11-gauge-watchdog").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({
                if (state.expired()) { state.reset("stale"); child?.destroy() }
            }, 5, 5, TimeUnit.SECONDS)
        }
        worker = Thread({
            while (!closed) {
                try {
                    state.reset("discovering")
                    val url = discover()
                    if (closed) break
                    val process = ProcessBuilder(executable.absolutePath, url).redirectErrorStream(true).start()
                    child = process
                    if (closed) { process.destroy(); break }
                    process.inputStream.bufferedReader().use { reader ->
                        while (!closed) {
                            val line = reader.readLine() ?: break
                            synchronized(this@Fx11GaugeSource) { if (!closed) state.accept(line) }
                        }
                    }
                } catch (_: Exception) {
                    if (!closed) state.reset("reader-unavailable")
                } finally { child?.destroy(); child = null; state.reset() }
                try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
            }
        }, "fx11-gauge").apply { isDaemon = true; start() }
    }
    private fun discover(): String {
        if (android.os.Build.VERSION.SDK_INT < 26) error("FX11 native reader requires Android 11")
        val process = ProcessBuilder("/system/bin/lssvc", "-v").redirectErrorStream(true).start()
        child = process
        val bytes = ByteArrayOutputStream()
        val reader = Thread({
            runCatching { process.inputStream.use { input ->
                val buffer = ByteArray(2048)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    if (bytes.size() + n > 131072) { process.destroy(); break }
                    bytes.write(buffer, 0, n)
                }
            } }
        }, "fx11-service-list").apply { isDaemon = true; start() }
        try {
            check(process.waitFor(5, TimeUnit.SECONDS)) { "discovery timeout" }
            reader.join(1000)
            check(!reader.isAlive && process.exitValue() == 0) { "discovery failed" }
            return endpoint(bytes.toString("UTF-8")) ?: error("GaugeModule unavailable")
        } finally { process.destroy(); child = null }
    }
    override fun snapshot() = state.snapshot()
    fun diagnosticReport() = state.report()
    @Synchronized override fun close() {
        closed = true; watchdog?.shutdownNow(); watchdog = null
        child?.destroy(); worker?.interrupt(); state.reset("closed")
    }
    companion object {
        /** Only the verified FX11 QNX peer; TCP port is discovered after every reconnect. */
        internal fun endpoint(inventory: String): String? {
            var ip: String? = null; var target = false
            for (line in inventory.lineSequence()) {
                if (line.startsWith("[")) {
                    ip = Regex("IP: (\\d+\\.\\d+\\.\\d+\\.\\d+)").find(line)?.groupValues?.get(1)
                    target = false
                }
                if (line.trimStart().startsWith("[")) target = line.trim() == "[GaugeModule]"
                if (target && ip == "192.168.118.2") {
                    val match = Regex("tcp://([0-9.]+):(\\d+)").find(line) ?: continue
                    val host = if (match.groupValues[1] == "0") ip else match.groupValues[1]
                    val port = match.groupValues[2].toIntOrNull() ?: return null
                    if (host == ip && port in 1..65535) return "tcp://$host:$port"
                }
            }
            return null
        }
    }
}
