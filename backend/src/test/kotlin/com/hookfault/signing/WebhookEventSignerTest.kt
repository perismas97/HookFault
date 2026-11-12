package com.hookfault.signing

import com.hookfault.events.DemoUserCreatedPayload
import com.hookfault.events.SerializedWebhookEvent
import com.hookfault.events.WebhookEventEnvelope
import com.hookfault.events.WebhookEventSerializer
import com.hookfault.events.WebhookEventType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.env.MockEnvironment
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.charset.StandardCharsets.US_ASCII
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

class WebhookEventSignerTest {
    private val signer = WebhookEventSigner(DemoSigningKey(MockEnvironment()))
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val serializer = WebhookEventSerializer(mapper)
    private val testKey = ByteArray(32) { it.toByte() }
    private val sentAt = Instant.parse("2026-11-06T10:00:05Z")
    private val envelope = WebhookEventEnvelope(
        schemaVersion = 1,
        id = UUID.fromString("00000000-0000-4000-8000-000000000001"),
        type = WebhookEventType.DEMO_USER_CREATED,
        occurredAt = Instant.parse("2026-11-06T10:00:00Z"),
        runId = UUID.fromString("00000000-0000-4000-8000-000000000002"),
        payload = DemoUserCreatedPayload(UUID.fromString("00000000-0000-4000-8000-000000000003"), "Demo \u03b1"),
    )
    private val event = serializer.serialize(envelope)

    @Test
    fun signsUsingTheConfiguredDemoKey(@TempDir directory: Path) {
        val file = Files.write(directory.resolve("demo-signing-key"), testKey)
        val environment = MockEnvironment().withProperty("HOOKFAULT_DEMO_SIGNING_KEY_FILE", file.toString())
        val configuredSigner = WebhookEventSigner(DemoSigningKey(environment))

        assertThat(configuredSigner.sign(event, sentAt).headers())
            .isEqualTo(signer.sign(event, testKey, sentAt).headers())
    }

    @Test
    fun refusesConfiguredSigningWithoutAKey() {
        assertThatThrownBy { signer.sign(event, sentAt) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Demo signing key is not configured.")
    }

    @Test
    fun matchesRfc4231TestCaseOne() {
        val digest = hmacSha256(ByteArray(20) { 0x0b }, "Hi ".toByteArray(US_ASCII), "There".toByteArray(US_ASCII))
        assertThat(HexFormat.of().formatHex(digest))
            .isEqualTo("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7")
    }

    @Test
    fun matchesIndependentUtf8WebhookVectorAndProducesExactHeaders() {
        val signed = signer.sign(event, testKey, sentAt)

        // This fixture was computed independently with .NET HMACSHA256.
        assertThat(signed.headers()).containsExactlyEntriesOf(mapOf(
            "X-HookFault-Timestamp" to "1793959205",
            "X-HookFault-Signature" to "v1=260ea19d057ce582ee6aca7922e7daf57ff91ecf4446ebc7bf568cda03ae8cb6",
        ))
        assertThat(signed.event).isSameAs(event)
    }

    @Test
    fun signsTheSameEventDeterministicallyWithinTheSameTimestampSecond() {
        val first = signer.sign(event, testKey, sentAt)
        val second = signer.sign(event, testKey, sentAt.plusNanos(999_999_999))
        assertThat(second.headers()).isEqualTo(first.headers())
    }

    @Test
    fun changingOnlyThePayloadChangesTheSignature() {
        val changed = serializer.serialize(envelope.copy(payload = envelope.payload.copy(displayName = "Demo \u03b2")))
        assertThat(signer.sign(changed, testKey, sentAt).signature)
            .isNotEqualTo(signer.sign(event, testKey, sentAt).signature)
    }

    @Test
    fun signsRawBytesEvenWhenDifferentWhitespaceDoesNotChangeTheJson() {
        val original = event.bodyBytes()
        val padded = original + '\n'.code.toByte()
        val changed = SerializedWebhookEvent(envelope, padded)
        assertThat(mapper.readTree(padded)).isEqualTo(mapper.readTree(original))
        assertThat(signer.sign(changed, testKey, sentAt).signature)
            .isNotEqualTo(signer.sign(event, testKey, sentAt).signature)
    }

    @Test
    fun aNewSendTimestampChangesTheSignatureButNotTheEvent() {
        val first = signer.sign(event, testKey, sentAt)
        val retry = signer.sign(event, testKey, sentAt.plusSeconds(1))
        assertThat(retry.timestamp).isEqualTo(first.timestamp + 1)
        assertThat(retry.signature).isNotEqualTo(first.signature)
        assertThat(retry.event.envelope.id).isEqualTo(first.event.envelope.id)
        assertThat(retry.event.bodyBytes()).isEqualTo(first.event.bodyBytes())
    }

    @Test
    fun differentSecretsProduceDifferentSignaturesWithoutChangingTheInputs() {
        val originalBody = event.bodyBytes()
        val originalKey = testKey.copyOf()
        val differentKey = testKey.copyOf().apply { this[0] = 42 }
        val original = signer.sign(event, testKey, sentAt)
        assertThat(signer.sign(event, differentKey, sentAt).signature).isNotEqualTo(original.signature)
        assertThat(signer.sign(event, testKey, sentAt).signature).isEqualTo(original.signature)
        assertThat(testKey).isEqualTo(originalKey)
        assertThat(event.bodyBytes()).isEqualTo(originalBody)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 31])
    fun rejectsSecretsShorterThanThirtyTwoBytes(size: Int) {
        assertThatThrownBy { signer.sign(event, ByteArray(size), sentAt) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Signing secret must contain at least 32 bytes")
    }

    @Test
    fun rejectsPreEpochSendTimestamps() {
        assertThatThrownBy { signer.sign(event, testKey, Instant.EPOCH.minusNanos(1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Signing timestamp must not precede the Unix epoch")
    }
}
