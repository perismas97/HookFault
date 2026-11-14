package com.hookfault.receiver.events

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

class DemoEventDecoderTest {
    private val decoder = DemoEventDecoder(JsonMapper.builder().build())
    private val event = """
        {
          "schemaVersion": 1,
          "id": "00000000-0000-4000-8000-000000000001",
          "type": "demo.user.created",
          "occurredAt": "2026-11-06T10:00:00.123456789Z",
          "runId": "00000000-0000-4000-8000-000000000002",
          "payload": {
            "userId": "00000000-0000-4000-8000-000000000003",
            "displayName": "Demo \u03b1"
          }
        }
    """.trimIndent()

    @Test
    fun readsTheBackendEnvelopeAndKeepsOnlyTheIdentifiersNeededForProcessing() {
        assertThat(decoder.decode(event.toByteArray())).isEqualTo(
            DemoUserCreated(
                UUID.fromString("00000000-0000-4000-8000-000000000002"),
                UUID.fromString("00000000-0000-4000-8000-000000000001"),
                UUID.fromString("00000000-0000-4000-8000-000000000003"),
            ),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "{", "{}", "[]", "null"])
    fun rejectsInvalidOrIncompleteDocuments(body: String) {
        assertThat(decoder.decode(body.toByteArray())).isNull()
    }

    @Test
    fun rejectsUnsupportedOrAmbiguousEnvelopes() {
        val invalid = listOf(
            event.replace("\"schemaVersion\": 1", "\"schemaVersion\": \"1\""),
            event.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
            event.replace("demo.user.created", "demo.unknown"),
            event.replace("2026-11-06T10:00:00.123456789Z", "not-a-time"),
            event.replace("00000000-0000-4000-8000-000000000001", "1-1-1-1-1"),
            event.replace("""Demo \u03b1""", "  "),
            event.replace("""Demo \u03b1""", "x".repeat(101)),
            event.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2, \"schemaVersion\": 1"),
            "$event {}",
        )
        for (body in invalid) assertThat(decoder.decode(body.toByteArray())).isNull()
    }
}
