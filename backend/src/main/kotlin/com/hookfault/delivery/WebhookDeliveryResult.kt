package com.hookfault.delivery

sealed interface WebhookDeliveryResult {
    class Response(val statusCode: Int, body: ByteArray) : WebhookDeliveryResult {
        private val body = body.copyOf()

        fun bodyBytes(): ByteArray = body.copyOf()
    }

    data class Failure(val reason: WebhookDeliveryFailure, val statusCode: Int? = null) : WebhookDeliveryResult
}

enum class WebhookDeliveryFailure {
    TIMEOUT,
    RESPONSE_TOO_LARGE,
    NETWORK_ERROR,
}
