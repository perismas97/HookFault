package com.hookfault.receiver.events

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
internal class DemoEventProcessor(private val jdbc: JdbcTemplate) {
    fun process(event: DemoUserCreated) {
        // Only a newly claimed event returns a row to the notification insert.
        jdbc.update(
            """
            WITH claimed AS (
                INSERT INTO hookfault_receiver.processed_events (run_id, event_id)
                VALUES (?, ?)
                ON CONFLICT (run_id, event_id) DO NOTHING
                RETURNING run_id, event_id
            )
            INSERT INTO hookfault_receiver.welcome_notifications (run_id, event_id, user_id)
            SELECT run_id, event_id, ? FROM claimed
            """.trimIndent(),
            event.runId, event.eventId, event.userId,
        )
    }
}
