package io.github.carlos19979.ledger.adapter.postgres

import io.github.carlos19979.ledger.application.account.OpenAccountCommand
import io.github.carlos19979.ledger.application.port.MessageBroker
import io.github.carlos19979.ledger.application.port.OutboxMessage
import io.github.carlos19979.ledger.application.transfer.DepositCommand
import io.github.carlos19979.ledger.domain.Money
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.util.Collections
import java.util.Currency
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class PostgresOutboxSpec : FunSpec({
    val ledger = PostgresLedger()
    val account = ledger.openAccount.execute(OpenAccountCommand("Ada", Currency.getInstance("EUR"), null)).id

    test("events are written with the transfer and published in commit order") {
        repeat(3) { ledger.transferMoney.deposit(DepositCommand("d$it", account, Money.of("1.00", "EUR"))) }
        ledger.outbox.pendingCount() shouldBe 9

        val received = mutableListOf<OutboxMessage>()
        ledger.relay { received += it }.publishPending() shouldBe 9

        received.map { it.eventType } shouldBe
            List(3) { listOf("EntryPosted", "EntryPosted", "TransferSettled") }.flatten()
        ledger.outbox.pendingCount() shouldBe 0
    }

    test("only one relay instance publishes at a time") {
        repeat(2) { ledger.transferMoney.deposit(DepositCommand("e$it", account, Money.of("1.00", "EUR"))) }
        val published = Collections.synchronizedList(mutableListOf<OutboxMessage>())
        val inside = CountDownLatch(1)
        val slowBroker =
            MessageBroker {
                inside.countDown()
                Thread.sleep(200)
                published += it
            }
        val pool = Executors.newFixedThreadPool(2)

        val slow = pool.submit<Int> { ledger.relay(slowBroker).publishPending() }
        inside.await()
        val second = ledger.relay { published += it }.publishPending()
        slow.get()
        pool.shutdown()

        second shouldBe 0
        published
            .groupBy { it.eventId }
            .filterValues { it.size > 1 }
            .keys
            .shouldBeEmpty()
        published.size shouldBe 6
    }

    test("a failed publish is recorded and retried later") {
        ledger.transferMoney.deposit(DepositCommand("f", account, Money.of("1.00", "EUR")))

        ledger.relay { error("kafka down") }.publishPending() shouldBe 0
        ledger.jdbc
            .sql(
                "SELECT max(attempts) FROM outbox WHERE published_at IS NULL",
            ).query(Int::class.java)
            .single() shouldBe
            1

        ledger.relay {}.publishPending() shouldBe 3
    }
})
