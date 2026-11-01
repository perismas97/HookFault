package com.hookfault.endpoints

import com.hookfault.api.ApiExceptionHandler
import com.hookfault.http.ApiRequestBodyLimitFilter
import com.hookfault.http.WebhookDestinationRejectedException
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Instant
import java.util.UUID

class WebhookEndpointControllerTest {
    private val service = Mockito.mock(WebhookEndpointService::class.java)
    private val validator = LocalValidatorFactoryBean().apply { afterPropertiesSet() }
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val mvc = MockMvcBuilders.standaloneSetup(WebhookEndpointController(service))
        .setValidator(validator)
        .setMessageConverters(JacksonJsonHttpMessageConverter(mapper))
        .setControllerAdvice(ApiExceptionHandler())
        .addFilters<StandaloneMockMvcBuilder>(ApiRequestBodyLimitFilter(mapper))
        .build()

    @AfterEach
    fun closeValidator() {
        validator.close()
    }

    @Test
    fun returnsCreatedWithExplicitPublicFieldsAndIsoTimestamp() {
        val endpoint = WebhookEndpoint(
            UUID.randomUUID(),
            "Demo receiver",
            "http://localhost:8090/webhooks",
            Instant.parse("2026-10-31T12:00:00.123456Z"),
        )
        Mockito.`when`(service.register(endpoint.name, endpoint.url)).thenReturn(endpoint)

        val result = mvc.perform(
            post("/api/v1/webhook-endpoints")
                .contentType("application/json")
                .accept("application/json")
                .content(mapper.writeValueAsString(RegisterWebhookEndpointRequest(endpoint.name, endpoint.url))),
        ).andExpect(status().isCreated).andReturn()

        val body = mapper.readTree(result.response.contentAsString)
        assertThat(result.response.getHeader("Location")).isEqualTo("/api/v1/webhook-endpoints/${endpoint.id}")
        assertThat(body.size()).isEqualTo(4)
        assertThat(body.path("id").asString()).isEqualTo(endpoint.id.toString())
        assertThat(body.path("name").asString()).isEqualTo(endpoint.name)
        assertThat(body.path("url").asString()).isEqualTo(endpoint.url)
        assertThat(body.path("createdAt").asString()).isEqualTo(endpoint.createdAt.toString())
        Mockito.verify(service).register(endpoint.name, endpoint.url)
    }

    @Test
    fun returnsTheStoredEndpointForACanonicalId() {
        val endpoint = WebhookEndpoint(UUID.randomUUID(), "Receiver", "http://localhost:8090/webhooks", Instant.now())
        Mockito.`when`(service.get(endpoint.id)).thenReturn(endpoint)
        val result = mvc.perform(get("/api/v1/webhook-endpoints/${endpoint.id.toString().uppercase()}"))
            .andExpect(status().isOk).andReturn()
        val json = mapper.readTree(result.response.contentAsString)
        assertThat(json.size()).isEqualTo(4)
        assertThat(json.path("id").asString()).isEqualTo(endpoint.id.toString())
        assertThat(json.path("createdAt").asString()).isEqualTo(endpoint.createdAt.toString())
        Mockito.verify(service).get(endpoint.id)
    }

    @Test
    fun returnsTheDefaultListPageWithOnlyExplicitPublicFields() {
        val endpoint = WebhookEndpoint(UUID.randomUUID(), "Receiver", "http://localhost:8090/webhooks", Instant.now())
        Mockito.`when`(service.list(20, 0)).thenReturn(WebhookEndpointListResponse(listOf(endpoint.toResponse()), 20, 0, false))
        val result = mvc.perform(get("/api/v1/webhook-endpoints")).andExpect(status().isOk).andReturn()
        val json = mapper.readTree(result.response.contentAsString)
        assertThat(json.size()).isEqualTo(4)
        assertThat(json.path("items").first().size()).isEqualTo(4)
        assertThat(json.path("items").first().path("id").asString()).isEqualTo(endpoint.id.toString())
        assertThat(json.path("limit").asInt()).isEqualTo(20)
        assertThat(json.path("hasMore").asBoolean()).isFalse()
        Mockito.verify(service).list(20, 0)
    }

    @Test
    fun forwardsExplicitPaginationParameters() {
        Mockito.`when`(service.list(100, 5)).thenReturn(WebhookEndpointListResponse(emptyList(), 100, 5, false))
        mvc.perform(get("/api/v1/webhook-endpoints?limit=100&offset=5")).andExpect(status().isOk)
        Mockito.verify(service).list(100, 5)
    }

    @Test
    fun reportsRejectedDestinationsWithoutEchoingTheUrl() {
        val url = "http://example.com/webhooks?token=do-not-expose"
        Mockito.`when`(service.register("Demo receiver", url)).thenThrow(WebhookDestinationRejectedException())
        val result = mvc.perform(
            post("/api/v1/webhook-endpoints").contentType("application/json")
                .content(mapper.writeValueAsString(RegisterWebhookEndpointRequest("Demo receiver", url))),
        ).andExpect(status().isBadRequest).andReturn()
        assertThat(result.response.contentAsString).contains("configured local webhook origin")
            .doesNotContain("example.com", "do-not-expose")
    }

    @Test
    fun rejectsOversizedBodiesBeforeCallingTheService() {
        val result = mvc.perform(
            post("/api/v1/webhook-endpoints").contentType("application/json")
                .content(" ".repeat(16 * 1024 + 1)),
        ).andExpect(status().`is`(413)).andReturn()
        assertThat(result.response.contentAsString).contains("16384")
        Mockito.verifyNoInteractions(service)
    }

    @ParameterizedTest
    @ExtendWith(OutputCaptureExtension::class)
    @ValueSource(
        strings = [
            "{\"name\":\"\",\"url\":\"http://localhost:8090/webhooks\"}",
            "{\"name\":\"Demo receiver\",\"url\":\"https://user:do-not-expose@example.com/webhooks\"}",
            "{\"name\":\"Demo receiver\"}",
            "{",
            "{\"name\":\"Demo receiver\",\"url\":\"ftp://example.com/webhooks\"}",
        ],
    )
    fun rejectsInvalidRequestsBeforeCallingService(body: String, output: CapturedOutput) {
        val result = mvc.perform(
            post("/api/v1/webhook-endpoints")
                .contentType("application/json")
                .accept("application/json")
                .content(body),
        ).andExpect(status().isBadRequest).andReturn()

        assertThat(result.response.contentAsString).doesNotContain("do-not-expose")
        assertThat(output.all).doesNotContain("do-not-expose")
        Mockito.verifyNoInteractions(service)
    }
}
