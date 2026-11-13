package com.hookfault.receiver.signing

import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets.US_ASCII
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val HMAC_SHA256 = "HmacSHA256"
private const val TIMESTAMP_TOLERANCE_SECONDS = 300L
private val timestampFormat = Regex("0|[1-9][0-9]{0,18}")
private val signatureFormat = Regex("v1=[0-9a-f]{64}")

enum class WebhookVerification {
    VERIFIED,
    REJECTED,
    KEY_UNAVAILABLE,
}

@Component
class WebhookSignatureVerifier(private val signingKey: DemoSigningKey) {
    fun verify(
        timestamp: String?,
        signature: String?,
        body: ByteArray,
        now: Instant = Instant.now(),
    ): WebhookVerification {
        val secret = signingKey.bytesOrNull() ?: return WebhookVerification.KEY_UNAVAILABLE
        try {
            if (timestamp == null || !timestampFormat.matches(timestamp)) return WebhookVerification.REJECTED
            val sentAt = timestamp.toLongOrNull() ?: return WebhookVerification.REJECTED
            val current = now.epochSecond
            if (sentAt !in (current - TIMESTAMP_TOLERANCE_SECONDS)..(current + TIMESTAMP_TOLERANCE_SECONDS)) {
                return WebhookVerification.REJECTED
            }
            if (signature == null || !signatureFormat.matches(signature)) return WebhookVerification.REJECTED

            val mac = Mac.getInstance(HMAC_SHA256)
            mac.init(SecretKeySpec(secret, HMAC_SHA256))
            mac.update("$timestamp.".toByteArray(US_ASCII))
            val expected = mac.doFinal(body)
            val supplied = HexFormat.of().parseHex(signature, 3, signature.length)
            return if (MessageDigest.isEqual(expected, supplied)) {
                WebhookVerification.VERIFIED
            } else {
                WebhookVerification.REJECTED
            }
        } finally {
            secret.fill(0)
        }
    }
}
