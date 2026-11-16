package com.hookfault.receiver.events

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionOperations
import java.util.UUID

@Component
internal class ReceiverRunTransaction(
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionOperations,
) {
    fun write(runId: UUID, action: () -> Unit) {
        transactions.executeWithoutResult {
            // Serialize deliveries and resets for this run until the transaction ends.
            jdbc.query(
                "select pg_advisory_xact_lock(hashtextextended(?, 0))",
                RowCallbackHandler { },
                "hookfault-receiver:$runId",
            )
            action()
        }
    }
}
