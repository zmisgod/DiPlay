package com.shilapi.xcertplay.airplay

/** Per-session, one-shot recovery. A host UI request or close permanently cancels it. */
internal class UltraMainScreenStartup {
    private var finished = false
    @Synchronized fun cancel() { finished = true }
    @Synchronized fun requestIfMissing(active: Boolean, eventReady: Boolean, mainSetup: Boolean,
        send: () -> Boolean): Boolean {
        if (finished) return false
        finished = true
        if (!active || !eventReady || mainSetup) return false
        return send()
    }
}
