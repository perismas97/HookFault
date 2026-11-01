package com.hookfault.api

import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.http.HttpStatus

@Schema(description = "Problem details with a stable code. Rejected body and query values are not included.")
data class ApiErrorResponse(
    val type: String = "about:blank",
    val title: String,
    val status: Int,
    val detail: String,
    val instance: String,
    val code: String,
    val errors: List<ApiFieldError> = emptyList(),
)

data class ApiFieldError(val field: String, val message: String)

internal object ApiProblems {
    fun create(
        status: HttpStatus,
        code: String,
        detail: String,
        path: String,
        errors: List<ApiFieldError> = emptyList(),
    ): ApiErrorResponse = ApiErrorResponse(
        title = status.reasonPhrase, status = status.value(), detail = detail,
        instance = path, code = code, errors = errors,
    )
}
