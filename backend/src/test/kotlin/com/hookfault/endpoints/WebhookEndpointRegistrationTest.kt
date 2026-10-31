package com.hookfault.endpoints

import com.hookfault.PostgresTestConfiguration
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "server.address=127.0.0.1",
        "management.endpoint.health.show-components=always",
    ],
)
@Import(PostgresTestConfiguration::class)
class WebhookEndpointRegistrationTest @Autowired constructor(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    @LocalServerPort
    private var port: Int = 0

    private val createdIds = mutableListOf<UUID>()
    private val validUrl = "http://localhost:8090/webhooks"

    @AfterEach
    fun removeCreatedEndpoints() {
        createdIds.forEach { jdbcTemplate.update("delete from webhook_endpoints where id = ?", it) }
    }

    @Test
    fun registersEndpointAndPersistsReturnedFields() {
        val before = endpointCount()
        val response = post(requestBody())

        assertThat(response.statusCode()).isEqualTo(201)
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json")

        val body = objectMapper.readTree(response.body())
        val id = UUID.fromString(body.path("id").asString())
        val createdAt = Instant.parse(body.path("createdAt").asString())
        assertThat(id.version()).isEqualTo(4)
        assertThat(body.size()).isEqualTo(4)
        assertThat(body.path("name").asString()).isEqualTo("Demo receiver")
        assertThat(body.path("url").asString()).isEqualTo(validUrl)
        assertThat(endpointCount()).isEqualTo(before + 1)

        val stored = jdbcTemplate.queryForMap("select * from webhook_endpoints where id = ?", id)
        assertThat(stored).containsEntry("name", "Demo receiver").containsEntry("url", validUrl)
        assertThat((stored["created_at"] as Timestamp).toInstant()).isEqualTo(createdAt)
    }

    @Test
    fun acceptsValuesAtTheirLengthLimits() {
        val name = "n".repeat(100)
        val prefix = "https://example.com/"
        val url = prefix + "x".repeat(2048 - prefix.length)

        val response = post(requestBody(name, url))

        assertThat(response.statusCode()).isEqualTo(201)
        val body = objectMapper.readTree(response.body())
        assertThat(body.path("name").asString()).isEqualTo(name)
        assertThat(body.path("url").asString()).isEqualTo(url)
    }

    @Test
    fun createsDistinctIdsForSeparateRegistrations() {
        val before = endpointCount()
        val first = post(requestBody())
        val second = post(requestBody())

        assertThat(first.statusCode()).isEqualTo(201)
        assertThat(second.statusCode()).isEqualTo(201)
        assertThat(objectMapper.readTree(first.body()).path("id").asString())
            .isNotEqualTo(objectMapper.readTree(second.body()).path("id").asString())
        assertThat(endpointCount()).isEqualTo(before + 2)
    }

    @Test
    fun storesSqlLookingNamesAsLiteralText() {
        val name = "Receiver'); DROP TABLE webhook_endpoints; --"
        val response = post(requestBody(name = name))

        assertThat(response.statusCode()).isEqualTo(201)
        val id = UUID.fromString(objectMapper.readTree(response.body()).path("id").asString())
        assertThat(jdbcTemplate.queryForObject("select name from webhook_endpoints where id = ?", String::class.java, id))
            .isEqualTo(name)
    }

    @Test
    fun doesNotContactReceiverDuringRegistration() {
        val calls = AtomicInteger()
        val receiver = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        receiver.createContext("/webhooks") { exchange ->
            calls.incrementAndGet()
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        receiver.start()
        try {
            val response = post(requestBody(url = "http://127.0.0.1:${receiver.address.port}/webhooks"))

            assertThat(response.statusCode()).isEqualTo(201)
            assertThat(calls.get()).isZero()
        } finally {
            receiver.stop(0)
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = ["   ", "\t", "\r\n"])
    fun rejectsMissingOrBlankNames(name: String?) {
        assertRejected(requestBody(name = name))
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = ["   ", "\t", "\r\n"])
    fun rejectsMissingOrBlankUrls(url: String?) {
        assertRejected(requestBody(url = url))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "\u00a0",
            "\u2007",
            "\u202f",
            "/webhooks",
            "ftp://example.com/webhooks",
            "http:///webhooks",
            "https://example.com/with space",
            "http://localhost:0/webhooks",
            "http://localhost:65536/webhooks",
            "https://user:password@example.com/webhooks",
            "https://example.com/webhooks#fragment",
            "https://example.com/%ZZ",
        ],
    )
    fun rejectsInvalidUrlsBeforePersistence(url: String) {
        assertRejected(requestBody(url = url))
    }

    @Test
    fun rejectsOverlongNamesBeforePersistence() {
        assertRejected(requestBody(name = "n".repeat(101)))
    }

    @Test
    fun rejectsOverlongUrlsBeforePersistence() {
        assertRejected(requestBody(url = "https://example.com/" + "x".repeat(2048)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "{\"name\":\"Demo receiver\"}", "{\"url\":\"http://localhost:8090/webhooks\"}"])
    fun rejectsMissingFields(body: String) {
        assertRejected(body)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "{",
            "null",
            "[]",
            "{\"name\":{},\"url\":\"http://localhost:8090/webhooks\"}",
            "{\"name\":\"Demo receiver\",\"url\":[]}",
        ],
    )
    fun rejectsMalformedOrIncorrectlyShapedJson(body: String) {
        assertRejected(body)
    }

    @Test
    @ExtendWith(OutputCaptureExtension::class)
    fun rejectsCredentialsWithoutEchoingThem(output: CapturedOutput) {
        val response = assertRejected(requestBody(url = "https://user:do-not-expose@example.com/webhooks"))

        assertThat(response.body()).doesNotContain("do-not-expose")
        assertThat(output.all).doesNotContain("do-not-expose")
    }

    private fun requestBody(name: String? = "Demo receiver", url: String? = validUrl): String =
        objectMapper.writeValueAsString(mapOf("name" to name, "url" to url))

    private fun endpointCount(): Int =
        checkNotNull(jdbcTemplate.queryForObject("select count(*) from webhook_endpoints", Int::class.java))

    private fun assertRejected(body: String): HttpResponse<String> {
        val before = endpointCount()
        val response = post(body)
        assertThat(response.statusCode()).isEqualTo(400)
        assertThat(endpointCount()).isEqualTo(before)
        return response
    }

    private fun post(body: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:$port/api/v1/webhook-endpoints"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()
            .use { client -> client.send(request, HttpResponse.BodyHandlers.ofString()) }

        if (response.statusCode() == 201) {
            createdIds.add(UUID.fromString(objectMapper.readTree(response.body()).path("id").asString()))
        }
        return response
    }
}
