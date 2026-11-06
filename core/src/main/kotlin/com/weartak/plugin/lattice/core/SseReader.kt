// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.lattice.core

import java.io.InputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class ProtocolFailure : IOException("Invalid or unsupported Lattice stream data.")

/** Bounded SSE framing. EOF never commits an unfinished event; no resume cursor is invented. */
object SseReader {
    const val MAX_FRAME_BYTES = 65_536
    fun read(input: InputStream, onEvent: (String, String) -> Unit) {
        val line = ArrayList<Byte>()
        val data = StringBuilder()
        var type = ""
        var frameBytes = 0
        var afterCr = false
        var firstLine = true
        fun finishLine() {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            var text = try {
                decoder.decode(ByteBuffer.wrap(line.toByteArray())).toString()
            } catch (_: java.nio.charset.CharacterCodingException) {
                throw ProtocolFailure()
            }
            line.clear()
            if (firstLine) { text = text.removePrefix("\uFEFF"); firstLine = false }
            if (text.isEmpty()) {
                if (data.isNotEmpty()) onEvent(type.ifEmpty { "message" }, data.dropLast(1).toString())
                data.setLength(0)
                type = ""
                frameBytes = 0
            } else if (!text.startsWith(":")) {
                val colon = text.indexOf(':')
                val key = if (colon < 0) text else text.substring(0, colon)
                val value = if (colon < 0) "" else text.substring(colon + 1).removePrefix(" ")
                when (key) {
                    "event" -> type = value
                    "data" -> data.append(value).append('\n')
                    // Retry and event IDs are not authoritative resume tokens for this API.
                    "id", "retry" -> Unit
                }
            }
        }
        while (true) {
            val next = input.read()
            if (next < 0) return
            if (++frameBytes > MAX_FRAME_BYTES) throw ProtocolFailure()
            if (next == 10 && afterCr) { afterCr = false; continue }
            afterCr = next == 13
            if (next == 10 || next == 13) finishLine() else line.add(next.toByte())
        }
    }
}
