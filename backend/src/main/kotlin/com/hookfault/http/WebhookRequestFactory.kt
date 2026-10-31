package com.hookfault.http

import org.springframework.stereotype.Component
import java.net.http.HttpRequest

@Component
class WebhookRequestFactory(private val destinations: WebhookDestinationPolicy) {
    fun create(url: String, body: ByteArray): HttpRequest {
        val uri = destinations.requireAllowed(url)
        require(body.size <= MAX_JSON_REQUEST_BYTES) { "Webhook payload must not exceed $MAX_JSON_REQUEST_BYTES bytes" }
        return HttpRequest.newBuilder(uri)
            .timeout(WEBHOOK_REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.copyOf()))
            .build()
    }
}
