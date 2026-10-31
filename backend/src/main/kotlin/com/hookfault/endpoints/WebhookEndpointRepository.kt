package com.hookfault.endpoints

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class WebhookEndpointRepository(private val jdbcTemplate: JdbcTemplate) {
    fun create(id: UUID, name: String, url: String): WebhookEndpoint = checkNotNull(
        jdbcTemplate.queryForObject(
            """
            insert into webhook_endpoints (id, name, url)
            values (?, ?, ?)
            returning id, name, url, created_at
            """.trimIndent(),
            RowMapper { result, _ ->
                WebhookEndpoint(
                    id = result.getObject("id", UUID::class.java),
                    name = result.getString("name"),
                    url = result.getString("url"),
                    createdAt = result.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                )
            },
            id,
            name,
            url,
        ),
    ) { "Endpoint insert returned no row" }
}
