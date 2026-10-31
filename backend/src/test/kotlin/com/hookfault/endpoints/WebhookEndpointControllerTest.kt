package com.hookfault.endpoints

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
        .addFilters<StandaloneMockMvcBuilder>(ApiRequestBodyLimitFilter())
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
        assertThat(body.size()).isEqualTo(4)
        assertThat(body.path("id").asString()).isEqualTo(endpoint.id.toString())
        assertThat(body.path("name").asString()).isEqualTo(endpoint.name)
        assertThat(body.path("url").asString()).isEqualTo(endpoint.url)
        assertThat(body.path("createdAt").asString()).isEqualTo(endpoint.createdAt.toString())
        Mockito.verify(service).register(endpoint.name, endpoint.url)
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
