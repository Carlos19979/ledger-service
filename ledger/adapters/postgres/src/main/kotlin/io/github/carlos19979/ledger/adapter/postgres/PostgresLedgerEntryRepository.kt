package io.github.carlos19979.ledger.adapter.postgres

import io.github.carlos19979.ledger.application.port.LedgerEntryRepository
import io.github.carlos19979.ledger.application.port.StatementPage
import io.github.carlos19979.ledger.domain.AccountId
import io.github.carlos19979.ledger.domain.EntryDirection
import io.github.carlos19979.ledger.domain.EntryId
import io.github.carlos19979.ledger.domain.LedgerEntry
import io.github.carlos19979.ledger.domain.Money
import io.github.carlos19979.ledger.domain.TransferId
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.util.Currency

class PostgresLedgerEntryRepository(private val jdbc: JdbcClient) : LedgerEntryRepository {
    override fun insertAll(entries: List<LedgerEntry>) {
        entries.forEach { entry ->
            jdbc
                .sql(
                    """
                INSERT INTO ledger_entries (id, account_id, transfer_id, direction, amount_minor, currency,
                                            balance_after_minor, account_version, posted_at)
                VALUES (:id, :account, :transfer, :direction, :amount, :currency, :balanceAfter, :version, :postedAt)
                """,
                ).param("id", entry.id.value)
                .param("account", entry.accountId.value)
                .param("transfer", entry.transferId.value)
                .param("direction", entry.direction.name)
                .param("amount", entry.amount.minorUnits)
                .param("currency", entry.amount.currency.currencyCode)
                .param("balanceAfter", entry.balanceAfter.minorUnits)
                .param("version", entry.accountVersion)
                .param("postedAt", entry.postedAt.toTimestamp())
                .update()
        }
    }

    override fun debitedSince(accountId: AccountId, since: Instant, currency: Currency): Money {
        val minor =
            jdbc
                .sql(
                    """
            SELECT coalesce(sum(amount_minor), 0) FROM ledger_entries
            WHERE account_id = :account AND direction = 'DEBIT' AND posted_at >= :since
            """,
                ).param("account", accountId.value)
                .param("since", since.toTimestamp())
                .query(Long::class.java)
                .single()
        return Money(minor, currency)
    }

    override fun statement(accountId: AccountId, before: Long?, limit: Int): StatementPage {
        // One extra row tells us whether another page exists without a COUNT query.
        val rows =
            jdbc
                .sql(
                    """
            SELECT * FROM ledger_entries
            WHERE account_id = :account AND sequence < coalesce(CAST(:before AS bigint), 9223372036854775807)
            ORDER BY sequence DESC
            LIMIT :limit
            """,
                ).param("account", accountId.value)
                .param("before", before)
                .param("limit", limit + 1)
                .query { rs, _ ->
                    val currency = Currency.getInstance(rs.getString("currency"))
                    rs.getLong("sequence") to
                        LedgerEntry(
                            id = EntryId(rs.uuid("id")),
                            accountId = AccountId(rs.uuid("account_id")),
                            transferId = TransferId(rs.uuid("transfer_id")),
                            direction = EntryDirection.valueOf(rs.getString("direction")),
                            amount = Money(rs.getLong("amount_minor"), currency),
                            balanceAfter = Money(rs.getLong("balance_after_minor"), currency),
                            accountVersion = rs.getLong("account_version"),
                            postedAt = rs.instant("posted_at"),
                        )
                }.list()
        val page = rows.take(limit)
        return StatementPage(page.map { it.second }, if (rows.size > limit) page.last().first else null)
    }
}
