// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.lattice.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class ReadOnlyStreamTest {
    private val config = ConnectionConfig("https://synthetic.invalid", "synthetic-token", "synthetic-sandbox")
    private class Connection(url: URL, val status: Int = 200, val mime: String = "text/event-stream; charset=utf-8") : HttpsURLConnection(url) {
        val body = ByteArrayOutputStream()
        var disconnected = false
        override fun getOutputStream() = body
        override fun getInputStream() = "event: heartbeat\ndata: {\"timestamp\":\"2026-01-01T00:00:00Z\"}\n\n".byteInputStream()
        override fun getResponseCode() = status
        override fun getContentType() = mime
        override fun disconnect() { disconnected = true }
        override fun connect() {}
        override fun usingProxy() = false
        override fun getCipherSuite() = "synthetic"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
    @Test fun onlyReadSubscriptionEndpointIsUsedAndRedirectsNeverFollowed() {
        val connection = Connection(URL(config.streamUrl))
        var count = 0
        val stream = ReadOnlyStream {
            assertEquals("https://synthetic.invalid/api/v1/entities/stream", it.toString())
            connection
        }
        assertThrows(IOException::class.java) { stream.receive(config, null) { type, _ -> assertEquals("heartbeat", type); count++ } }
        assertEquals("POST", connection.requestMethod)
        assertEquals(String(ReadOnlyStream.REQUEST), connection.body.toString("UTF-8"))
        assertEquals("Bearer synthetic-token", connection.getRequestProperty("Authorization"))
        assertEquals("Bearer synthetic-sandbox", connection.getRequestProperty("Anduril-Sandbox-Authorization"))
        assertFalse(connection.instanceFollowRedirects)
        assertEquals(1, count)
        assertTrue(connection.disconnected)
    }
    @Test fun httpErrorsIncludingRedirectNeverLookLikeHealthyStreams() {
        for (code in listOf(204, 301, 302, 307, 400, 401, 403, 429, 500)) {
            val connection = Connection(URL(config.streamUrl), code)
            val error = assertThrows(HttpFailure::class.java) { ReadOnlyStream { connection }.receive(config, null) { _, _ -> fail() } }
            assertEquals(code, error.status)
            assertTrue(connection.disconnected)
        }
    }
    @Test fun jsonOrHtmlSuccessResponseIsNotAnSseStream() {
        for (mime in listOf("application/json", "text/html", "")) {
            assertThrows(ProtocolFailure::class.java) {
                ReadOnlyStream { Connection(URL(config.streamUrl), mime = mime) }.receive(config, null) { _, _ -> fail() }
            }
        }
    }
    @Test fun cancelledStreamNeverOpensNetwork() {
        val stream = ReadOnlyStream { fail("Unexpected network access"); throw AssertionError() }
        stream.close()
        stream.receive(config, null) { _, _ -> fail() }
    }
    @Test fun originAndCredentialsRejectUnsafeInputsAndNeverPrintSecrets() {
        for (origin in listOf("http://host", "https://user:pass@host", "https://host/path", "https://host?token=x", "https://host#fragment", "https://host:0")) {
            assertThrows(IllegalArgumentException::class.java) { ConnectionConfig(origin, "token") }
        }
        for (token in listOf("", "Bearer secret", "secret\r\nInjected:value", "x".repeat(8193))) {
            assertThrows(IllegalArgumentException::class.java) { ConnectionConfig("https://host", token) }
        }
        assertFalse(config.toString().contains("synthetic-token"))
        assertEquals("https://host:8443/api/v1/entities/stream", ConnectionConfig("https://host:8443/", "token").streamUrl)
    }
}
