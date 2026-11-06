// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.lattice.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.DateTimeException

data class Track(
    val id: String,
    val name: String,
    val affiliation: String,
    val environment: String,
    val platform: String,
    val latitude: Double,
    val longitude: Double,
    val observed: Instant,
    val expires: Instant,
    val simulated: Boolean,
    val sourceUpdated: Instant = observed,
)

sealed interface EntityEvent {
    data class Upsert(val track: Track, val version: Instant) : EntityEvent
    data class Remove(val id: String, val version: Instant, val unsupported: Boolean = false) : EntityEvent
    data object Heartbeat : EntityEvent
}

object EntityDecoder {
    const val MAX_AGE_SECONDS = 120L
    private val dispositions = setOf("UNKNOWN", "FRIENDLY", "HOSTILE", "SUSPICIOUS", "ASSUMED_FRIENDLY", "NEUTRAL", "PENDING")
    private val environments = setOf("UNKNOWN", "AIR", "SURFACE", "SUB_SURFACE", "LAND", "SPACE")
    private val events = setOf("EVENT_TYPE_CREATED", "EVENT_TYPE_UPDATE", "EVENT_TYPE_PREEXISTING", "EVENT_TYPE_DELETED")

    fun decode(type: String, payload: String, now: Instant): EntityEvent {
        if (payload.toByteArray().size > SseReader.MAX_FRAME_BYTES) throw ProtocolFailure()
        checkDepth(payload)
        try {
            val root = Json.parseToJsonElement(payload) as? JsonObject ?: throw ProtocolFailure()
            if (type == "heartbeat") {
                root.optionalTime("timestamp")?.let {
                    if (it > now.plusSeconds(30) || it < now.minusSeconds(45)) throw ProtocolFailure()
                }
                return EntityEvent.Heartbeat
            }
            if (type != "entity") throw ProtocolFailure()
            val eventType = root.text("eventType")
            if (eventType !in events) throw ProtocolFailure()
            val version = root.time("time")
            if (version > now.plusSeconds(30)) throw ProtocolFailure()
            val entity = root.obj("entity")
            val id = entity.text("entityId", 256)
            if (eventType == "EVENT_TYPE_DELETED") return EntityEvent.Remove(id, version)
            if (!entity.bool("isLive")) return EntityEvent.Remove(id, version)
            val ontology = entity.obj("ontology")
            val template = ontology.text("template")
            if (template !in setOf("TEMPLATE_TRACK", "TEMPLATE_ASSET")) {
                if (template !in setOf("TEMPLATE_GEO", "TEMPLATE_SIGNAL_OF_INTEREST", "TEMPLATE_SENSOR_POINT_OF_INTEREST")) throw ProtocolFailure()
                return EntityEvent.Remove(id, version, unsupported = true)
            }
            val sourceTime = entity.obj("provenance").time("sourceUpdateTime")
            val location = entity.optionalObj("location")
            val kinematics = entity.optionalObj("kinematics")
            if (location == null && kinematics == null) return EntityEvent.Remove(id, version, unsupported = true)
            if (kinematics != null && "kinematicsGeocentric" in kinematics) return EntityEvent.Remove(id, version, unsupported = true)
            if ((location == null) == (kinematics == null)) throw ProtocolFailure()
            val geodetic = kinematics?.obj("kinematicsGeodetic")
            val position = location?.obj("position") ?: geodetic!!.obj("location")
            val latitude = position.number("latitudeDegrees")
            val longitude = position.number("longitudeDegrees")
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) throw ProtocolFailure()
            val observations = listOfNotNull(
                sourceTime,
                entity.optionalObj("tracked")?.optionalTime("lastMeasurementTime"),
                geodetic?.optionalTime("measurementTime"),
            )
            val measurement = observations.min()
            if (observations.any { it > now.plusSeconds(30) }) throw ProtocolFailure()
            val ageExpiry = measurement.plusSeconds(MAX_AGE_SECONDS)
            val noExpiry = entity.optionalBool("noExpiry")
            val expiry = entity.optionalTime("expiryTime") ?: if (noExpiry) ageExpiry else throw ProtocolFailure()
            if (ageExpiry <= now || expiry <= now) return EntityEvent.Remove(id, version)
            val mil = entity.obj("milView")
            val affiliation = mil.text("disposition").removePrefix("DISPOSITION_")
            val environment = mil.text("environment").removePrefix("ENVIRONMENT_")
            if (affiliation !in dispositions || environment !in environments ||
                mil.text("disposition") != "DISPOSITION_$affiliation" ||
                mil.text("environment") != "ENVIRONMENT_$environment") throw ProtocolFailure()
            val name = entity.optionalObj("aliases")?.optionalText("name", 128) ?: id
            return EntityEvent.Upsert(
                Track(id, name, affiliation, environment, ontology.text("platformType", 128), latitude, longitude,
                    measurement, minOf(expiry, ageExpiry),
                    entity.optionalObj("indicators")?.optionalBool("simulated") ?: false, sourceTime),
                version,
            )
        } catch (_: SerializationException) {
            throw ProtocolFailure()
        } catch (_: DateTimeException) {
            throw ProtocolFailure()
        }
    }

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject ?: throw ProtocolFailure()
    private fun JsonObject.optionalObj(key: String) = if (key in this) obj(key) else null
    private fun JsonObject.text(key: String, max: Int = 128): String {
        val primitive = this[key] as? JsonPrimitive ?: throw ProtocolFailure()
        val value = primitive.content
        if (!primitive.isString || value.isBlank() || value.length > max ||
            value.any { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) throw ProtocolFailure()
        return value
    }
    private fun JsonObject.optionalText(key: String, max: Int) = if (key in this) text(key, max) else null
    private fun JsonObject.time(key: String) = Instant.parse(text(key))
    private fun JsonObject.optionalTime(key: String) = if (key in this) time(key) else null
    private fun JsonObject.bool(key: String): Boolean {
        val value = this[key] as? JsonPrimitive ?: throw ProtocolFailure()
        if (value.isString) throw ProtocolFailure()
        return value.booleanOrNull ?: throw ProtocolFailure()
    }
    private fun JsonObject.optionalBool(key: String) = if (key in this) bool(key) else false
    private fun JsonObject.number(key: String): Double {
        val value = this[key] as? JsonPrimitive ?: throw ProtocolFailure()
        if (value.isString) throw ProtocolFailure()
        return value.doubleOrNull?.takeIf { it.isFinite() } ?: throw ProtocolFailure()
    }

    private fun checkDepth(json: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in json) {
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else {
                when (char) {
                    '"' -> quoted = true
                    '{', '[' -> if (++depth > 32) throw ProtocolFailure()
                    '}', ']' -> depth--
                }
            }
        }
    }
}
