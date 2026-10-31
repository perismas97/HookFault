package com.hookfault.endpoints

import com.hookfault.http.WebhookDestinationRejectedException
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/webhook-endpoints")
class WebhookEndpointController(private val service: WebhookEndpointService) {
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@Valid @RequestBody request: RegisterWebhookEndpointRequest): WebhookEndpointResponse {
        val endpoint = service.register(request.name, request.url)
        return WebhookEndpointResponse(
            id = endpoint.id,
            name = endpoint.name,
            url = endpoint.url,
            createdAt = endpoint.createdAt,
        )
    }

    @ExceptionHandler(MethodArgumentNotValidException::class, HttpMessageNotReadableException::class)
    fun invalidRegistration(): ResponseEntity<ProblemDetail> = ResponseEntity.badRequest().body(
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            "Provide a non-blank name of up to $ENDPOINT_NAME_MAX_LENGTH characters and an absolute " +
                "HTTP(S) URL of up to $ENDPOINT_URL_MAX_LENGTH characters, without credentials or a fragment.",
        ),
    )

    @ExceptionHandler(WebhookDestinationRejectedException::class)
    fun rejectedDestination(): ResponseEntity<ProblemDetail> = ResponseEntity.badRequest().body(
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Use a configured local webhook origin."),
    )
}
