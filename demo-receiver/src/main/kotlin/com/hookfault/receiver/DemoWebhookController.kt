package com.hookfault.receiver

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

internal const val MAX_WEBHOOK_BODY_BYTES = 16 * 1024

@RestController
class DemoWebhookController {
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

        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Webhook verification is not enabled.")
    }

    private fun tooLarge(): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.valueOf(413), "Webhook body must not exceed $MAX_WEBHOOK_BODY_BYTES bytes.")

    private fun problem(status: HttpStatus, detail: String): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(ProblemDetail.forStatusAndDetail(status, detail))
}
