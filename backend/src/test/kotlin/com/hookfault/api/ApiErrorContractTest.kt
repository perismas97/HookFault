package com.hookfault.api

import com.hookfault.endpoints.WebhookEndpointController
import com.hookfault.endpoints.WebhookEndpointRepository
import com.hookfault.endpoints.WebhookEndpointService
import com.hookfault.http.ApiRequestBodyLimitFilter
import com.hookfault.http.WebhookDestinationPolicy
import com.hookfault.http.WebhookDestinationProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.util.UUID

class ApiErrorContractTest {
    private val path = "/api/v1/webhook-endpoints"
    private val repository = Mockito.mock(WebhookEndpointRepository::class.java)
    private val service = WebhookEndpointService(repository,
        WebhookDestinationPolicy(WebhookDestinationProperties(listOf("http://localhost:8090"))))
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val validator = LocalValidatorFactoryBean().apply { afterPropertiesSet() }
    private val mvc = MockMvcBuilders.standaloneSetup(WebhookEndpointController(service))
        .setControllerAdvice(ApiExceptionHandler()).setValidator(validator)
        .setMessageConverters(JacksonJsonHttpMessageConverter(mapper))
        .addFilters<StandaloneMockMvcBuilder>(ApiRequestBodyLimitFilter(mapper)).build()

    @AfterEach
    fun closeValidator() = validator.close()

    @Test
    @ExtendWith(OutputCaptureExtension::class)
    fun returnsSafeFieldErrorsWithoutRejectedValues(output: CapturedOutput) {
        val body = mapper.writeValueAsString(mapOf("name" to "", "url" to "http://user:do-not-expose@localhost:8090/webhooks"))
        val result = mvc.perform(post(path).contentType("application/json").content(body)).andReturn()
        val problem = assertProblem(result, 400, "VALIDATION_FAILED", path)
        assertThat(problem.path("errors").values().map { it.path("field").asString() }).containsExactly("name", "url")
        assertThat(result.response.contentAsString).doesNotContain("do-not-expose", "rejectedValue", "user:")
        assertThat(output.all).doesNotContain("do-not-expose")
        Mockito.verifyNoInteractions(repository)
    }

    @ParameterizedTest
    @ValueSource(strings = ["{", "{}", "null", "[]", "{\"name\":{},\"url\":[]}"])
    fun standardizesMalformedAndMissingJson(body: String) {
        assertProblem(mvc.perform(post(path).contentType("application/json").content(body)).andReturn(),
            400, "INVALID_JSON", path)
        Mockito.verifyNoInteractions(repository)
    }

    @Test
    fun standardizesDisallowedDestinationsWithoutEchoingTheQuery() {
        val body = mapper.writeValueAsString(mapOf("name" to "Receiver", "url" to "http://example.com/webhooks?token=do-not-expose"))
        val result = mvc.perform(post(path).contentType("application/json").content(body)).andReturn()
        assertProblem(result, 400, "DESTINATION_NOT_ALLOWED", path)
        assertThat(result.response.contentAsString).doesNotContain("example.com", "do-not-expose")
        Mockito.verifyNoInteractions(repository)
    }

    @ParameterizedTest
    @ValueSource(strings = ["not-a-uuid", "1-1-1-1-1", "00000000000000000000000000000000"])
    fun rejectsNonCanonicalIdsWithoutQueryingTheDatabase(id: String) {
        assertProblem(mvc.perform(get("$path/$id")).andReturn(), 400, "INVALID_ENDPOINT_ID", "$path/$id")
        Mockito.verifyNoInteractions(repository)
    }

    @Test
    fun distinguishesAnUnknownEndpointFromAnUnknownRoute() {
        val id = UUID.randomUUID()
        assertProblem(mvc.perform(get("$path/$id")).andReturn(), 404, "ENDPOINT_NOT_FOUND", "$path/$id")
        assertProblem(mvc.perform(get("/api/v1/no-such-route")).andReturn(), 404, "ROUTE_NOT_FOUND", "/api/v1/no-such-route")
    }

    @ParameterizedTest
    @ValueSource(strings = ["limit=0", "limit=-1", "limit=101", "offset=-1"])
    fun rejectsOutOfRangePagination(query: String) {
        assertProblem(mvc.perform(get("$path?$query")).andReturn(), 400, "INVALID_PAGINATION", path)
        Mockito.verifyNoInteractions(repository)
    }

    @ParameterizedTest
    @ValueSource(strings = ["limit=abc", "offset=abc", "limit=2147483648", "offset=2147483648"])
    fun standardizesParameterConversionErrors(query: String) {
        assertProblem(mvc.perform(get("$path?$query")).andReturn(), 400, "INVALID_PARAMETER", path)
        Mockito.verifyNoInteractions(repository)
    }

    @Test
    fun preservesTheAllowHeaderOnMethodErrors() {
        val result = mvc.perform(delete(path)).andReturn()
        assertProblem(result, 405, "METHOD_NOT_ALLOWED", path)
        assertThat(result.response.getHeader("Allow")).contains("GET", "POST")
    }

    @Test
    fun standardizesUnsupportedContentTypes() {
        assertProblem(mvc.perform(post(path).contentType("text/plain").content("do-not-expose")).andReturn(),
            415, "UNSUPPORTED_MEDIA_TYPE", path)
    }

    @Test
    fun standardizesUnsupportedResponseFormats() {
        assertProblem(mvc.perform(get(path).accept("application/xml")).andReturn(), 406, "NOT_ACCEPTABLE", path)
    }

    @Test
    fun standardizesFilterErrorsAndOmitsQueryStrings() {
        val result = mvc.perform(post("$path?token=do-not-expose").contentType("application/json")
            .content("x".repeat(16 * 1024 + 1))).andReturn()
        assertProblem(result, 413, "REQUEST_TOO_LARGE", path)
        assertThat(result.response.contentAsString).doesNotContain("do-not-expose")
        Mockito.verifyNoInteractions(repository)
    }

    @Test
    @ExtendWith(OutputCaptureExtension::class)
    fun hidesUnexpectedExceptionAndSqlDetails(output: CapturedOutput) {
        val id = UUID.randomUUID()
        Mockito.`when`(repository.findById(id)).thenThrow(IllegalStateException("select do-not-expose from secret_table"))
        val result = mvc.perform(get("$path/$id")).andReturn()
        assertProblem(result, 500, "INTERNAL_ERROR", "$path/$id")
        assertThat(result.response.contentAsString).doesNotContain("do-not-expose", "secret_table", "IllegalStateException", "stackTrace")
        assertThat(output.all).doesNotContain("do-not-expose", "secret_table")
    }

    private fun assertProblem(result: MvcResult, status: Int, code: String, instance: String): JsonNode {
        assertThat(result.response.status).isEqualTo(status)
        assertThat(result.response.contentType).startsWith("application/problem+json")
        val problem = mapper.readTree(result.response.contentAsString)
        assertThat(problem.size()).isEqualTo(7)
        assertThat(problem.path("type").asString()).isEqualTo("about:blank")
        assertThat(problem.path("title").asString()).isNotBlank()
        assertThat(problem.path("status").asInt()).isEqualTo(status)
        assertThat(problem.path("detail").asString()).isNotBlank()
        assertThat(problem.path("code").asString()).isEqualTo(code)
        assertThat(problem.path("instance").asString()).isEqualTo(instance)
        assertThat(problem.path("errors").isArray).isTrue()
        return problem
    }
}
