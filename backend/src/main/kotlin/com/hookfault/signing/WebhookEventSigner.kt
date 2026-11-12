package com.hookfault.signing

import com.hookfault.events.SerializedWebhookEvent
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets.US_ASCII
import java.time.Instant
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val HMAC_SHA_256 = "HmacSHA256"
private const val MIN_SIGNING_SECRET_BYTES = 32

@Component
class WebhookEventSigner(private val demoSigningKey: DemoSigningKey) {
    fun sign(event: SerializedWebhookEvent, sentAt: Instant): SignedWebhookEvent =
        sign(event, demoSigningKey.requireBytes(), sentAt)

    fun sign(event: SerializedWebhookEvent, secret: ByteArray, sentAt: Instant): SignedWebhookEvent {
        require(secret.size >= MIN_SIGNING_SECRET_BYTES) { "Signing secret must contain at least 32 bytes" }
        val timestamp = sentAt.epochSecond
        require(timestamp >= 0) { "Signing timestamp must not precede the Unix epoch" }

        val prefix = "$timestamp.".toByteArray(US_ASCII)
        val digest = hmacSha256(secret, prefix, event.bodyBytes())
        return SignedWebhookEvent(event, timestamp, "v1=${HexFormat.of().formatHex(digest)}")
    }
}

class SignedWebhookEvent internal constructor(
    val event: SerializedWebhookEvent,
    val timestamp: Long,
    val signature: String,
) {
    fun headers(): Map<String, String> = mapOf(
        "X-HookFault-Timestamp" to timestamp.toString(),
        "X-HookFault-Signature" to signature,
    )
}

internal fun hmacSha256(secret: ByteArray, vararg parts: ByteArray): ByteArray {
    val mac = Mac.getInstance(HMAC_SHA_256)
    mac.init(SecretKeySpec(secret, HMAC_SHA_256))
    parts.forEach { mac.update(it) }
    return mac.doFinal()
}
