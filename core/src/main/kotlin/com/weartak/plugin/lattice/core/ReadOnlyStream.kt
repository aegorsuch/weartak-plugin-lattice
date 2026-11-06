// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.lattice.core

import java.io.IOException
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

class HttpFailure(val status: Int) : IOException("Lattice HTTP $status")

class ReadOnlyStream(
    private val open: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
) : AutoCloseable {
    private val cancelled = AtomicBoolean()
    private val connection = AtomicReference<HttpsURLConnection?>()
    fun receive(config: ConnectionConfig, tls: SSLSocketFactory?, onEvent: (String, String) -> Unit) {
        if (cancelled.get()) return
        val client = open(URL(config.streamUrl))
        connection.set(client)
        try {
            if (cancelled.get()) return
            if (tls != null) client.sslSocketFactory = tls
            client.instanceFollowRedirects = false
            client.connectTimeout = 15_000
            client.readTimeout = 45_000
            client.requestMethod = "POST"
            client.doOutput = true
            client.useCaches = false
            client.setRequestProperty("Accept", "text/event-stream")
            client.setRequestProperty("Content-Type", "application/json")
            client.setRequestProperty("Authorization", "Bearer ${config.token}")
            if (config.sandboxToken.isNotEmpty()) {
                client.setRequestProperty("Anduril-Sandbox-Authorization", "Bearer ${config.sandboxToken}")
            }
            client.setFixedLengthStreamingMode(REQUEST.size)
            client.outputStream.use { it.write(REQUEST) }
            if (client.responseCode != 200) throw HttpFailure(client.responseCode)
            if (client.contentType?.substringBefore(';')?.trim()?.lowercase() != "text/event-stream") throw ProtocolFailure()
            client.inputStream.buffered().use { input ->
                SseReader.read(input) { type, payload ->
                    if (cancelled.get()) throw IOException("Stream cancelled.")
                    onEvent(type, payload)
                }
            }
            if (!cancelled.get()) throw IOException("Lattice stream ended.")
        } finally {
            connection.compareAndSet(client, null)
            client.disconnect()
        }
    }
    override fun close() {
        cancelled.set(true)
        connection.getAndSet(null)?.disconnect()
    }
    companion object {
        // This POST establishes a read subscription, never publishes or modifies an entity.
        val REQUEST: ByteArray get() = """{"heartbeatIntervalMS":15000,"preExistingOnly":false}""".toByteArray()
    }
}
