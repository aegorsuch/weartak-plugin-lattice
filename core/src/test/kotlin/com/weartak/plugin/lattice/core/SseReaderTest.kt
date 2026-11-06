// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.lattice.core

import org.junit.Assert.*
import org.junit.Test

class SseReaderTest {
    private fun read(text: String): List<Pair<String, String>> {
        val events = mutableListOf<Pair<String, String>>()
        SseReader.read(text.byteInputStream()) { type, data -> events.add(type to data) }
        return events
    }
    @Test fun supportsCrLfCrLfCommentsBomAndMultilineData() {
        for (newline in listOf("\n", "\r\n", "\r")) {
            val text = listOf("\uFEFF: comment", "event: entity", "id: unused", "retry: 1", "data: {", "data: }", "", "").joinToString(newline)
            assertEquals(listOf("entity" to "{\n}"), read(text))
        }
    }
    @Test fun eofDoesNotDispatchTruncatedEvent() {
        assertTrue(read("event: entity\ndata: {}").isEmpty())
        assertTrue(read("event: entity\ndata: {}\n").isEmpty())
    }
    @Test fun eventTypeDoesNotLeakAcrossFrames() {
        assertEquals(listOf("entity" to "{}", "message" to "{}"), read("event: entity\ndata: {}\n\ndata: {}\n\n"))
    }
    @Test fun oversizedLineAndManySmallLinesBothFailBound() {
        for (text in listOf("data: " + "x".repeat(SseReader.MAX_FRAME_BYTES), "data:x\n".repeat(10_000))) {
            assertThrows(ProtocolFailure::class.java) { read(text) }
        }
    }
    @Test fun invalidUtf8FailsInsteadOfReplacementCharacters() {
        assertThrows(ProtocolFailure::class.java) {
            SseReader.read(byteArrayOf(0xc3.toByte(), 0x28, 10).inputStream()) { _, _ -> fail() }
        }
    }
}
