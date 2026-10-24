package com.hookfault

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "server.address=127.0.0.1",
        "management.endpoint.health.show-components=always",
    ],
)
class ApplicationStartupTest @Autowired constructor(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val postgres: PostgreSQLContainer,
) {
    @LocalServerPort
    private var port: Int = 0

    @Test
    fun connectsToContainerizedPostgreSQL() {
        val jdbcUrl = jdbcTemplate.execute(ConnectionCallback { connection -> connection.metaData.url })
        val database = jdbcTemplate.queryForObject("select current_database()", String::class.java)

        assertThat(jdbcUrl).isEqualTo(postgres.jdbcUrl)
        assertThat(database).isEqualTo(postgres.databaseName)
    }

    @Test
    fun healthEndpointReportsApplicationAndDatabaseUp() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:$port/actuator/health"))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()

        val response = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()
            .use { client -> client.send(request, HttpResponse.BodyHandlers.ofString()) }

        assertThat(response.statusCode()).isEqualTo(200)

        val health = objectMapper.readTree(response.body())
        assertThat(health.path("status").asString()).isEqualTo("UP")
        assertThat(health.path("components").path("db").path("status").asString()).isEqualTo("UP")
    }

    @TestConfiguration(proxyBeanMethods = false)
    class PostgresConfiguration {
        @Bean
        @ServiceConnection
        fun postgres(): PostgreSQLContainer = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("hookfault_test")
    }
}
