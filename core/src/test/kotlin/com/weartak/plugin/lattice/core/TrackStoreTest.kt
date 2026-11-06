// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.lattice.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TrackStoreTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val track = Track("one", "Synthetic", "UNKNOWN", "LAND", "test", 0.0, 0.0, now, now.plusSeconds(60), true)
    @Test fun updateDeleteAndOutOfOrderReplayAreVersioned() {
        val store = TrackStore()
        store.apply(EntityEvent.Upsert(track, now), now)
        store.apply(EntityEvent.Upsert(track.copy(latitude = 1.0), now.plusSeconds(1)), now)
        store.apply(EntityEvent.Upsert(track.copy(latitude = 2.0), now), now)
        assertEquals(1.0, store.snapshot(now).single().latitude, 0.0)
        store.apply(EntityEvent.Remove(track.id, now.plusSeconds(2)), now)
        store.apply(EntityEvent.Upsert(track, now.plusSeconds(1)), now)
        assertTrue(store.snapshot(now).isEmpty())
    }
    @Test fun duplicateCannotChangeSameVersion() {
        val store = TrackStore()
        store.apply(EntityEvent.Upsert(track, now), now)
        store.apply(EntityEvent.Upsert(track.copy(latitude = 2.0), now), now)
        assertEquals(0.0, store.snapshot(now).single().latitude, 0.0)
    }
    @Test fun newerDeliveryCannotOverwriteNewerSourceObservationWithOlderOne() {
        val store = TrackStore()
        store.apply(EntityEvent.Upsert(track.copy(sourceUpdated = now.plusSeconds(1)), now), now)
        store.apply(EntityEvent.Upsert(track.copy(latitude = 2.0), now.plusSeconds(2)), now)
        assertEquals(0.0, store.snapshot(now).single().latitude, 0.0)
    }
    @Test fun expiryWithoutAnyFurtherNetworkTrafficRemovesTrack() {
        val store = TrackStore()
        store.apply(EntityEvent.Upsert(track, now), now)
        assertTrue(store.snapshot(track.expires).isEmpty())
        store.apply(EntityEvent.Upsert(track, now), now)
        assertTrue(store.snapshot(now).isEmpty())
    }
    @Test fun expiryRetainsSourceWatermarkAgainstNewerDeliveryOfOlderObservation() {
        val store = TrackStore()
        val current = track.copy(sourceUpdated = now.plusSeconds(10), expires = now.plusSeconds(20))
        store.apply(EntityEvent.Upsert(current, now.plusSeconds(10)), now.plusSeconds(10))
        assertTrue(store.snapshot(now.plusSeconds(20)).isEmpty())
        store.apply(EntityEvent.Upsert(track, now.plusSeconds(30)), now.plusSeconds(30))
        assertTrue(store.snapshot(now.plusSeconds(30)).isEmpty())
    }
    @Test fun deletionRetainsSourceWatermarkButAllowsGenuinelyNewerObservation() {
        val store = TrackStore()
        store.apply(EntityEvent.Upsert(track.copy(sourceUpdated = now.plusSeconds(10)), now.plusSeconds(10)), now)
        store.apply(EntityEvent.Remove(track.id, now.plusSeconds(20)), now)
        store.apply(EntityEvent.Upsert(track, now.plusSeconds(30)), now)
        assertTrue(store.snapshot(now).isEmpty())
        val fresh = track.copy(sourceUpdated = now.plusSeconds(40))
        store.apply(EntityEvent.Upsert(fresh, now.plusSeconds(40)), now)
        assertEquals(fresh, store.snapshot(now).single())
    }
    @Test fun reconnectAndSourceSwitchCannotRetainOldView() {
        val old = TrackStore()
        old.apply(EntityEvent.Upsert(track, now), now)
        val replacement = TrackStore()
        assertTrue(replacement.snapshot(now).isEmpty())
        replacement.apply(EntityEvent.Upsert(track.copy(name = "Other origin"), now), now)
        assertEquals("Other origin", replacement.snapshot(now).single().name)
        assertEquals("Synthetic", old.snapshot(now).single().name)
    }
    @Test fun trackBudgetFailsExplicitlyInsteadOfSilentEviction() {
        val store = TrackStore()
        repeat(TrackStore.MAX_TRACKS) { store.apply(EntityEvent.Upsert(track.copy(id = "$it"), now), now) }
        assertThrows(ProtocolFailure::class.java) { store.apply(EntityEvent.Upsert(track.copy(id = "overflow"), now), now) }
        assertEquals(256, store.snapshot(now).size)
    }
    @Test fun tombstonesRemainBoundedWithoutAllowingStaleResurrection() {
        val store = TrackStore()
        repeat(TrackStore.MAX_IDENTITIES) { store.apply(EntityEvent.Remove("$it", now), now) }
        assertThrows(ProtocolFailure::class.java) { store.apply(EntityEvent.Remove("overflow", now), now) }
    }
}
