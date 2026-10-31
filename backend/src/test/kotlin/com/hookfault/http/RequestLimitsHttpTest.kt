package com.hookfault.http

import com.hookfault.endpoints.WebhookEndpoint
import com.hookfault.endpoints.WebhookEndpointController
import com.hookfault.endpoints.WebhookEndpointService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RequestLimitsHttpTest {
    private lateinit var context: ConfigurableApplicationContext
    private lateinit var client: HttpClient
    private var port = 0
    private val body = """{"name":"Demo receiver","url":"http://localhost:8090/webhooks"}"""

    @BeforeAll
    fun startHttpApplicationWithoutADatabase() {
        context = SpringApplicationBuilder(HttpTestConfiguration::class.java)
            .run("--server.port=0", "--server.address=127.0.0.1", "--spring.main.banner-mode=off")
        port = context.environment.getRequiredProperty("local.server.port", Int::class.java)
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    }

    @AfterAll
    fun stopHttpApplication() {
        if (::client.isInitialized) client.close()
        if (::context.isInitialized) context.close()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun acceptsAnExactLimitBodyOverRealHttp(chunked: Boolean) {
        val before = serviceCalls()
        val padded = body + " ".repeat(MAX_JSON_REQUEST_BYTES - body.toByteArray().size)
        assertThat(post(padded, chunked).statusCode()).isEqualTo(201)
        assertThat(serviceCalls()).isEqualTo(before + 1)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun rejectsAnOversizedBodyOverRealHttp(chunked: Boolean) {
        val before = serviceCalls()
        val response = post(body + " ".repeat(MAX_JSON_REQUEST_BYTES), chunked)
        assertThat(response.statusCode()).isEqualTo(413)
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json")
        assertThat(serviceCalls()).isEqualTo(before)
    }

    @Test
    fun rejectsOversizedMultibyteInputBeforeTheController() {
        val before = serviceCalls()
        val text = body + "\u03b1".repeat(8200)
        assertThat(text.length).isLessThan(MAX_JSON_REQUEST_BYTES)
        assertThat(post(text, true).statusCode()).isEqualTo(413)
        assertThat(serviceCalls()).isEqualTo(before)
    }

    @Test
    fun loadsTheFiveDefaultOriginsFromApplicationYaml() {
        val policy = context.getBean(WebhookDestinationPolicy::class.java)
        for (host in listOf("localhost", "127.0.0.1", "[::1]", "demo-receiver", "host.docker.internal")) {
            assertThat(policy.requireAllowed("http://$host:8090/webhooks").port).isEqualTo(8090)
        }
        assertThatThrownBy { policy.requireAllowed("http://example.com/webhooks") }
            .isInstanceOf(WebhookDestinationRejectedException::class.java)
    }

    private fun serviceCalls(): Int = context.getBean(AtomicInteger::class.java).get()

    private fun post(text: String, chunked: Boolean): HttpResponse<String> {
        val publisher = if (chunked) HttpRequest.BodyPublishers.ofInputStream { text.byteInputStream() }
            else HttpRequest.BodyPublishers.ofString(text)
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/webhook-endpoints"))
            .header("Content-Type", "application/json").timeout(Duration.ofSeconds(10))
            .POST(publisher).build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = [DataSourceAutoConfiguration::class, FlywayAutoConfiguration::class])
    @Import(WebhookEndpointController::class, ApiRequestBodyLimitFilter::class,
        WebhookHttpConfiguration::class, WebhookDestinationPolicy::class)
    class HttpTestConfiguration {
        @Bean
        fun serviceCalls(): AtomicInteger = AtomicInteger()

        @Bean
        fun service(calls: AtomicInteger): WebhookEndpointService = Mockito.mock(WebhookEndpointService::class.java).apply {
            Mockito.`when`(register(Mockito.anyString(), Mockito.anyString())).thenAnswer { invocation ->
                calls.incrementAndGet()
                WebhookEndpoint(UUID.randomUUID(), invocation.getArgument(0), invocation.getArgument(1), Instant.now())
            }
        }
    }
}
