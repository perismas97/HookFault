package com.hookfault.receiver.signing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.env.MockEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class WebhookSignatureVerifierTest {
    @TempDir
    lateinit var directory: Path

    private val sentAt = Instant.ofEpochSecond(1793959205)
    private val timestamp = sentAt.epochSecond.toString()
    private val signature = "v1=260ea19d057ce582ee6aca7922e7daf57ff91ecf4446ebc7bf568cda03ae8cb6"
    // Same independent UTF-8 fixture as the backend signer test.
    private val body = (
        """{"schemaVersion":1,"id":"00000000-0000-4000-8000-000000000001","type":"demo.user.created",""" +
            """"occurredAt":"2026-11-06T10:00:00Z","runId":"00000000-0000-4000-8000-000000000002",""" +
            """"payload":{"userId":"00000000-0000-4000-8000-000000000003","displayName":"Demo """ +
            "\u03b1" + "\"}}"
        ).toByteArray(Charsets.UTF_8)

    @ParameterizedTest
    @ValueSource(longs = [-300, 0, 300])
    fun acceptsTheKnownSignatureWithinTheInclusiveWindow(secondsSinceSend: Long) {
        val result = verifier().verify(timestamp, signature, body, sentAt.plusSeconds(secondsSinceSend))
        assertThat(result).isEqualTo(WebhookVerification.VERIFIED)
    }

    @ParameterizedTest
    @ValueSource(longs = [-301, 301])
    fun rejectsValidSignaturesOutsideTheWindow(secondsSinceSend: Long) {
        val result = verifier().verify(timestamp, signature, body, sentAt.plusSeconds(secondsSinceSend))
        assertThat(result).isEqualTo(WebhookVerification.REJECTED)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "01793959205", "+1793959205", "-1", "1793959205.0", "9223372036854775808", "9223372036854775807", "1793959205,1793959205"])
    fun rejectsMalformedOrExtremeTimestamps(value: String) {
        assertThat(verifier().verify(value, signature, body, sentAt)).isEqualTo(WebhookVerification.REJECTED)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "v1=", "v2=abcd", "v1=not-hex"])
    fun rejectsMalformedSignatures(value: String) {
        assertThat(verifier().verify(timestamp, value, body, sentAt)).isEqualTo(WebhookVerification.REJECTED)
    }

    @Test
    fun rejectsMissingHeaders() {
        val verifier = verifier()
        assertThat(verifier.verify(null, signature, body, sentAt)).isEqualTo(WebhookVerification.REJECTED)
        assertThat(verifier.verify(timestamp, null, body, sentAt)).isEqualTo(WebhookVerification.REJECTED)
    }

    @Test
    fun rejectsAChangedBodyOrTimestampWithTheOriginalSignature() {
        val verifier = verifier()
        assertThat(verifier.verify(timestamp, signature, body + '\n'.code.toByte(), sentAt))
            .isEqualTo(WebhookVerification.REJECTED)
        assertThat(verifier.verify((sentAt.epochSecond + 1).toString(), signature, body, sentAt))
            .isEqualTo(WebhookVerification.REJECTED)
    }

    @Test
    fun rejectsAWellFormedSignatureFromADifferentKey() {
        assertThat(verifier(ByteArray(32) { 42 }).verify(timestamp, signature, body, sentAt))
            .isEqualTo(WebhookVerification.REJECTED)
    }

    @Test
    fun failsClosedWithoutAConfiguredKey() {
        val verifier = WebhookSignatureVerifier(DemoSigningKey(MockEnvironment()))
        assertThat(verifier.verify(timestamp, signature, body, sentAt)).isEqualTo(WebhookVerification.KEY_UNAVAILABLE)
    }

    private fun verifier(key: ByteArray = ByteArray(32) { it.toByte() }): WebhookSignatureVerifier {
        val file = Files.write(directory.resolve("test-key"), key)
        return WebhookSignatureVerifier(DemoSigningKey(MockEnvironment()
            .withProperty("HOOKFAULT_DEMO_SIGNING_KEY_FILE", file.toString())))
    }
}
