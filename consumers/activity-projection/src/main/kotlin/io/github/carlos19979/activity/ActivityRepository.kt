package io.github.carlos19979.activity

import io.github.carlos19979.contracts.ledger.EntryPostedV1
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class AccountActivity(
    val accountId: UUID,
    val currency: String,
    val balanceMinor: Long,
    val lastAccountVersion: Long,
    val creditsMinor: Long,
    val debitsMinor: Long,
    val entryCount: Long,
    val lastPostedAt: OffsetDateTime,
)

@Repository
class ActivityRepository(private val jdbc: JdbcClient) {
    /** True the first time an event id is seen. */
    fun markProcessed(eventId: UUID): Boolean =
        jdbc
            .sql("INSERT INTO processed_events (event_id) VALUES (:id) ON CONFLICT DO NOTHING")
            .param("id", eventId)
            .update() == 1

    /**
     * Totals are commutative, so they are correct in any delivery order. The balance is taken from the entry with
     * the highest account version, so a late, older entry never overwrites a newer balance.
     */
    fun apply(event: EntryPostedV1) {
        val credit = if (event.direction == "CREDIT") event.amountMinor else 0
        val debit = if (event.direction == "DEBIT") event.amountMinor else 0
        jdbc
            .sql(
                """
            INSERT INTO account_activity (account_id, currency, balance_minor, last_account_version,
                                          credits_minor, debits_minor, entry_count, last_posted_at)
            VALUES (:account, :currency, :balance, :version, :credit, :debit, 1, :postedAt)
            ON CONFLICT (account_id) DO UPDATE SET
                credits_minor = account_activity.credits_minor + EXCLUDED.credits_minor,
                debits_minor = account_activity.debits_minor + EXCLUDED.debits_minor,
                entry_count = account_activity.entry_count + 1,
                balance_minor = CASE WHEN EXCLUDED.last_account_version > account_activity.last_account_version
                                     THEN EXCLUDED.balance_minor ELSE account_activity.balance_minor END,
                last_account_version = greatest(account_activity.last_account_version, EXCLUDED.last_account_version),
                last_posted_at = greatest(account_activity.last_posted_at, EXCLUDED.last_posted_at)
            """,
            ).param("account", event.accountId)
            .param("currency", event.currency)
            .param("balance", event.balanceAfterMinor)
            .param("version", event.accountVersion)
            .param("credit", credit)
            .param("debit", debit)
            .param("postedAt", event.postedAt.atOffset(ZoneOffset.UTC))
            .update()
    }

    fun find(accountId: UUID): AccountActivity? =
        jdbc
            .sql("SELECT * FROM account_activity WHERE account_id = :id")
            .param("id", accountId)
            .query { rs, _ ->
                AccountActivity(
                    accountId = rs.getObject("account_id", UUID::class.java),
                    currency = rs.getString("currency"),
                    balanceMinor = rs.getLong("balance_minor"),
                    lastAccountVersion = rs.getLong("last_account_version"),
                    creditsMinor = rs.getLong("credits_minor"),
                    debitsMinor = rs.getLong("debits_minor"),
                    entryCount = rs.getLong("entry_count"),
                    lastPostedAt = rs.getObject("last_posted_at", OffsetDateTime::class.java),
                )
            }.optional()
            .orElse(null)
}
