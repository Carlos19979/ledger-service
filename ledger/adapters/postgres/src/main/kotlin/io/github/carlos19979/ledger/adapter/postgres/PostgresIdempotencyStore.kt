package io.github.carlos19979.ledger.adapter.postgres

import io.github.carlos19979.ledger.application.port.IdempotencyClaim
import io.github.carlos19979.ledger.application.port.IdempotencyStore
import io.github.carlos19979.ledger.domain.TransferId
import org.springframework.jdbc.core.simple.JdbcClient

class PostgresIdempotencyStore(private val jdbc: JdbcClient) : IdempotencyStore {
    // ON CONFLICT DO NOTHING waits for a concurrent transaction holding the same key: if it commits, we see its
    // row and replay its result; if it rolls back, our insert goes through and we own the key.
    override fun claim(scope: String, key: String, requestHash: String, transferId: TransferId): IdempotencyClaim {
        val inserted =
            jdbc
                .sql(
                    """
            INSERT INTO idempotency_keys (scope, idempotency_key, request_hash, transfer_id)
            VALUES (:scope, :key, :hash, :transfer)
            ON CONFLICT DO NOTHING
            """,
                ).param("scope", scope)
                .param("key", key)
                .param("hash", requestHash)
                .param("transfer", transferId.value)
                .update()
        if (inserted == 1) return IdempotencyClaim.Claimed

        return jdbc
            .sql(
                """
                SELECT request_hash, transfer_id FROM idempotency_keys
                WHERE scope = :scope AND idempotency_key = :key
                """,
            ).param("scope", scope)
            .param("key", key)
            .query {
                rs,
                _,
                ->
                IdempotencyClaim.AlreadyUsed(rs.getString("request_hash"), TransferId(rs.uuid("transfer_id")))
            }.single()
    }
}
