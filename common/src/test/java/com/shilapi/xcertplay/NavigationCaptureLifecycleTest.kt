package com.shilapi.xcertplay

import android.content.Intent
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.navigation.NavigationCapture
import com.shilapi.xcertplay.navigation.NavigationJournal
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class NavigationCaptureLifecycleTest {
    @Test fun leavingSettingsKeepsRecordingAndExportAfterStopIncludesCleanEnd() {
        val app = RuntimeEnvironment.getApplication()
        NavigationCapture.initialize(app)
        NavigationCapture.setEnabled(app, false)
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        NavigationCapture.setEnabled(app, true)
        try {
            assertTrue(NavigationCapture.enabled())
            val before = Iap2Frame(0x5201, byteArrayOf(0,5,0,1,1))
            val after = Iap2Frame(0x5202, byteArrayOf(0,5,0,3,4))
            NavigationCapture.frame("RX", "wired", "lifecycle-test", before)
            activity.pause().stop()
            assertTrue(NavigationCapture.enabled())
            NavigationCapture.frame("RX", "wireless-tunnel", "lifecycle-test", after)
            NavigationCapture.event("user_marker", detail = "route_selected")
            NavigationCapture.setEnabled(app, false)
            NavigationCapture.frame("RX", "wired", "must-not-be-recorded", before)
            val zip = ByteArrayOutputStream()
            NavigationCapture.export(app, zip)
            val text = buildString {
                ZipInputStream(zip.toByteArray().inputStream()).use { input ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        if (entry.name.endsWith("events.jsonl")) append(input.readBytes().toString(Charsets.UTF_8))
                    }
                }
            }
            assertTrue(text.contains(NavigationJournal.hex(before.encodedFrame())))
            assertTrue(text.contains(NavigationJournal.hex(after.encodedFrame())))
            assertTrue(text.contains("route_selected"))
            assertTrue(text.contains("\"complete\":true"))
            assertFalse(text.contains("must-not-be-recorded"))
        } finally {
            NavigationCapture.setEnabled(app, false)
            activity.destroy()
        }
    }
}
