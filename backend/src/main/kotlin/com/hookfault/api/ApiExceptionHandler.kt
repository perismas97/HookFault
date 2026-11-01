package com.hookfault.api

import com.hookfault.endpoints.ENDPOINT_NAME_MAX_LENGTH
import com.hookfault.endpoints.ENDPOINT_URL_MAX_LENGTH
import com.hookfault.endpoints.InvalidEndpointIdException
import com.hookfault.endpoints.InvalidEndpointPaginationException
import com.hookfault.endpoints.WebhookEndpointNotFoundException
import com.hookfault.http.WebhookDestinationRejectedException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.TypeMismatchException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(ApiExceptionHandler::class.java)

    @ExceptionHandler(
        WebhookDestinationRejectedException::class, WebhookEndpointNotFoundException::class,
        InvalidEndpointIdException::class, InvalidEndpointPaginationException::class,
    )
    fun domainError(ex: Exception, request: HttpServletRequest): ResponseEntity<ApiErrorResponse> {
        val problem = when (ex) {
            is WebhookDestinationRejectedException -> ApiProblems.create(
                HttpStatus.BAD_REQUEST, "DESTINATION_NOT_ALLOWED", "Use a configured local webhook origin.", request.requestURI,
            )
            is WebhookEndpointNotFoundException -> ApiProblems.create(
                HttpStatus.NOT_FOUND, "ENDPOINT_NOT_FOUND", "No webhook endpoint exists with this ID.", request.requestURI,
            )
            is InvalidEndpointIdException -> ApiProblems.create(
                HttpStatus.BAD_REQUEST, "INVALID_ENDPOINT_ID", "Use an endpoint ID in canonical UUID format.", request.requestURI,
            )
            else -> ApiProblems.create(
                HttpStatus.BAD_REQUEST, "INVALID_PAGINATION", "Use limit between 1 and 100 and a non-negative offset.", request.requestURI,
            )
        }
        return ResponseEntity.status(problem.status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem)
    }

    override fun handleExceptionInternal(
        ex: Exception, body: Any?, headers: HttpHeaders, statusCode: HttpStatusCode, request: WebRequest,
    ): ResponseEntity<Any>? {
        if ((request as ServletWebRequest).response?.isCommitted == true) return null
        val status = HttpStatus.valueOf(statusCode.value())
        val (code, detail) = when {
            ex is MethodArgumentNotValidException -> "VALIDATION_FAILED" to "One or more fields are invalid."
            ex is HttpMessageNotReadableException -> "INVALID_JSON" to "Provide a JSON object with string fields 'name' and 'url'."
            ex is TypeMismatchException -> "INVALID_PARAMETER" to "Use valid numeric limit and offset values."
            status.value() == 404 -> "ROUTE_NOT_FOUND" to "No API route matches this path."
            status.value() == 405 -> "METHOD_NOT_ALLOWED" to "This HTTP method is not supported for this route."
            status.value() == 406 -> "NOT_ACCEPTABLE" to "This route cannot produce the requested response format."
            status.value() == 415 -> "UNSUPPORTED_MEDIA_TYPE" to "Send requests with Content-Type application/json."
            status.is5xxServerError -> "INTERNAL_ERROR" to "An unexpected server error occurred."
            else -> "INVALID_REQUEST" to "The request could not be processed."
        }
        val errors = if (ex is MethodArgumentNotValidException) fieldErrors(ex) else emptyList()
        val problem = ApiProblems.create(status, code, detail, request.request.requestURI, errors)
        return ResponseEntity.status(statusCode).headers(headers).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem)
    }

    @ExceptionHandler(Exception::class)
    fun unexpectedError(ex: Exception, request: HttpServletRequest): ResponseEntity<ApiErrorResponse> {
        log.error("Unhandled API exception type: {}", ex.javaClass.name)
        return ResponseEntity.internalServerError().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(
            ApiProblems.create(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected server error occurred.", request.requestURI),
        )
    }

    private fun fieldErrors(ex: MethodArgumentNotValidException): List<ApiFieldError> =
        ex.bindingResult.fieldErrors.filter { it.field in setOf("name", "url") }.map { error ->
            val message = when (error.code) {
                "NotBlank" -> "must not be blank"
                "Size" -> "must be at most ${if (error.field == "name") ENDPOINT_NAME_MAX_LENGTH else ENDPOINT_URL_MAX_LENGTH} characters"
                "ValidWebhookUrl" -> "must be an absolute HTTP(S) URL without credentials or a fragment"
                else -> "is invalid"
            }
            ApiFieldError(error.field, message)
        }.distinct().sortedWith(compareBy(ApiFieldError::field, ApiFieldError::message))
}
