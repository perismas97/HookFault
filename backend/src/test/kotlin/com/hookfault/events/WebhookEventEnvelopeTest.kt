package com.hookfault.events

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.util.UUID

class WebhookEventEnvelopeTest {
    @Test
    fun acceptsTheSupportedVersionAndExposesTheWireType() {
        assertThat(event().schemaVersion).isEqualTo(1)
        assertThat(event().type.wireValue).isEqualTo("demo.user.created")
    }

    @ParameterizedTest
    @ValueSource(ints = [-1, 0, 2, Int.MAX_VALUE])
    fun rejectsUnsupportedVersions(version: Int) {
        assertThatThrownBy { event().copy(schemaVersion = version) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Unsupported webhook event schema version")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "\t", "\r\n", "\u00a0"])
    fun rejectsBlankDemoNamesWithoutEchoingThem(name: String) {
        assertThatThrownBy { DemoUserCreatedPayload(UUID.randomUUID(), name) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Demo display name must be non-blank and at most 100 characters")
    }

    @Test
    fun acceptsTheNameLengthBoundary() {
        assertThat(DemoUserCreatedPayload(UUID.randomUUID(), "x".repeat(100)).displayName).hasSize(100)
    }

    @Test
    fun rejectsOverlongNamesWithoutEchoingTheirContents() {
        assertThatThrownBy { DemoUserCreatedPayload(UUID.randomUUID(), "do-not-expose".repeat(10)) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageNotContaining("do-not-expose")
    }

    private fun event(): WebhookEventEnvelope = WebhookEventEnvelope(
        1, UUID.randomUUID(), WebhookEventType.DEMO_USER_CREATED, Instant.parse("2026-11-06T10:00:00Z"),
        UUID.randomUUID(), DemoUserCreatedPayload(UUID.randomUUID(), "Demo user"),
    )
}
