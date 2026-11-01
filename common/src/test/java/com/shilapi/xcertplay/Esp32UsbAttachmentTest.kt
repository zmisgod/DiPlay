package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class Esp32UsbAttachmentTest {
    private val c get()=RuntimeEnvironment.getApplication()
    private val manager get()=c.getSystemService(Context.USB_SERVICE) as UsbManager
    private fun device(vid:Int=0x303a,pid:Int=0x1001)=mock(UsbDevice::class.java).also {
        `when`(it.vendorId).thenReturn(vid);`when`(it.productId).thenReturn(pid)
        `when`(it.deviceName).thenReturn("/dev/bus/usb/001/$vid")
    }
    private fun attachment(d:UsbDevice)=Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE,d)
    @Test fun onlyEnumeratedEsp32CanTriggerAutomaticConnection() {
        val d=device();assertNull(Esp32UsbAttachment.device(c,attachment(d)))
        shadowOf(manager).addOrUpdateUsbDevice(d,true)
        assertSame(d,Esp32UsbAttachment.device(c,attachment(d)))
        val iphone=device(0x05ac,0x12a8);shadowOf(manager).addOrUpdateUsbDevice(iphone,true)
        assertNull(Esp32UsbAttachment.device(c,attachment(iphone)))
        assertNull(Esp32UsbAttachment.device(c,Intent("arbitrary.action").putExtra(UsbManager.EXTRA_DEVICE,d)))
        shadowOf(manager).removeUsbDevice(d);assertNull(Esp32UsbAttachment.device(c,attachment(d)))
    }
    @Test fun automaticStartRequiresExistingOptInAndUsbTransport() {
        val prefs=c.getSharedPreferences("esp32_display",0)
        assertFalse(Esp32UsbAttachment.enabled(c))
        prefs.edit().putBoolean("enabled",true).apply();assertFalse(Esp32UsbAttachment.enabled(c))
        Esp32Settings.setTransport(c,1);assertTrue(Esp32UsbAttachment.enabled(c))
        prefs.edit().putBoolean("enabled",false).apply();assertFalse(Esp32UsbAttachment.enabled(c))
    }
    @Test fun denialDoesNotRepeatPromptUntilDetachAndReattach() {
        val d=device();shadowOf(manager).addOrUpdateUsbDevice(d,false)
        c.getSharedPreferences("esp32_display",0).edit().putBoolean("enabled",true).putInt("transport",1).apply()
        var prompts=0;val monitor=Esp32UsbAttachmentMonitor(c){prompts++}
        monitor.start();monitor.scan();monitor.scan();assertEquals(1,prompts)
        c.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).putExtra(UsbManager.EXTRA_DEVICE,d))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        c.sendBroadcast(attachment(d));shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(2,prompts);monitor.close()
    }
    @Test fun authorizedReattachStartsCompanionWithoutStartingCarplayOrChangingNetworkSettings() {
        val d=device();shadowOf(manager).addOrUpdateUsbDevice(d,true)
        c.getSharedPreferences("esp32_display",0).edit().putBoolean("enabled",true).putInt("transport",1).apply()
        val before=AirPlayPersistence.loadWirelessEnabled(c)
        val controller=Robolectric.buildActivity(Esp32UsbAttachActivity::class.java,attachment(d)).create()
        assertTrue(controller.get().isFinishing)
        assertEquals(Esp32DisplayService::class.java.name,shadowOf(c).nextStartedService.component!!.className)
        assertNull(shadowOf(c).nextStartedActivity)
        assertEquals(before,AirPlayPersistence.loadWirelessEnabled(c))
        controller.destroy()
    }
}
