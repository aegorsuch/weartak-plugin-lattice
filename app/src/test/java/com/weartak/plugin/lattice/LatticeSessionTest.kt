// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.lattice

import com.weartak.plugin.lattice.core.ConnectionConfig
import com.weartak.plugin.lattice.core.ReadOnlyStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import java.security.cert.Certificate
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LatticeSessionTest {
    private val config = ConnectionConfig("https://synthetic.invalid", "synthetic-token")
    private lateinit var connection: Connection

    private class HeldInput(text: String) : InputStream() {
        private val data = text.toByteArray()
        private var position = 0
        val closed = CountDownLatch(1)
        override fun read(): Int {
            if (position < data.size) return data[position++].toInt() and 255
            closed.await(3, TimeUnit.SECONDS)
            return -1
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (position < data.size) {
                val count = minOf(length, data.size - position)
                data.copyInto(bytes, offset, position, position + count)
                position += count
                return count
            }
            return read()
        }
        override fun close() { closed.countDown() }
    }
    private class Connection(text: String, val status: Int = 200) : HttpsURLConnection(URL("https://synthetic.invalid")) {
        private val input = HeldInput(text)
        val disconnected = CountDownLatch(1)
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = input
        override fun getResponseCode() = status
        override fun getContentType() = "text/event-stream"
        override fun disconnect() { input.close(); disconnected.countDown() }
        override fun connect() {}
        override fun usingProxy() = false
        override fun getCipherSuite() = "synthetic"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
    private fun frame(): String {
        val now = Instant.now()
        return """event: entity
data: {"eventType":"EVENT_TYPE_PREEXISTING","time":"$now","entity":{"entityId":"synthetic","isLive":true,"expiryTime":"${now.plusSeconds(60)}","aliases":{"name":"Synthetic"},"milView":{"disposition":"DISPOSITION_UNKNOWN","environment":"ENVIRONMENT_LAND"},"ontology":{"template":"TEMPLATE_TRACK","platformType":"Synthetic"},"provenance":{"sourceUpdateTime":"$now"},"location":{"position":{"latitudeDegrees":0,"longitudeDegrees":0}}}}

"""
    }
    private fun awaitState(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("Expected session state", condition())
    }
    @Before fun setup() {
        LatticeSession.hostStopped()
        LatticeSession.foreground(true)
        connection = Connection(frame())
        LatticeSession.createStream = { ReadOnlyStream { connection } }
    }
    @After fun cleanup() {
        LatticeSession.foreground(false)
        LatticeSession.hostStopped()
        LatticeSession.createStream = { ReadOnlyStream() }
        connection.disconnect()
        awaitState { LatticeSession.workerIdle() }
    }
    @Test fun receptionRequiresBothHostStartAndExplicitForegroundOptIn() {
        LatticeSession.start(config, null)
        assertFalse(LatticeSession.snapshot().receiving)
        LatticeSession.hostStarted()
        assertFalse(LatticeSession.snapshot().receiving)
        LatticeSession.start(config, null)
        awaitState { LatticeSession.snapshot().tracks.size == 1 }
        LatticeSession.foreground(false)
        assertFalse(LatticeSession.snapshot().receiving)
        assertTrue(LatticeSession.snapshot().tracks.isEmpty())
        assertTrue(connection.disconnected.await(1, TimeUnit.SECONDS))
    }
    @Test fun hostStopImmediatelyRevokesAndClearsSourceView() {
        LatticeSession.hostStarted()
        LatticeSession.start(config, null)
        awaitState { LatticeSession.snapshot().tracks.size == 1 }
        LatticeSession.hostStopped()
        assertTrue(LatticeSession.snapshot().tracks.isEmpty())
        assertEquals(R.string.host_inactive, LatticeSession.snapshot().message)
    }
    @Test fun authFailureNeverProducesHealthyEmptySnapshot() {
        connection = Connection("", 401)
        LatticeSession.hostStarted()
        LatticeSession.start(config, null)
        awaitState { LatticeSession.snapshot().message == R.string.auth_failed }
        assertFalse(LatticeSession.snapshot().receiving)
    }
    @Test fun invalidDataFailsVisiblyAndClearsAllPriorTracks() {
        connection = Connection(frame() + "event: entity\ndata: {}\n\n")
        LatticeSession.hostStarted()
        LatticeSession.start(config, null)
        awaitState { LatticeSession.snapshot().message == R.string.data_failed }
        assertTrue(LatticeSession.snapshot().tracks.isEmpty())
    }
    @Test fun applicationHeartbeatDeadlineClearsViewDespiteOpenConnection() {
        LatticeSession.hostStarted()
        LatticeSession.start(config, null)
        awaitState { LatticeSession.snapshot().tracks.isNotEmpty() }
        ShadowSystemClock.advanceBy(Duration.ofSeconds(46))
        assertEquals(R.string.timeout, LatticeSession.snapshot().message)
        assertTrue(LatticeSession.snapshot().tracks.isEmpty())
    }
    @Test fun eventRateBudgetFailsExplicitlyAtOneHundredAndOne() {
        val heartbeat = "event: heartbeat\ndata: {\"timestamp\":\"${Instant.now()}\"}\n\n"
        connection = Connection(heartbeat.repeat(101))
        LatticeSession.hostStarted()
        LatticeSession.start(config, null)
        awaitState { LatticeSession.snapshot().message == R.string.rate_failed }
        assertTrue(LatticeSession.snapshot().tracks.isEmpty())
    }
}
