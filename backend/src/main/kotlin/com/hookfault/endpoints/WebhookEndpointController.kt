package com.hookfault.endpoints

import com.hookfault.api.ApiErrorResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/v1/webhook-endpoints", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Webhook endpoints", description = "Register and inspect local webhook receivers.")
@ApiResponses(
    ApiResponse(responseCode = "400", description = "Invalid input, ID, pagination, or disallowed destination.",
        content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ApiErrorResponse::class))]),
    ApiResponse(responseCode = "405", description = "Unsupported HTTP method.",
        content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ApiErrorResponse::class))]),
    ApiResponse(responseCode = "406", description = "Unsupported response format.",
        content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ApiErrorResponse::class))]),
    ApiResponse(responseCode = "413", description = "Request body exceeds 16,384 bytes.",
        content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ApiErrorResponse::class))]),
    ApiResponse(responseCode = "500", description = "Unexpected server error.",
        content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ApiErrorResponse::class))]),
)
class WebhookEndpointController(private val service: WebhookEndpointService) {
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Register a webhook endpoint", description = "Stores a local receiver URL without contacting it. JSON body limit: 16,384 bytes.")
    @ApiResponse(responseCode = "201", description = "Endpoint created.",
        headers = [Header(name = "Location", description = "Relative URL for the created endpoint.", schema = Schema(type = "string"))])
    @ApiResponse(responseCode = "415", description = "Content-Type must be application/json.",
        content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ApiErrorResponse::class))])
    fun register(@Valid @RequestBody request: RegisterWebhookEndpointRequest): ResponseEntity<WebhookEndpointResponse> {
        val endpoint = service.register(request.name, request.url)
        return ResponseEntity.created(URI("/api/v1/webhook-endpoints/${endpoint.id}")).body(endpoint.toResponse())
    }

    @GetMapping
    @Operation(summary = "List webhook endpoints", description = "Newest first, ordered by createdAt descending and then ID descending. Offset pagination is not a snapshot across concurrent registrations.")
    @ApiResponse(responseCode = "200", description = "A bounded page, including an empty items array when no endpoints match.")
    fun list(
        @Parameter(description = "Maximum endpoints per page.", schema = Schema(minimum = "1", maximum = "100", defaultValue = "20"))
        @RequestParam(name = "limit", defaultValue = "20") limit: Int,
        @Parameter(description = "Number of endpoints to skip.", schema = Schema(minimum = "0", defaultValue = "0"))
        @RequestParam(name = "offset", defaultValue = "0") offset: Int,
    ): WebhookEndpointListResponse = service.list(limit, offset)

    @GetMapping("/{id}")
    @Operation(summary = "Get a webhook endpoint")
    @ApiResponse(responseCode = "200", description = "Stored endpoint.")
    @ApiResponse(responseCode = "404", description = "Endpoint ID does not exist.",
        content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ApiErrorResponse::class))])
    fun get(
        @Parameter(description = "Canonical endpoint UUID.", schema = Schema(type = "string", format = "uuid"))
        @PathVariable("id") id: String,
    ): WebhookEndpointResponse {
        val parsed = try {
            UUID.fromString(id)
        } catch (_: IllegalArgumentException) {
            throw InvalidEndpointIdException()
        }
        if (!parsed.toString().equals(id, ignoreCase = true)) throw InvalidEndpointIdException()
        return service.get(parsed).toResponse()
    }
}
