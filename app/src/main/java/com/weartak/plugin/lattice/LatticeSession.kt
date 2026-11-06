// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.lattice

import android.os.SystemClock
import com.weartak.plugin.lattice.core.*
import java.io.IOException
import java.time.Instant
import java.util.concurrent.Executors
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory

/** Process-local gate shared by the host-bound service and native plugin screen. */
object LatticeSession {
    data class ViewState(val message: Int, val httpStatus: Int, val tracks: List<Track>, val ignored: Int, val receiving: Boolean)
    private val executor = Executors.newSingleThreadExecutor()
    private var generation = 0L
    private var hostReady = false
    private var foreground = false
    private var workerBusy = false
    private var stream: ReadOnlyStream? = null
    private var store = TrackStore()
    private var message = R.string.host_inactive
    private var httpStatus = 0
    private var ignored = 0
    private var lastVerified = 0L
    private var rateWindow = 0L
    private var rateCount = 0
    internal var createStream: () -> ReadOnlyStream = { ReadOnlyStream() }
    @Synchronized
    internal fun workerIdle() = !workerBusy

    @Synchronized
    fun hostStarted() { hostReady = true; if (stream == null) message = R.string.stopped }

    @Synchronized
    fun hostStopped() { hostReady = false; stop(R.string.host_inactive) }

    @Synchronized
    fun foreground(value: Boolean) { foreground = value; if (!value) stop() }

    @Synchronized
    fun start(config: ConnectionConfig, tls: SSLSocketFactory?) {
        if (!hostReady || !foreground) { stop(R.string.host_inactive); return }
        if (stream != null) return
        if (workerBusy) { message = R.string.closing; return }
        generation++
        val current = generation
        val receiver = createStream()
        stream = receiver
        workerBusy = true
        store = TrackStore()
        ignored = 0
        httpStatus = 0
        message = R.string.connecting
        lastVerified = SystemClock.elapsedRealtime()
        rateWindow = lastVerified
        rateCount = 0
        executor.execute {
            try {
                receiver.receive(config, tls) { type, payload ->
                    val now = Instant.now()
                    val event = EntityDecoder.decode(type, payload, now)
                    synchronized(this) {
                        if (current != generation) throw IOException("Session cancelled.")
                        val elapsed = SystemClock.elapsedRealtime()
                        if (elapsed - rateWindow >= 1000) { rateWindow = elapsed; rateCount = 0 }
                        if (++rateCount > 100) {
                            stop(R.string.rate_failed)
                            throw IOException("Rate limit exceeded.")
                        }
                        store.apply(event, now)
                        if (event is EntityEvent.Remove && event.unsupported && ignored < Int.MAX_VALUE) ignored++
                        lastVerified = elapsed
                        message = R.string.receiving
                    }
                }
            } catch (error: HttpFailure) {
                fail(current, if (error.status in setOf(401, 403)) R.string.auth_failed else R.string.http_failed, error.status)
            } catch (_: ProtocolFailure) {
                fail(current, R.string.data_failed)
            } catch (_: SSLException) {
                fail(current, R.string.tls_failed)
            } catch (_: IOException) {
                fail(current, R.string.network_failed)
            } catch (_: SecurityException) {
                fail(current, R.string.network_failed)
            } finally {
                receiver.close()
                synchronized(this) { workerBusy = false }
            }
        }
    }

    @Synchronized
    private fun fail(current: Long, reason: Int, status: Int = 0) {
        if (current == generation) { stop(reason); httpStatus = status }
    }

    @Synchronized
    fun stop(reason: Int = R.string.stopped) {
        generation++
        stream?.close()
        stream = null
        store = TrackStore()
        message = reason
        httpStatus = 0
        ignored = 0
    }

    @Synchronized
    fun snapshot(): ViewState {
        if (stream != null && SystemClock.elapsedRealtime() - lastVerified > 45_000) stop(R.string.timeout)
        return ViewState(message, httpStatus, store.snapshot(Instant.now()), ignored, stream != null)
    }
}
