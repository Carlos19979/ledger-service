package io.github.carlos19979.ledger.adapter.postgres

import io.github.carlos19979.ledger.application.port.AccountRepository
import io.github.carlos19979.ledger.domain.Account
import io.github.carlos19979.ledger.domain.AccountId
import io.github.carlos19979.ledger.domain.AccountStatus
import io.github.carlos19979.ledger.domain.AccountType
import io.github.carlos19979.ledger.domain.Money
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.util.Currency

class PostgresAccountRepository(private val jdbc: JdbcClient) : AccountRepository {
    override fun insert(account: Account) {
        jdbc
            .sql(
                """
            INSERT INTO accounts (id, owner, type, currency, balance_minor, status, daily_debit_limit_minor,
                                  version, opened_at)
            VALUES (:id, :owner, :type, :currency, :balance, :status, :limit, :version, :openedAt)
            """,
            ).param("id", account.id.value)
            .param("owner", account.owner)
            .param("type", account.type.name)
            .param("currency", account.currency.currencyCode)
            .param("balance", account.balance.minorUnits)
            .param("status", account.status.name)
            .param("limit", account.dailyDebitLimit?.minorUnits)
            .param("version", account.version)
            .param("openedAt", account.openedAt.toTimestamp())
            .update()
    }

    override fun findById(id: AccountId): Account? =
        jdbc
            .sql("SELECT * FROM accounts WHERE id = :id")
            .param("id", id.value)
            .query { rs, _ -> rs.toAccount() }
            .optional()
            .orElse(null)

    // ORDER BY before FOR UPDATE: PostgreSQL takes the row locks in that order, so every transaction locks a given
    // pair of accounts the same way round and two opposite transfers cannot deadlock.
    override fun lockForUpdate(ids: Set<AccountId>): Map<AccountId, Account> {
        if (ids.isEmpty()) return emptyMap()
        return jdbc
            .sql("SELECT * FROM accounts WHERE id IN (:ids) ORDER BY id FOR UPDATE")
            .param("ids", ids.map { it.value })
            .query { rs, _ -> rs.toAccount() }
            .list()
            .associateBy { it.id }
    }

    override fun update(account: Account, expectedVersion: Long) {
        val updated =
            jdbc
                .sql(
                    """
            UPDATE accounts SET balance_minor = :balance, status = :status, version = :version
            WHERE id = :id AND version = :expected
            """,
                ).param("id", account.id.value)
                .param("balance", account.balance.minorUnits)
                .param("status", account.status.name)
                .param("version", account.version)
                .param("expected", expectedVersion)
                .update()
        check(updated == 1) { "Account ${account.id} changed concurrently (expected version $expectedVersion)" }
    }

    override fun fundingAccountFor(currency: Currency): AccountId? =
        jdbc
            .sql("SELECT id FROM accounts WHERE type = 'INTERNAL' AND currency = :currency")
            .param("currency", currency.currencyCode)
            .query { rs, _ -> AccountId(rs.uuid("id")) }
            .optional()
            .orElse(null)

    private fun ResultSet.toAccount(): Account {
        val currency = Currency.getInstance(getString("currency"))
        return Account.rehydrate(
            id = AccountId(uuid("id")),
            owner = getString("owner"),
            type = AccountType.valueOf(getString("type")),
            currency = currency,
            balance = Money(getLong("balance_minor"), currency),
            status = AccountStatus.valueOf(getString("status")),
            dailyDebitLimit = longOrNull("daily_debit_limit_minor")?.let { Money(it, currency) },
            version = getLong("version"),
            openedAt = instant("opened_at"),
        )
    }
}
