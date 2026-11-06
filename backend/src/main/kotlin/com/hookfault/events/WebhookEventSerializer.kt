package com.hookfault.events

import com.hookfault.http.MAX_JSON_REQUEST_BYTES
import org.springframework.stereotype.Component
import tools.jackson.core.json.JsonWriteFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.cfg.DateTimeFeature
import java.util.TimeZone

@Component
class WebhookEventSerializer(objectMapper: ObjectMapper) {
    private val writer = objectMapper.writerFor(WebhookEventEnvelope::class.java)
        .without(SerializationFeature.INDENT_OUTPUT)
        .without(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
        .without(JsonWriteFeature.ESCAPE_NON_ASCII)
        .with(TimeZone.getTimeZone("UTC"))

    fun serialize(event: WebhookEventEnvelope): SerializedWebhookEvent {
        val bytes = writer.writeValueAsBytes(event)
        require(bytes.size <= MAX_JSON_REQUEST_BYTES) { "Webhook event must not exceed $MAX_JSON_REQUEST_BYTES bytes" }
        return SerializedWebhookEvent(event, bytes)
    }
}

class SerializedWebhookEvent internal constructor(val envelope: WebhookEventEnvelope, bytes: ByteArray) {
    private val body = bytes.copyOf()
    val size: Int get() = body.size

    // Signing and retries receive copies of the same bytes, never a regenerated event.
    fun bodyBytes(): ByteArray = body.copyOf()
}
