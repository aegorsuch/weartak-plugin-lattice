// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.lattice.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class EntityContractTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val fixture = javaClass.getResource("/point-event.json")!!.readText()
    private fun decode(json: String = fixture) = EntityDecoder.decode("entity", json, now)
    private fun entity(vararg fields: Pair<String, JsonElement?>): String {
        val root = Json.parseToJsonElement(fixture).jsonObject.toMutableMap()
        val value = root.getValue("entity").jsonObject.toMutableMap()
        fields.forEach { (key, item) -> if (item == null) value.remove(key) else value[key] = item }
        root["entity"] = JsonObject(value)
        return JsonObject(root).toString()
    }

    @Test fun zeroCoordinatesRemainValidAndUnknownDoesNotBecomeFriendly() {
        val event = decode() as EntityEvent.Upsert
        assertEquals(0.0, event.track.latitude, 0.0)
        assertEquals(0.0, event.track.longitude, 0.0)
        assertEquals("UNKNOWN", event.track.affiliation)
        assertTrue(event.track.simulated)
    }
    @Test fun geodeticCoordinatesUseDocumentedLocationShape() {
        val geo = Json.parseToJsonElement("""{"kinematicsGeodetic":{"location":{"latitudeDegrees":90,"longitudeDegrees":-180},"measurementTime":"2026-01-01T00:00:00Z"}}""")
        val event = decode(entity("location" to null, "kinematics" to geo)) as EntityEvent.Upsert
        assertEquals(90.0, event.track.latitude, 0.0)
        assertEquals(-180.0, event.track.longitude, 0.0)
    }
    @Test fun missingLatitudeDoesNotDefaultToZero() {
        assertThrows(ProtocolFailure::class.java) { decode(fixture.replace("\"latitudeDegrees\": 0.0,", "")) }
    }
    @Test fun invalidCoordinatesFailInsteadOfClamping() {
        for (invalid in listOf("91", "-91", "1e999", "\"0\"", "null")) {
            assertThrows(ProtocolFailure::class.java) { decode(fixture.replace("\"latitudeDegrees\": 0.0", "\"latitudeDegrees\": $invalid")) }
        }
    }
    @Test fun identityMustBeNonblankBoundedString() {
        for (id in listOf(JsonPrimitive(""), JsonPrimitive("x".repeat(257)), JsonPrimitive(123), JsonPrimitive("bad\nid"))) {
            assertThrows(ProtocolFailure::class.java) { decode(entity("entityId" to id)) }
        }
    }
    @Test fun missingAndUnrecognizedAffiliationOrTypeFail() {
        for (json in listOf(
            fixture.replace("DISPOSITION_UNKNOWN", "FRIENDLY"),
            fixture.replace("DISPOSITION_UNKNOWN", "DISPOSITION_NEW"),
            fixture.replace("ENVIRONMENT_SURFACE", "ENVIRONMENT_NEW"),
            entity("milView" to null),
            entity("ontology" to null),
        )) assertThrows(ProtocolFailure::class.java) { decode(json) }
    }
    @Test fun oldObservationRemovesTrackEvenWhenEventAndExpiryAreNew() {
        val json = fixture.replace("\"sourceUpdateTime\": \"2026-01-01T00:00:00Z\"", "\"sourceUpdateTime\": \"2025-12-31T23:57:59Z\"")
        assertTrue(decode(json) is EntityEvent.Remove)
    }
    @Test fun measurementAgeCanOnlyReduceFreshness() {
        val tracked = Json.parseToJsonElement("""{"lastMeasurementTime":"2025-12-31T23:57:59Z"}""")
        assertTrue(decode(entity("tracked" to tracked)) is EntityEvent.Remove)
    }
    @Test fun futureMeasurementCannotHideBehindCurrentSourceTime() {
        val tracked = Json.parseToJsonElement("""{"lastMeasurementTime":"2026-01-01T00:01:00Z"}""")
        assertThrows(ProtocolFailure::class.java) { decode(entity("tracked" to tracked)) }
    }
    @Test fun exactExpiryBoundaryRemovesTrack() {
        assertTrue(decode(entity("expiryTime" to JsonPrimitive(now.toString()))) is EntityEvent.Remove)
    }
    @Test fun noExpiryStillHasLocalAgeLimit() {
        val event = decode(entity("expiryTime" to null, "noExpiry" to JsonPrimitive(true))) as EntityEvent.Upsert
        assertEquals(now.plusSeconds(120), event.track.expires)
    }
    @Test fun missingExpiryIsNotPermanentAndFalseStringsAreNotBooleans() {
        assertThrows(ProtocolFailure::class.java) { decode(entity("expiryTime" to null)) }
        assertThrows(ProtocolFailure::class.java) { decode(entity("isLive" to JsonPrimitive("true"))) }
    }
    @Test fun minimalDeleteAndNonLiveEntityRemoveWithoutCoordinates() {
        for (json in listOf(
            """{"eventType":"EVENT_TYPE_DELETED","time":"2026-01-01T00:00:00Z","entity":{"entityId":"one"}}""",
            """{"eventType":"EVENT_TYPE_UPDATE","time":"2026-01-01T00:00:00Z","entity":{"entityId":"one","isLive":false}}""",
        )) assertTrue(decode(json) is EntityEvent.Remove)
    }
    @Test fun heartbeatHonorsOptionalTimestampButValidatesItWhenPresent() {
        assertEquals(EntityEvent.Heartbeat, EntityDecoder.decode("heartbeat", """{"timestamp":"2026-01-01T00:00:00Z"}""", now))
        assertEquals(EntityEvent.Heartbeat, EntityDecoder.decode("heartbeat", "{}", now))
        for (payload in listOf("[]", """{"timestamp":"bad"}""", """{"timestamp":"2025-01-01T00:00:00Z"}""")) {
            assertThrows(ProtocolFailure::class.java) { EntityDecoder.decode("heartbeat", payload, now) }
        }
    }
    @Test fun httpSuccessBodyOrUnknownEventIsNotValidEntitySnapshot() {
        for (payload in listOf("{}", "[]", "null", "{\"entity\":{}", fixture.replace("EVENT_TYPE_PREEXISTING", "EVENT_TYPE_POST_EXPIRY_OVERRIDE"))) {
            assertThrows(ProtocolFailure::class.java) { decode(payload) }
        }
        assertThrows(ProtocolFailure::class.java) { EntityDecoder.decode("message", fixture, now) }
    }
    @Test fun unsupportedShapesRemoveRatherThanFabricateCoordinates() {
        val event = decode(fixture.replace("TEMPLATE_TRACK", "TEMPLATE_GEO")) as EntityEvent.Remove
        assertTrue(event.unsupported)
    }
    @Test fun ambiguousLocationAndKinematicsAreRejected() {
        assertThrows(ProtocolFailure::class.java) { decode(entity("kinematics" to JsonObject(emptyMap()))) }
    }
    @Test fun malformedOptionalComponentsNeverSilentlyBecomeRealOrCurrent() {
        for (key in listOf("tracked", "indicators", "aliases", "location")) {
            assertThrows(ProtocolFailure::class.java) { decode(entity(key to JsonArray(emptyList()))) }
        }
        assertThrows(ProtocolFailure::class.java) { decode(entity("noExpiry" to JsonPrimitive("true"))) }
    }
    @Test fun depthAndFrameBudgetsRejectBeforeRecursiveParsing() {
        assertThrows(ProtocolFailure::class.java) { decode("[".repeat(33) + "]".repeat(33)) }
        assertThrows(ProtocolFailure::class.java) { decode(" ".repeat(SseReader.MAX_FRAME_BYTES + 1)) }
    }
}
