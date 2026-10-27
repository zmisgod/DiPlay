package com.shilapi.xcertplay.navigation

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.io.File
import java.io.OutputStream

/** Process-wide recorder: leaving Settings does not stop a running CarPlay capture. */
object NavigationCapture {
    private var app: Context? = null
    @Volatile private var writer: NavigationJournal? = null
    @Volatile private var error: String? = null
    private fun prefs() = requireNotNull(app).getSharedPreferences("navigation-capture", Context.MODE_PRIVATE)
    @Synchronized fun initialize(context: Context) {
        if (app != null) return
        app = context.applicationContext
        if (prefs().getBoolean("enabled", false)) start("process_restart")
    }
    private fun root() = File(requireNotNull(app).filesDir, "navigation-captures")
    fun enabled(): Boolean = writer?.recording == true
    @Synchronized fun setEnabled(context: Context, enabled: Boolean) {
        initialize(context)
        if (enabled && !enabled()) start("user_enabled")
        if (!enabled) { writer?.event("user_disabled"); writer?.close() }
        prefs().edit().putBoolean("enabled", enabled && enabled()).apply()
    }
    private fun start(reason: String) {
        val old = writer
        if (old != null && !old.awaitStopped()) { error = "Previous writer is still closing"; return }
        runCatching {
            val context = requireNotNull(app)
            @Suppress("DEPRECATION") val info = context.packageManager.getPackageInfo(context.packageName, 0)
            NavigationJournal(root(), "package=${context.packageName} version=${info.versionName} sdk=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER}/${Build.MODEL} reason=$reason")
        }.onSuccess { writer = it; error = null }.onFailure {
            error = "${it.javaClass.simpleName}: ${it.message}"
            prefs().edit().putBoolean("enabled", false).apply()
        }
    }
    fun frame(direction: String, channel: String, link: String, frame: Iap2Frame) {
        writer?.takeIf { it.recording }?.frame(direction, channel, link, frame)
    }
    fun event(kind: String, channel: String = "", link: String = "", detail: String = "") {
        writer?.event(kind, channel, link, detail)
    }
    fun preview(context: Context): String {
        initialize(context)
        return listOfNotNull(error, writer?.preview() ?: "recording=false\npath=${root()}\n${NavigationJournal.SCOPE}").joinToString("\n")
    }
    /** Caller uses a background thread. Export does not clear or redact the evidence. */
    @Synchronized fun export(context: Context, output: OutputStream) {
        initialize(context)
        writer?.export(output) ?: NavigationJournal.exportSaved(root(), output)
    }
}
