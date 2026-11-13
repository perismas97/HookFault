package com.hookfault.receiver

import com.hookfault.receiver.signing.WebhookSignatureVerifier
import com.hookfault.receiver.signing.WebhookVerification
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

internal const val MAX_WEBHOOK_BODY_BYTES = 16 * 1024

@RestController
class DemoWebhookController(private val verifier: WebhookSignatureVerifier) {
    @PostMapping("/webhooks", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun receive(request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        if (request.contentLengthLong > MAX_WEBHOOK_BODY_BYTES) {
            return tooLarge()
        }

        // Read raw bytes with a cap even when the sender omits Content-Length.
        val body = request.inputStream.readNBytes(MAX_WEBHOOK_BODY_BYTES + 1)
        if (body.size > MAX_WEBHOOK_BODY_BYTES) {
            return tooLarge()
        }

        return when (verifier.verify(
            request.singleHeader("X-HookFault-Timestamp"),
            request.singleHeader("X-HookFault-Signature"),
            body,
        )) {
            WebhookVerification.VERIFIED -> ResponseEntity.noContent().build()
            WebhookVerification.REJECTED -> problem(HttpStatus.UNAUTHORIZED, "Webhook authentication failed.")
            WebhookVerification.KEY_UNAVAILABLE ->
                problem(HttpStatus.SERVICE_UNAVAILABLE, "Webhook verification is unavailable.")
        }
    }

    private fun HttpServletRequest.singleHeader(name: String): String? =
        getHeaders(name)?.toList()?.singleOrNull()

    private fun tooLarge(): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.valueOf(413), "Webhook body must not exceed $MAX_WEBHOOK_BODY_BYTES bytes.")

    private fun problem(status: HttpStatus, detail: String): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(status)
            .headers { headers ->
                if (status == HttpStatus.UNAUTHORIZED) {
                    headers.set("WWW-Authenticate", "HookFault-HMAC")
                }
            }
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(ProblemDetail.forStatusAndDetail(status, detail))
}
