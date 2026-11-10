package io.github.carlos19979.ledger.adapter.postgres

import io.github.carlos19979.ledger.application.account.OpenAccountCommand
import io.github.carlos19979.ledger.application.transfer.DepositCommand
import io.github.carlos19979.ledger.domain.Money
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import org.springframework.dao.DataAccessException
import org.springframework.transaction.TransactionSystemException
import java.util.Currency
import java.util.UUID

/** The database refuses a broken ledger even if application code tried to write one. */
class LedgerIntegritySpec : FunSpec({
    val ledger = PostgresLedger()
    val account = ledger.openAccount.execute(OpenAccountCommand("Ada", Currency.getInstance("EUR"), null)).id
    ledger.transferMoney.deposit(DepositCommand("seed", account, Money.of("10.00", "EUR")))

    test("ledger entries cannot be edited or deleted") {
        shouldThrow<DataAccessException> { ledger.jdbc.sql("UPDATE ledger_entries SET amount_minor = 1").update() }
            .message shouldContain "append-only"
        shouldThrow<DataAccessException> { ledger.jdbc.sql("DELETE FROM ledger_entries").update() }
        shouldThrow<DataAccessException> { ledger.jdbc.sql("TRUNCATE ledger_entries CASCADE").update() }
    }

    test("transfers cannot be rewritten") {
        shouldThrow<DataAccessException> { ledger.jdbc.sql("UPDATE transfers SET status = 'REJECTED'").update() }
    }

    test("a customer balance can never be stored below zero") {
        shouldThrow<DataAccessException> {
            ledger.jdbc
                .sql("UPDATE accounts SET balance_minor = -1 WHERE id = :id")
                .param("id", account.value)
                .update()
        }.message shouldContain "customer_balance_not_negative"
    }

    test("a transfer with a single, unbalanced entry fails at commit") {
        val transfer = UUID.randomUUID()
        // Deferred constraints fire on COMMIT, which Spring reports as a transaction failure.
        val error =
            shouldThrow<TransactionSystemException> {
                ledger.tx.inTransaction {
                    ledger.jdbc
                        .sql(
                            """
                    INSERT INTO transfers (id, kind, source_account_id, target_account_id, amount_minor, currency,
                                           status, created_at)
                    VALUES (:t, 'TRANSFER', :a, :f, 100, 'EUR', 'SETTLED', now())
                    """,
                        ).param("t", transfer)
                        .param("a", account.value)
                        .param("f", EUR_FUNDING.value)
                        .update()
                    ledger.jdbc
                        .sql(
                            """
                    INSERT INTO ledger_entries (id, account_id, transfer_id, direction, amount_minor, currency,
                                                balance_after_minor, account_version, posted_at)
                    VALUES (gen_random_uuid(), :a, :t, 'DEBIT', 100, 'EUR', 900, 99, now())
                    """,
                        ).param("t", transfer)
                        .param("a", account.value)
                        .update()
                }
            }
        error.rootCause?.message shouldContain "unbalanced"
    }
})
