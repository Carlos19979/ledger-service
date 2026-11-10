package io.github.carlos19979.ledger.adapter.postgres

import io.github.carlos19979.ledger.application.port.TransferRepository
import io.github.carlos19979.ledger.domain.AccountId
import io.github.carlos19979.ledger.domain.Money
import io.github.carlos19979.ledger.domain.Rejection
import io.github.carlos19979.ledger.domain.Transfer
import io.github.carlos19979.ledger.domain.TransferId
import io.github.carlos19979.ledger.domain.TransferKind
import io.github.carlos19979.ledger.domain.TransferStatus
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.Currency

class PostgresTransferRepository(private val jdbc: JdbcClient) : TransferRepository {
    override fun insert(transfer: Transfer) {
        jdbc
            .sql(
                """
            INSERT INTO transfers (id, kind, source_account_id, target_account_id, amount_minor, currency, reference,
                                   status, rejection_code, rejection_detail, created_at)
            VALUES (:id, :kind, :source, :target, :amount, :currency, :reference,
                    :status, :rejectionCode, :rejectionDetail, :createdAt)
            """,
            ).param("id", transfer.id.value)
            .param("kind", transfer.kind.name)
            .param("source", transfer.sourceAccountId.value)
            .param("target", transfer.targetAccountId.value)
            .param("amount", transfer.amount.minorUnits)
            .param("currency", transfer.amount.currency.currencyCode)
            .param("reference", transfer.reference)
            .param("status", transfer.status.name)
            .param("rejectionCode", transfer.rejection?.code)
            .param("rejectionDetail", transfer.rejection?.detail)
            .param("createdAt", transfer.createdAt.toTimestamp())
            .update()
    }

    override fun findById(id: TransferId): Transfer? =
        jdbc
            .sql("SELECT * FROM transfers WHERE id = :id")
            .param("id", id.value)
            .query { rs, _ ->
                val currency = Currency.getInstance(rs.getString("currency"))
                Transfer(
                    id = TransferId(rs.uuid("id")),
                    kind = TransferKind.valueOf(rs.getString("kind")),
                    sourceAccountId = AccountId(rs.uuid("source_account_id")),
                    targetAccountId = AccountId(rs.uuid("target_account_id")),
                    amount = Money(rs.getLong("amount_minor"), currency),
                    reference = rs.getString("reference"),
                    status = TransferStatus.valueOf(rs.getString("status")),
                    rejection = rs.getString("rejection_code")?.let { Rejection(it, rs.getString("rejection_detail")) },
                    createdAt = rs.instant("created_at"),
                )
            }.optional()
            .orElse(null)
}
