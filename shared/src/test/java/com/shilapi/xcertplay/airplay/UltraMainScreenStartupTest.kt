package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class UltraMainScreenStartupTest {
    @Test fun missingMainRequestsOnceAndNeverRetriesFailures() {
        for (accepted in listOf(false,true)) {
            val startup = UltraMainScreenStartup()
            var calls = 0
            assertEquals(accepted, startup.requestIfMissing(true,true,false) { calls++; accepted })
            repeat(5) { assertFalse(startup.requestIfMissing(true,true,false) { calls++; true }) }
            assertEquals(1,calls)
        }
    }
    @Test fun normalVideoClosedSessionMissingEventAndHostUiNeverGetRecoveryCommands() {
        for ((active,event,main) in listOf(Triple(true,true,true),Triple(false,true,false),Triple(true,false,false))) {
            assertFalse(UltraMainScreenStartup().requestIfMissing(active,event,main) { error("Unexpected command") })
        }
        val startup = UltraMainScreenStartup()
        startup.cancel()
        assertFalse(startup.requestIfMissing(true,true,false) { error("UI returned to host") })
    }
}
