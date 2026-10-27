package com.shilapi.xcertplay.navigation

import com.shilapi.xcertplay.iap2.catalog.Iap2Endpoints
import com.shilapi.xcertplay.iap2.trace.Iap2FrameFormatter
import com.shilapi.xcertplay.iap2.trace.Iap2TraceDirection
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Append-only capture. Preview truncation never affects the raw journal. No automatic pruning. */
class NavigationJournal(
    val root: File,
    metadata: String,
    private val maxBytes: Long = 256L * 1024 * 1024,
    queueCapacity: Int = 256,
) : AutoCloseable {
    private data class Event(val seq: Long, val utc: Long, val elapsed: Long, val kind: String,
        val channel: String, val link: String, val direction: String, val frame: Iap2Frame?, val detail: String)
    private val queue = ArrayBlockingQueue<Event>(queueCapacity)
    private val gate = Any()
    private val io = Any()
    private var sequence = 0L
    @Volatile private var accepting = true
    @Volatile private var failure: String? = null
    @Volatile private var written = 0L
    @Volatile private var lastWrittenSeq = 0L
    @Volatile private var navFrames = 0L
    @Volatile private var preview = ""
    private val recent = java.util.ArrayDeque<String>()
    private var totalBytes = 0L
    val id = "${System.currentTimeMillis()}-${UUID.randomUUID()}"
    val directory = File(root, id)
    private val stream: FileOutputStream
    private val worker: Thread

    init {
        require(maxBytes > 0)
        check(root.isDirectory || root.mkdirs()) { "Cannot create capture directory" }
        totalBytes = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        check(totalBytes < maxBytes) { "Capture storage limit reached; export and archive existing recordings first" }
        check(directory.mkdir()) { "Cannot create capture session" }
        stream = FileOutputStream(File(directory, "events.jsonl"), true)
        appendLine("{\"kind\":\"capture_start\",\"schema\":1,\"captureId\":${json(id)},\"utcMillis\":${System.currentTimeMillis()},\"metadata\":${json(metadata)},\"scope\":${json(SCOPE)},\"storageLimitBytes\":$maxBytes}")
        worker = Thread(::drain, "diplay-navigation-journal").apply { isDaemon = true; start() }
    }

    val recording: Boolean get() = accepting
    fun status(): String = "capture=$id\nrecording=$accepting records=$written navigationFrames=$navFrames queued=${queue.size}\n" +
        "error=${failure ?: "none"}\npath=${directory.absolutePath}\n$scopeText"
    private val scopeText get() = SCOPE
    fun preview(): String = status() + "\n\n" + preview

    fun frame(direction: String, channel: String, link: String, frame: Iap2Frame) =
        offer("frame", channel, link, direction, frame, "")
    fun event(kind: String, channel: String = "", link: String = "", detail: String = "") =
        offer(kind, channel, link, "", null, detail)

    private fun offer(kind: String, channel: String, link: String, direction: String, frame: Iap2Frame?, detail: String) {
        synchronized(gate) {
            if (!accepting) return
            val next = Event(++sequence, System.currentTimeMillis(), System.nanoTime(), kind, channel, link, direction, frame, detail)
            if (!queue.offer(next)) {
                failure = "queue_overflow: capture incomplete; first rejected seq=${next.seq}"
                accepting = false
            }
        }
    }

    private fun drain() {
        try {
            while (accepting || queue.isNotEmpty()) {
                val event = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                val line = encode(event)
                if (totalBytes + line.toByteArray(Charsets.UTF_8).size + 1 > maxBytes) {
                    synchronized(gate) { accepting = false; failure = "storage_limit: capture incomplete; first unwritten seq=${event.seq}" }
                    queue.clear()
                    break
                }
                appendLine(line)
                written++
                lastWrittenSeq = event.seq
                if (event.frame?.messageId in 0x5200..0x52ff) navFrames++
            }
            appendLine("{\"kind\":\"capture_end\",\"utcMillis\":${System.currentTimeMillis()},\"writtenRecords\":$written,\"lastAssignedSeq\":$sequence,\"complete\":${failure == null},\"error\":${failure?.let(::json) ?: "null"}}")
        } catch (error: Exception) {
            synchronized(gate) { failure = "write_failed: ${error.javaClass.simpleName}: ${error.message}"; accepting = false }
            // If the journal volume is full, this marker may also fail; lack of capture_end is detectable.
            runCatching { File(directory, "ERROR.txt").writeText(failure!!) }
        } finally {
            synchronized(io) { runCatching { stream.fd.sync() }; runCatching { stream.close() } }
        }
    }

    private fun appendLine(line: String) = synchronized(io) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        stream.write(bytes)
        stream.fd.sync()
        totalBytes += bytes.size
    }

    private fun encode(e: Event): String {
        val f = e.frame
        val raw = f != null && f.messageId in 0x5200..0x52ff
        val parts = mutableListOf("\"kind\":${json(if (f != null && !raw) "message_inventory" else e.kind)}",
            "\"seq\":${e.seq}", "\"utcMillis\":${e.utc}", "\"monotonicNanos\":${e.elapsed}",
            "\"channel\":${json(e.channel)}", "\"linkId\":${json(e.link)}", "\"direction\":${json(e.direction)}")
        if (f != null) {
            parts += "\"messageId\":${f.messageId}"
            parts += "\"messageName\":${json(Iap2Endpoints.byId(f.messageId)?.name ?: "unknown") }"
            parts += "\"bodyBytes\":${f.payload.size}"
            if (raw) {
                val encoded = f.encodedFrame()
                parts += "\"csmHex\":${json(hex(encoded))}"
                parts += "\"sha256\":${json(hex(MessageDigest.getInstance("SHA-256").digest(encoded)))}"
                val formatted = Iap2FrameFormatter.format(if (e.direction == "TX") Iap2TraceDirection.TX else Iap2TraceDirection.RX, e.channel, f)
                parts += "\"previewOnly\":${json(formatted)}"
                recent.addLast("seq=${e.seq} utcMillis=${e.utc} link=${e.link}\n$formatted")
                while (recent.size > 8) recent.removeFirst()
                preview = recent.joinToString("\n\n").takeLast(48_000)
            }
        } else {
            parts += "\"detail\":${json(e.detail)}"
        }
        return parts.joinToString(",", "{", "}")
    }

    override fun close() { synchronized(gate) { accepting = false } }
    fun awaitStopped(timeoutMillis: Long = 10_000): Boolean { worker.join(timeoutMillis); return !worker.isAlive }

    /** Captures byte lengths at a complete-record boundary, then streams immutable prefixes. */
    fun export(output: OutputStream) {
        val target = synchronized(gate) { sequence }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (lastWrittenSeq < target && worker.isAlive) {
            check(System.nanoTime() < deadline) { "Capture flush timed out; recording remains on device" }
            Thread.sleep(10)
        }
        if (!accepting) check(awaitStopped()) { "Capture is still closing; retry export" }
        val snapshot = synchronized(io) { files(root).map { it to it.length() } }
        exportSnapshot(root, snapshot, output, status())
    }

    companion object {
        const val SCOPE = "Full CSM RX/TX for 0x5200..0x52ff, including unknown fields and malformed TLV bodies. Other CSM messages: ID/name/length only; payload excluded. No video, file-transfer assets, raw link packets, or automatic Route Sharing capability negotiation. No frames before enabling capture. Empty capture does not prove the iPhone sent no route."
        fun files(root: File): List<File> = if (!root.exists()) emptyList() else root.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList()
        fun exportSaved(root: File, output: OutputStream) = exportSnapshot(root, files(root).map { it to it.length() }, output, "No live writer")
        private fun exportSnapshot(root: File, snapshot: List<Pair<File, Long>>, output: OutputStream, state: String) {
            check(snapshot.isNotEmpty()) { "No recordings to export" }
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("export.json"))
                zip.write(("{\"schema\":1,\"utcMillis\":${System.currentTimeMillis()},\"status\":${json(state)},\"scope\":${json(SCOPE)},\"files\":[" +
                    snapshot.joinToString(",") { (f, n) -> "{\"path\":${json(f.relativeTo(root).invariantSeparatorsPath)},\"bytes\":$n}" } + "]}").toByteArray())
                zip.closeEntry()
                val buffer = ByteArray(32 * 1024)
                for ((file, size) in snapshot) {
                    zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath))
                    file.inputStream().use { input ->
                        var left = size
                        while (left > 0) {
                            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                            check(count > 0) { "Recording changed during export" }
                            zip.write(buffer, 0, count); left -= count
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
        fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
            val digits = "0123456789abcdef"
            for (b in bytes) { val n = b.toInt() and 255; append(digits[n ushr 4]); append(digits[n and 15]) }
        }
        fun json(value: String): String = buildString {
            append('"')
            for (c in value) when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
            }
            append('"')
        }
    }
}
