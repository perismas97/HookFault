package com.hookfault.endpoints

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

internal const val ENDPOINT_NAME_MAX_LENGTH = 100
internal const val ENDPOINT_URL_MAX_LENGTH = 2048
internal const val DEFAULT_ENDPOINT_LIMIT = 20
internal const val MAX_ENDPOINT_LIMIT = 100

class WebhookEndpointNotFoundException : RuntimeException("Webhook endpoint not found")
class InvalidEndpointIdException : IllegalArgumentException("Invalid endpoint ID")
class InvalidEndpointPaginationException : IllegalArgumentException("Invalid endpoint pagination")

data class RegisterWebhookEndpointRequest(
    @field:NotBlank
    @field:Size(max = ENDPOINT_NAME_MAX_LENGTH)
    @field:Schema(example = "Demo receiver")
    val name: String,
    @field:NotBlank
    @field:Size(max = ENDPOINT_URL_MAX_LENGTH)
    @field:ValidWebhookUrl
    @field:Schema(description = "Absolute URL on a configured local origin; no credentials or fragment.", example = "http://localhost:8090/webhooks")
    val url: String,
)

data class WebhookEndpoint(
    val id: UUID,
    val name: String,
    val url: String,
    val createdAt: Instant,
)

data class WebhookEndpointResponse(
    val id: UUID,
    val name: String,
    val url: String,
    val createdAt: Instant,
)

data class WebhookEndpointListResponse(
    val items: List<WebhookEndpointResponse>,
    val limit: Int,
    val offset: Int,
    val hasMore: Boolean,
)

internal fun WebhookEndpoint.toResponse(): WebhookEndpointResponse = WebhookEndpointResponse(id, name, url, createdAt)
