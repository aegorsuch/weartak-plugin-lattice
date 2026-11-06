// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.lattice.core

import java.time.Instant

/** One store per connection; a reconnect discards the old partial view before resnapshotting. */
class TrackStore {
    private data class Version(val delivered: Instant, val sourceUpdated: Instant?)
    private val tracks = linkedMapOf<String, Track>()
    private val versions = HashMap<String, Version>()

    fun apply(event: EntityEvent, now: Instant) {
        expire(now)
        if (event is EntityEvent.Heartbeat) return
        val id = when (event) {
            is EntityEvent.Upsert -> event.track.id
            is EntityEvent.Remove -> event.id
            EntityEvent.Heartbeat -> return
        }
        val version = when (event) {
            is EntityEvent.Upsert -> event.version
            is EntityEvent.Remove -> event.version
            EntityEvent.Heartbeat -> return
        }
        val previous = versions[id]
        if (previous != null && version <= previous.delivered) return
        if (event is EntityEvent.Upsert &&
            previous?.sourceUpdated?.let { event.track.sourceUpdated < it } == true) return
        if (previous == null && versions.size >= MAX_IDENTITIES) throw ProtocolFailure()
        if (event is EntityEvent.Upsert && id !in tracks && tracks.size >= MAX_TRACKS) throw ProtocolFailure()
        versions[id] = Version(version, if (event is EntityEvent.Upsert) event.track.sourceUpdated else previous?.sourceUpdated)
        when (event) {
            is EntityEvent.Upsert -> if (event.track.expires > now) tracks[id] = event.track else tracks.remove(id)
            is EntityEvent.Remove -> tracks.remove(id)
            EntityEvent.Heartbeat -> Unit
        }
    }
    fun snapshot(now: Instant): List<Track> {
        expire(now)
        return tracks.values.toList()
    }
    private fun expire(now: Instant) { tracks.entries.removeAll { it.value.expires <= now } }
    companion object {
        const val MAX_TRACKS = 256
        const val MAX_IDENTITIES = 512
    }
}
