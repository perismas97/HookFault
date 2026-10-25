package com.hookfault

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationState
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.NONE
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.util.UUID

@SpringBootTest(webEnvironment = NONE)
@Import(PostgresTestConfiguration::class)
class DatabaseMigrationTest @Autowired constructor(
    private val flyway: Flyway,
    private val jdbcTemplate: JdbcTemplate,
) {
    @Test
    fun appliesInitialMigrationOnStartup() {
        val initialMigration = flyway.info().applied().filter { it.version?.version == "1" }

        assertThat(initialMigration).hasSize(1)
        assertThat(initialMigration.single().state).isEqualTo(MigrationState.SUCCESS)
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue()
    }

    @Test
    @Transactional
    fun createsEndpointTableWithDatabaseTimestamp() {
        val id = UUID.randomUUID()
        insertEndpoint(id)

        val endpoint = jdbcTemplate.queryForMap("select * from webhook_endpoints where id = ?", id)

        assertThat(endpoint.keys).containsExactlyInAnyOrder("id", "name", "url", "created_at")
        assertThat(endpoint).containsEntry("id", id)
            .containsEntry("name", "Demo receiver")
            .containsEntry("url", "http://localhost:8090/webhooks")
        assertThat(endpoint["created_at"]).isInstanceOf(Timestamp::class.java)
    }

    @Test
    fun repeatedMigrationPreservesExistingEndpoint() {
        val id = UUID.randomUUID()
        try {
            insertEndpoint(id)
            val before = jdbcTemplate.queryForMap("select * from webhook_endpoints where id = ?", id)

            val result = flyway.migrate()

            assertThat(result.migrationsExecuted).isZero()
            assertThat(jdbcTemplate.queryForMap("select * from webhook_endpoints where id = ?", id))
                .isEqualTo(before)
        } finally {
            jdbcTemplate.update("delete from webhook_endpoints where id = ?", id)
        }
    }

    @Test
    @Transactional
    fun rejectsDuplicateEndpointIds() {
        val id = UUID.randomUUID()
        insertEndpoint(id)

        assertThatThrownBy { insertEndpoint(id) }
            .isInstanceOf(DuplicateKeyException::class.java)
    }

    @ParameterizedTest(name = "rejects missing or blank endpoint names [{index}]")
    @NullAndEmptySource
    @ValueSource(strings = ["   ", "\t", "\r\n"])
    @Transactional
    fun rejectsMissingOrBlankEndpointNames(name: String?) {
        assertThatThrownBy { insertEndpoint(name = name) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @ParameterizedTest(name = "rejects missing or blank endpoint URLs [{index}]")
    @NullAndEmptySource
    @ValueSource(strings = ["   ", "\t", "\r\n"])
    @Transactional
    fun rejectsMissingOrBlankEndpointUrls(url: String?) {
        assertThatThrownBy { insertEndpoint(url = url) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    @Transactional
    fun rejectsEndpointNamesLongerThanOneHundredCharacters() {
        assertThatThrownBy { insertEndpoint(name = "n".repeat(101)) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    @Transactional
    fun rejectsEndpointUrlsLongerThanTwoThousandFortyEightCharacters() {
        assertThatThrownBy { insertEndpoint(url = "u".repeat(2049)) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    private fun insertEndpoint(
        id: UUID = UUID.randomUUID(),
        name: String? = "Demo receiver",
        url: String? = "http://localhost:8090/webhooks",
    ) {
        jdbcTemplate.update(
            "insert into webhook_endpoints (id, name, url) values (?, ?, ?)",
            id,
            name,
            url,
        )
    }
}
