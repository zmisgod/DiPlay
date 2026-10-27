package com.shilapi.xcertplay.navigation

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2Parameter
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

class NavigationJournalTest {
    @get:Rule val temp = TemporaryFolder()
    private fun read(zip: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(zip.inputStream()).use { input ->
            while (true) { val entry = input.nextEntry ?: break; put(entry.name, input.readBytes().toString(Charsets.UTF_8)) }
        }
    }
    private fun stopped(j: NavigationJournal): String {
        j.close(); assertTrue(j.awaitStopped())
        return File(j.directory, "events.jsonl").readText()
    }
    @Test fun rawFramesKeepEveryByteRepeatedUnknownParametersAndMalformedBodies() {
        val journal = NavigationJournal(temp.newFolder(), "test")
        val payload = Iap2ParameterList.of(Iap2Parameter(3, "长路名\n测试\u0000".toByteArray()),
            Iap2Parameter(3, byteArrayOf(1, 2)), Iap2Parameter(65500, ByteArray(4096) { (it % 256).toByte() })).encode()
        val frames = listOf(Iap2Frame(0x5201, payload), Iap2Frame(0x52fe, byteArrayOf(0, 3, 1)), Iap2Frame(0x5204, byteArrayOf()))
        for ((index, frame) in frames.withIndex()) journal.frame("RX", listOf("wired", "wireless-rfcomm", "wireless-tunnel")[index], "link-$index", frame)
        val text = stopped(journal)
        for (frame in frames) assertTrue(text.contains("\"csmHex\":\"${NavigationJournal.hex(frame.encodedFrame())}\""))
        assertEquals(3, text.lineSequence().count { it.contains("\"kind\":\"frame\"") })
        assertTrue(text.contains("\"complete\":true"))
        assertTrue(text.contains("malformed TLV"))
    }
    @Test fun capturesSubscriptionAndDoesNotPersistAuthenticationOrOtherNonNavigationPayloads() {
        val journal = NavigationJournal(temp.newFolder(), "test")
        journal.frame("TX", "wired", "a", Iap2Frame(0x5200, byteArrayOf(0, 4, 0, 1)))
        journal.frame("RX", "wired", "a", Iap2Frame(0xaa03, "SECRET-AUTH".toByteArray()))
        journal.frame("RX", "wired", "a", Iap2Frame(0x5001, "PERSONAL-MUSIC".toByteArray()))
        val text = stopped(journal)
        assertTrue(text.contains("StartRouteGuidanceUpdates"))
        assertEquals(2, text.lineSequence().count { it.contains("message_inventory") })
        assertFalse(text.contains(NavigationJournal.hex("SECRET-AUTH".toByteArray())))
        assertFalse(text.contains("PERSONAL-MUSIC"))
    }
    @Test fun exportFlushesAdmittedRecordsAndPreservesEarlierSessions() {
        val root = temp.newFolder()
        val first = NavigationJournal(root, "first")
        first.event("user_marker", detail = "destination_changed"); stopped(first)
        val before = File(first.directory, "events.jsonl").readBytes()
        val second = NavigationJournal(root, "second")
        repeat(20) { second.frame("RX", "wireless-tunnel", "second", Iap2Frame(0x5201, byteArrayOf())) }
        val zip = ByteArrayOutputStream(); second.export(zip)
        val entries = read(zip.toByteArray())
        assertTrue(entries.containsKey("export.json"))
        assertEquals(before.toString(Charsets.UTF_8), entries["${first.id}/events.jsonl"])
        assertEquals(20, entries.getValue("${second.id}/events.jsonl").lineSequence().count { it.contains("\"kind\":\"frame\"") })
        stopped(second)
        assertArrayEquals(before, File(first.directory, "events.jsonl").readBytes())
    }
    @Test fun stopRejectsNewEventsWithoutErasingAlreadyQueuedData() {
        val journal = NavigationJournal(temp.newFolder(), "test")
        journal.event("user_marker", detail = "route_selected")
        journal.close(); journal.event("user_marker", detail = "must_not_appear")
        val text = stopped(journal)
        assertTrue(text.contains("route_selected")); assertFalse(text.contains("must_not_appear"))
    }
    @Test fun storageCapStopsWithExplicitIncompleteStatusAndRetainsHistory() {
        val root = temp.newFolder()
        val old = File(root, "older.log").apply { writeText("keep me") }
        val journal = NavigationJournal(root, "test", maxBytes = 2048)
        journal.frame("RX", "wired", "a", Iap2Frame(0x5201, ByteArray(4096)))
        assertTrue(journal.awaitStopped())
        val text = File(journal.directory, "events.jsonl").readText()
        assertFalse(journal.recording)
        assertTrue(text.contains("\"complete\":false")); assertTrue(text.contains("storage_limit"))
        assertEquals("keep me", old.readText())
    }
    @Test fun maximumFrameIsRetainedAndJournalLinesRemainSeparate() {
        val journal = NavigationJournal(temp.newFolder(), "test\nmetadata")
        val frame = Iap2Frame(0x52ff, ByteArray(65529) { 0xab.toByte() })
        journal.frame("RX", "wired", "a", frame)
        journal.event("user_marker", detail = "quotes\" newline\n control\u0001")
        val text = stopped(journal)
        assertEquals(4, text.lines().filter { it.isNotEmpty() }.size)
        assertTrue(text.contains(NavigationJournal.hex(frame.encodedFrame())))
        assertTrue(text.contains("\\u0001"))
    }
}
