package com.hookfault.events

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.annotation.JsonValue
import java.time.Instant
import java.util.UUID

internal const val WEBHOOK_EVENT_SCHEMA_VERSION = 1
internal const val DEMO_DISPLAY_NAME_MAX_LENGTH = 100

enum class WebhookEventType(@get:JsonValue val wireValue: String) {
    DEMO_USER_CREATED("demo.user.created"),
}

@JsonPropertyOrder("schemaVersion", "id", "type", "occurredAt", "runId", "payload")
data class WebhookEventEnvelope(
    @get:JsonProperty("schemaVersion") val schemaVersion: Int,
    @get:JsonProperty("id") val id: UUID,
    @get:JsonProperty("type") val type: WebhookEventType,
    @get:JsonProperty("occurredAt") val occurredAt: Instant,
    @get:JsonProperty("runId") val runId: UUID,
    @get:JsonProperty("payload") val payload: DemoUserCreatedPayload,
) {
    init {
        require(schemaVersion == WEBHOOK_EVENT_SCHEMA_VERSION) { "Unsupported webhook event schema version" }
    }
}

@JsonPropertyOrder("userId", "displayName")
data class DemoUserCreatedPayload(
    @get:JsonProperty("userId") val userId: UUID,
    @get:JsonProperty("displayName") val displayName: String,
) {
    init {
        require(displayName.isNotBlank() && displayName.length <= DEMO_DISPLAY_NAME_MAX_LENGTH) {
            "Demo display name must be non-blank and at most $DEMO_DISPLAY_NAME_MAX_LENGTH characters"
        }
    }
}
