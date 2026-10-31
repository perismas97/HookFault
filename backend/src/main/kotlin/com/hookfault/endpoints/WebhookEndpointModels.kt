package com.hookfault.endpoints

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

internal const val ENDPOINT_NAME_MAX_LENGTH = 100
internal const val ENDPOINT_URL_MAX_LENGTH = 2048

data class RegisterWebhookEndpointRequest(
    @field:NotBlank
    @field:Size(max = ENDPOINT_NAME_MAX_LENGTH)
    val name: String,
    @field:NotBlank
    @field:Size(max = ENDPOINT_URL_MAX_LENGTH)
    @field:ValidWebhookUrl
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
