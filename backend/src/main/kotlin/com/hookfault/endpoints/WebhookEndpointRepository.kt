package com.hookfault.endpoints

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class WebhookEndpointRepository(private val jdbcTemplate: JdbcTemplate) {
    private val rowMapper = RowMapper { result, _ ->
        WebhookEndpoint(
            id = result.getObject("id", UUID::class.java), name = result.getString("name"),
            url = result.getString("url"), createdAt = result.getObject("created_at", OffsetDateTime::class.java).toInstant(),
        )
    }

    fun create(id: UUID, name: String, url: String): WebhookEndpoint = checkNotNull(
        jdbcTemplate.queryForObject(
            """
            insert into webhook_endpoints (id, name, url)
            values (?, ?, ?)
            returning id, name, url, created_at
            """.trimIndent(),
            rowMapper,
            id,
            name,
            url,
        ),
    ) { "Endpoint insert returned no row" }

    fun findById(id: UUID): WebhookEndpoint? = jdbcTemplate.query(
        "select id, name, url, created_at from webhook_endpoints where id = ?", rowMapper, id,
    ).singleOrNull()

    fun findPage(limit: Int, offset: Int): List<WebhookEndpoint> = jdbcTemplate.query(
        "select id, name, url, created_at from webhook_endpoints order by created_at desc, id desc limit ? offset ?",
        rowMapper, limit, offset,
    )
}
