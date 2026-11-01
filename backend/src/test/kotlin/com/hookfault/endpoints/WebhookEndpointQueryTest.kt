package com.hookfault.endpoints

import com.hookfault.PostgresTestConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = ["server.address=127.0.0.1", "management.endpoint.health.show-components=always"],
)
@Import(PostgresTestConfiguration::class)
class WebhookEndpointQueryTest @Autowired constructor(
    private val jdbcTemplate: JdbcTemplate,
    private val mapper: ObjectMapper,
) {
    @LocalServerPort
    private var port = 0
    private val path = "/api/v1/webhook-endpoints"
    private val createdIds = mutableListOf<UUID>()

    @AfterEach
    fun removeOnlyThisTestsEndpoints() {
        createdIds.forEach { jdbcTemplate.update("delete from webhook_endpoints where id = ?", it) }
    }

    @Test
    fun returnsAnEmptyDefaultPage() {
        val response = get(path)
        assertThat(response.statusCode()).isEqualTo(200)
        val page = mapper.readTree(response.body())
        assertThat(page.size()).isEqualTo(4)
        assertThat(page.path("items").isArray).isTrue()
        assertThat(page.path("items").size()).isZero()
        assertThat(page.path("limit").asInt()).isEqualTo(20)
        assertThat(page.path("offset").asInt()).isZero()
        assertThat(page.path("hasMore").asBoolean()).isFalse()
    }

    @Test
    fun followsTheCreatedLocationAndReturnsExactlyThePersistedPublicFields() {
        val request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"name":"Demo receiver","url":"http://localhost:8090/webhooks"}"""))
            .timeout(Duration.ofSeconds(10)).build()
        val created = send(request)
        assertThat(created.statusCode()).isEqualTo(201)
        val expected = mapper.readTree(created.body())
        val id = UUID.fromString(expected.path("id").asString())
        createdIds.add(id)
        val location = created.headers().firstValue("Location").orElseThrow()
        assertThat(location).isEqualTo("$path/$id")
        val response = get(location)
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(mapper.readTree(response.body())).isEqualTo(expected)
        assertThat(expected.size()).isEqualTo(4)
        assertThat(jdbcTemplate.queryForObject("select count(*) from webhook_endpoints where id = ?", Int::class.java, id)).isEqualTo(1)
        assertThat(mapper.readTree(get("$path/${id.toString().uppercase()}").body())).isEqualTo(expected)
    }

    @Test
    fun returnsStoredUrlsEvenIfTheyAreNoLongerAllowedForDelivery() {
        val id = seed(url = "http://example.com/previously-configured")
        val response = get("$path/$id")
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(mapper.readTree(response.body()).path("url").asString()).isEqualTo("http://example.com/previously-configured")
    }

    @Test
    fun sortsByCreationTimeAndReturnsBoundedPagesWithHasMore() {
        val base = Instant.parse("2026-11-01T00:00:00Z")
        val oldest = seed(createdAt = base)
        val middle = seed(createdAt = base.plusSeconds(1))
        val newest = seed(createdAt = base.plusSeconds(2))
        val first = page("?limit=1&offset=0")
        assertThat(ids(first)).containsExactly(newest)
        assertThat(first.path("hasMore").asBoolean()).isTrue()
        val second = page("?limit=1&offset=1")
        assertThat(ids(second)).containsExactly(middle)
        assertThat(second.path("offset").asInt()).isEqualTo(1)
        assertThat(second.path("hasMore").asBoolean()).isTrue()
        val last = page("?limit=1&offset=2")
        assertThat(ids(last)).containsExactly(oldest)
        assertThat(last.path("hasMore").asBoolean()).isFalse()
        assertThat(last.path("items").first().size()).isEqualTo(4)
    }

    @Test
    fun usesIdDescendingToBreakEqualTimestampTies() {
        val timestamp = Instant.parse("2026-11-01T00:00:00.123456Z")
        val ids = (1..3).map { UUID.fromString("00000000-0000-4000-8000-00000000000$it") }
        ids.forEach { seed(id = it, createdAt = timestamp) }
        assertThat(ids(page())).containsExactly(ids[2], ids[1], ids[0])
    }

    @Test
    fun usesTwentyItemsByDefaultAndAcceptsTheMaximumPageSize() {
        val base = Instant.parse("2026-11-01T00:00:00Z")
        repeat(21) { seed(createdAt = base.plusSeconds(it.toLong())) }
        val defaultPage = page()
        assertThat(defaultPage.path("items").size()).isEqualTo(20)
        assertThat(defaultPage.path("hasMore").asBoolean()).isTrue()
        val maximum = page("?limit=100")
        assertThat(maximum.path("items").size()).isEqualTo(21)
        assertThat(maximum.path("hasMore").asBoolean()).isFalse()
    }

    @Test
    fun returnsAnEmptyPagePastTheEnd() {
        seed()
        val page = page("?offset=1000")
        assertThat(page.path("items").size()).isZero()
        assertThat(page.path("hasMore").asBoolean()).isFalse()
    }

    @Test
    fun distinguishesAMissingEndpointFromAnInvalidId() {
        val missing = get("$path/${UUID.randomUUID()}")
        assertThat(missing.statusCode()).isEqualTo(404)
        assertThat(mapper.readTree(missing.body()).path("code").asString()).isEqualTo("ENDPOINT_NOT_FOUND")
        val invalid = get("$path/1-1-1-1-1")
        assertThat(invalid.statusCode()).isEqualTo(400)
        assertThat(mapper.readTree(invalid.body()).path("code").asString()).isEqualTo("INVALID_ENDPOINT_ID")
    }

    @ParameterizedTest
    @ValueSource(strings = ["limit=0", "limit=101", "offset=-1", "limit=abc", "offset=2147483648"])
    fun rejectsInvalidQueriesWithoutChangingStoredRecords(query: String) {
        seed()
        val before = jdbcTemplate.queryForObject("select count(*) from webhook_endpoints", Int::class.java)
        val response = get("$path?$query")
        assertThat(response.statusCode()).isEqualTo(400)
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json")
        assertThat(mapper.readTree(response.body()).path("status").asInt()).isEqualTo(400)
        assertThat(jdbcTemplate.queryForObject("select count(*) from webhook_endpoints", Int::class.java)).isEqualTo(before)
    }

    private fun page(query: String = ""): JsonNode {
        val response = get(path + query)
        assertThat(response.statusCode()).isEqualTo(200)
        return mapper.readTree(response.body())
    }

    private fun ids(page: JsonNode): List<UUID> = page.path("items").values().map { UUID.fromString(it.path("id").asString()) }

    private fun seed(
        id: UUID = UUID.randomUUID(), createdAt: Instant = Instant.parse("2026-11-01T00:00:00Z"),
        url: String = "http://localhost:8090/webhooks",
    ): UUID {
        jdbcTemplate.update("insert into webhook_endpoints (id, name, url, created_at) values (?, ?, ?, ?)",
            id, "Demo receiver", url, Timestamp.from(createdAt))
        createdIds.add(id)
        return id
    }

    private fun uri(path: String): URI = URI("http://127.0.0.1:$port$path")
    private fun get(path: String): HttpResponse<String> = send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET().build())
    private fun send(request: HttpRequest): HttpResponse<String> = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        .use { it.send(request, HttpResponse.BodyHandlers.ofString()) }
}
