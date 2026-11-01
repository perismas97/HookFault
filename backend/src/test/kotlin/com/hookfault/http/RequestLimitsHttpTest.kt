package com.hookfault.http

import com.hookfault.api.ApiExceptionHandler
import com.hookfault.api.OpenApiConfiguration
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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
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

    @Test
    fun documentsOnlyTheImplementedEndpointRoutesAndSuccessSchemas() {
        val doc = apiDocument()
        assertThat(doc.path("info").path("title").asString()).isEqualTo("HookFault API")
        val paths = doc.path("paths")
        assertThat(paths.size()).isEqualTo(2)
        val collection = paths.path("/api/v1/webhook-endpoints")
        val item = paths.path("/api/v1/webhook-endpoints/{id}")
        assertThat(collection.path("post").path("responses").path("201").path("headers").has("Location")).isTrue()
        assertThat(collection.path("post").path("responses").path("201").path("content")
            .path("application/json").path("schema").path("\$ref").asString()).endsWith("/WebhookEndpointResponse")
        assertThat(collection.path("get").path("responses").path("200").path("content")
            .path("application/json").path("schema").path("\$ref").asString()).endsWith("/WebhookEndpointListResponse")
        assertThat(item.path("get").path("responses").path("200").path("content")
            .path("application/json").path("schema").path("\$ref").asString()).endsWith("/WebhookEndpointResponse")
        assertThat(item.path("get").path("parameters").first().path("schema").path("format").asString()).isEqualTo("uuid")
        assertThat(paths.has("/actuator/health")).isFalse()
    }

    @Test
    fun documentsTheActualRequestConstraintsAndPagination() {
        val doc = apiDocument()
        val request = doc.path("components").path("schemas").path("RegisterWebhookEndpointRequest")
        assertThat(request.path("required").values().map { it.asString() }).contains("name", "url")
        assertThat(request.path("properties").path("name").path("maxLength").asInt()).isEqualTo(100)
        assertThat(request.path("properties").path("url").path("maxLength").asInt()).isEqualTo(2048)
        val parameters = doc.path("paths").path("/api/v1/webhook-endpoints").path("get").path("parameters")
            .associateBy { it.path("name").asString() }
        assertThat(parameters.getValue("limit").path("schema").path("maximum").asInt()).isEqualTo(100)
        assertThat(parameters.getValue("limit").path("schema").path("default").asInt()).isEqualTo(20)
        assertThat(parameters.getValue("offset").path("schema").path("minimum").asInt()).isZero()
        assertThat(doc.path("components").path("schemas").path("WebhookEndpointResponse").path("properties").size()).isEqualTo(4)
    }

    @Test
    fun documentsTheSharedErrorSchemaForActualErrorStatuses() {
        val doc = apiDocument()
        val responses = doc.path("paths").path("/api/v1/webhook-endpoints").path("post").path("responses")
        for (status in listOf("400", "405", "406", "413", "415", "500")) {
            assertThat(responses.path(status).path("content").path("application/problem+json")
                .path("schema").path("\$ref").asString()).endsWith("/ApiErrorResponse")
        }
        val notFound = doc.path("paths").path("/api/v1/webhook-endpoints/{id}").path("get").path("responses").path("404")
        assertThat(notFound.path("content").path("application/problem+json").path("schema").path("\$ref").asString())
            .endsWith("/ApiErrorResponse")
        val properties = doc.path("components").path("schemas").path("ApiErrorResponse").path("properties")
        assertThat(properties.size()).isEqualTo(7)
        for (field in listOf("type", "title", "status", "detail", "instance", "code", "errors")) {
            assertThat(properties.has(field)).isTrue()
        }
    }

    @Test
    fun servesSwaggerUiItsLocalAssetsAndTheLocalDefinition() {
        val entry = get("/swagger-ui.html")
        assertThat(entry.statusCode()).isIn(301, 302, 307, 308)
        assertThat(entry.headers().firstValue("Location").orElse("")).contains("/swagger-ui/index.html")
        val index = get("/swagger-ui/index.html")
        assertThat(index.statusCode()).isEqualTo(200)
        assertThat(index.body()).contains("swagger-ui-bundle.js", "swagger-ui.css")
        for (asset in listOf("swagger-ui.css", "swagger-ui-bundle.js")) {
            val response = get("/swagger-ui/$asset")
            assertThat(response.statusCode()).isEqualTo(200)
            assertThat(response.body().length).isGreaterThan(100)
        }
        val config = get("/v3/api-docs/swagger-config")
        assertThat(config.statusCode()).isEqualTo(200)
        val json = context.getBean(ObjectMapper::class.java).readTree(config.body())
        assertThat(json.path("url").asString()).endsWith("/v3/api-docs")
        assertThat(json.path("validatorUrl").asString()).isEmpty()
    }

    @Test
    fun usesTheSameErrorShapeForMvcAndFilterFailuresOverRealHttp() {
        val malformed = post("{", false)
        val oversized = post(" ".repeat(MAX_JSON_REQUEST_BYTES + 1), true)
        val mapper = context.getBean(ObjectMapper::class.java)
        for ((response, code) in listOf(malformed to "INVALID_JSON", oversized to "REQUEST_TOO_LARGE")) {
            val json = mapper.readTree(response.body())
            assertThat(json.size()).isEqualTo(7)
            assertThat(json.path("code").asString()).isEqualTo(code)
            assertThat(json.path("status").asInt()).isEqualTo(response.statusCode())
            assertThat(json.path("instance").asString()).isEqualTo("/api/v1/webhook-endpoints")
            assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json")
        }
    }

    private fun apiDocument(): JsonNode {
        val response = get("/v3/api-docs")
        assertThat(response.statusCode()).isEqualTo(200)
        return context.getBean(ObjectMapper::class.java).readTree(response.body())
    }

    private fun get(path: String): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(10)).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

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
    @Import(WebhookEndpointController::class, ApiRequestBodyLimitFilter::class, ApiExceptionHandler::class, OpenApiConfiguration::class,
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
