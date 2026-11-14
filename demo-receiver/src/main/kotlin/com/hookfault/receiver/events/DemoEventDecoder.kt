package com.hookfault.receiver.events

import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

internal data class DemoUserCreated(val runId: UUID, val eventId: UUID, val userId: UUID)

@Component
internal class DemoEventDecoder(objectMapper: ObjectMapper) {
    private val reader = objectMapper.reader()
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)

    fun decode(body: ByteArray): DemoUserCreated? {
        val event = try {
            reader.readTree(body)
        } catch (_: JacksonException) {
            return null
        }
        if (!event.isObject || !event.path("schemaVersion").isInt || event.path("schemaVersion").asInt() != 1) return null
        if (event.text("type") != "demo.user.created") return null
        val occurredAt = event.text("occurredAt") ?: return null
        try {
            Instant.parse(occurredAt)
        } catch (_: DateTimeParseException) {
            return null
        }

        val runId = event.uuid("runId") ?: return null
        val eventId = event.uuid("id") ?: return null
        val payload = event.path("payload")
        val userId = payload.uuid("userId") ?: return null
        val name = payload.text("displayName") ?: return null
        if (name.isBlank() || name.length > 100) return null
        return DemoUserCreated(runId, eventId, userId)
    }

    private fun JsonNode.text(field: String): String? =
        path(field).takeIf { it.isString }?.asString()

    private fun JsonNode.uuid(field: String): UUID? {
        val value = text(field) ?: return null
        if (value.length != 36) return null
        return try {
            UUID.fromString(value).takeIf { it.toString().equals(value, ignoreCase = true) }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
