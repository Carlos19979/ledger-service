package io.github.carlos19979.ledger.adapter.postgres

import io.github.carlos19979.ledger.application.account.OpenAccountCommand
import io.github.carlos19979.ledger.application.port.IdempotencyClaim
import io.github.carlos19979.ledger.application.transfer.DepositCommand
import io.github.carlos19979.ledger.application.transfer.TransferCommand
import io.github.carlos19979.ledger.application.transfer.TransferResult
import io.github.carlos19979.ledger.domain.Money
import io.github.carlos19979.ledger.domain.TransferId
import io.github.carlos19979.ledger.domain.TransferStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.util.Currency
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Hammers the real database with parallel transfers between a handful of accounts, in both directions, to prove
 * that the locking strategy neither deadlocks nor loses or creates money.
 */
class ConcurrencySpec : FunSpec({
    val ledger = PostgresLedger()
    val eur = Currency.getInstance("EUR")

    test("parallel transfers keep the books balanced, never overdraw and never deadlock") {
        val accounts = (1..6).map { ledger.openAccount.execute(OpenAccountCommand("Customer $it", eur, null)).id }
        accounts.forEach { ledger.transferMoney.deposit(DepositCommand("seed-$it", it, Money.of("1000.00", "EUR"))) }

        val random = Random(42)
        val commands =
            (1..600).map { n ->
                val from = accounts.random(random)
                val to = (accounts - from).random(random)
                TransferCommand("tx-$n", from, to, Money(random.nextLong(1, 40_000), eur), null)
            }

        val pool = Executors.newFixedThreadPool(16)
        val start = CountDownLatch(1)
        val results =
            try {
                commands
                    .map { command ->
                        pool.submit(
                            Callable {
                                start.await()
                                runCatching { ledger.transferMoney.transfer(command) }
                            },
                        )
                    }.also { start.countDown() }
                    .map { it.get() }
            } finally {
                pool.shutdown()
            }

        results.mapNotNull { it.exceptionOrNull() }.shouldBeEmpty()
        val completed = results.map { it.getOrThrow() as TransferResult.Completed }
        completed.count { it.transfer.status == TransferStatus.SETTLED }.toLong() shouldBeGreaterThan 100

        // Money is conserved: customers plus the funding account always sum to zero.
        ledger.jdbc
            .sql("SELECT sum(balance_minor) FROM accounts")
            .query(Long::class.java)
            .single() shouldBe 0
        ledger.jdbc
            .sql("SELECT count(*) FROM accounts WHERE type = 'CUSTOMER' AND balance_minor < 0")
            .query(Long::class.java)
            .single() shouldBe 0
        // Every balance equals the sum of its entries.
        ledger.jdbc
            .sql(
                """
            SELECT count(*) FROM accounts a
            WHERE a.balance_minor <> (SELECT coalesce(sum(CASE e.direction WHEN 'CREDIT' THEN e.amount_minor
                                                                           ELSE -e.amount_minor END), 0)
                                      FROM ledger_entries e WHERE e.account_id = a.id)
            """,
            ).query(Long::class.java)
            .single() shouldBe 0
    }

    test("concurrent requests with the same idempotency key move money once") {
        val from = ledger.openAccount.execute(OpenAccountCommand("Payer", eur, null)).id
        val to = ledger.openAccount.execute(OpenAccountCommand("Payee", eur, null)).id
        ledger.transferMoney.deposit(DepositCommand("seed-dup", from, Money.of("100.00", "EUR")))
        val command = TransferCommand("double-click", from, to, Money.of("25.00", "EUR"), null)

        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val results =
            try {
                (1..8)
                    .map {
                        pool.submit(
                            Callable {
                                start.await()
                                ledger.transferMoney.transfer(command)
                            },
                        )
                    }.also { start.countDown() }
                    .map { it.get() as TransferResult.Completed }
            } finally {
                pool.shutdown()
            }

        results.map { it.transfer.id }.toSet().size shouldBe 1
        results.map { it.replayed } shouldContainExactlyInAnyOrder listOf(false) + List(7) { true }
        ledger.accounts.findById(to)!!.balance shouldBe Money.of("25.00", "EUR")
    }

    test("a key claimed by an uncommitted transaction makes the second claimer wait, then replay") {
        val first = TransferId.new()
        val latch = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        val account = ledger.openAccount.execute(OpenAccountCommand("Ada", eur, null)).id

        val holder =
            pool.submit {
                ledger.tx.inTransaction {
                    ledger.idempotency.claim("TEST", "held", "h", first)
                    latch.countDown()
                    Thread.sleep(300)
                    // Satisfy the deferred foreign key so the claim can commit.
                    ledger.jdbc
                        .sql(
                            """
                            INSERT INTO transfers
                            VALUES (:t, 'DEPOSIT', :f, :a, 1, 'EUR', null, 'REJECTED', 'x', 'x', now())
                            """,
                        ).param("t", first.value)
                        .param("f", EUR_FUNDING.value)
                        .param("a", account.value)
                        .update()
                }
            }
        latch.await()
        val second = ledger.tx.inTransaction { ledger.idempotency.claim("TEST", "held", "h", TransferId.new()) }
        holder.get()
        pool.shutdown()

        second shouldBe IdempotencyClaim.AlreadyUsed("h", first)
    }
})
