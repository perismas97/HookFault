package com.hookfault.receiver.events

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.util.UUID

internal data class ReceiverRunSnapshot(
    val runId: UUID,
    val processedEventCount: Long,
    val welcomeNotificationCount: Long,
)

@Component
internal class ReceiverRunState(
    private val jdbc: JdbcTemplate,
    private val runTransaction: ReceiverRunTransaction,
) {
    fun inspect(runId: UUID): ReceiverRunSnapshot = checkNotNull(
        jdbc.queryForObject(
            """
            SELECT
                (SELECT count(*) FROM hookfault_receiver.processed_events WHERE run_id = ?) AS events,
                (SELECT count(*) FROM hookfault_receiver.welcome_notifications WHERE run_id = ?) AS notifications
            """.trimIndent(),
            RowMapper { row, _ -> ReceiverRunSnapshot(runId, row.getLong("events"), row.getLong("notifications")) },
            runId, runId,
        ),
    )

    fun reset(runId: UUID) = runTransaction.write(runId) {
        jdbc.update("DELETE FROM hookfault_receiver.welcome_notifications WHERE run_id = ?", runId)
        jdbc.update("DELETE FROM hookfault_receiver.processed_events WHERE run_id = ?", runId)
    }
}
