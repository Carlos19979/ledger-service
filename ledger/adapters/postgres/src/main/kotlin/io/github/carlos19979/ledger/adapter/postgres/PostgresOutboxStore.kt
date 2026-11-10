package io.github.carlos19979.ledger.adapter.postgres

import io.github.carlos19979.ledger.application.port.OutboxMessage
import io.github.carlos19979.ledger.application.port.OutboxStore
import io.github.carlos19979.ledger.application.port.PendingMessage
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant

class PostgresOutboxStore(private val jdbc: JdbcClient) : OutboxStore {
    override fun append(messages: List<OutboxMessage>) {
        messages.forEach { message ->
            jdbc
                .sql(
                    """
                INSERT INTO outbox (event_id, topic, message_key, event_type, schema_version, payload, occurred_at)
                VALUES (:eventId, :topic, :key, :type, :version, CAST(:payload AS jsonb), :occurredAt)
                """,
                ).param("eventId", message.eventId)
                .param("topic", message.topic)
                .param("key", message.key)
                .param("type", message.eventType)
                .param("version", message.schemaVersion)
                .param("payload", message.payload)
                .param("occurredAt", message.occurredAt.toTimestamp())
                .update()
        }
    }

    // A transaction-scoped advisory lock makes the relay a single publisher across instances: the others return an
    // empty batch instead of racing, which keeps every key's events in commit order.
    override fun lockPendingBatch(limit: Int): List<PendingMessage> {
        val leader =
            jdbc
                .sql("SELECT pg_try_advisory_xact_lock(:lock)")
                .param("lock", RELAY_LOCK_ID)
                .query(Boolean::class.java)
                .single()
        if (!leader) return emptyList()

        return jdbc
            .sql(
                """
                SELECT *, payload::text AS payload_text FROM outbox
                WHERE published_at IS NULL ORDER BY id LIMIT :limit
                """,
            ).param("limit", limit)
            .query { rs, _ ->
                PendingMessage(
                    id = rs.getLong("id"),
                    message =
                        OutboxMessage(
                            eventId = rs.uuid("event_id"),
                            topic = rs.getString("topic"),
                            key = rs.getString("message_key"),
                            eventType = rs.getString("event_type"),
                            schemaVersion = rs.getInt("schema_version"),
                            payload = rs.getString("payload_text"),
                            occurredAt = rs.instant("occurred_at"),
                        ),
                    attempts = rs.getInt("attempts"),
                )
            }.list()
    }

    override fun markPublished(ids: List<Long>, at: Instant) {
        if (ids.isEmpty()) return
        jdbc
            .sql("UPDATE outbox SET published_at = :at WHERE id IN (:ids)")
            .param("at", at.toTimestamp())
            .param("ids", ids)
            .update()
    }

    override fun recordFailure(id: Long, error: String) {
        jdbc
            .sql("UPDATE outbox SET attempts = attempts + 1, last_error = :error WHERE id = :id")
            .param("id", id)
            .param("error", error.take(MAX_ERROR_LENGTH))
            .update()
    }

    override fun pendingCount(): Long =
        jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL").query(Long::class.java).single()

    private companion object {
        const val RELAY_LOCK_ID = 7_301_726_521L
        const val MAX_ERROR_LENGTH = 1_000
    }
}
