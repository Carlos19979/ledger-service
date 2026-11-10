package io.github.carlos19979.ledger.application

import io.github.carlos19979.ledger.application.account.OpenAccountCommand
import io.github.carlos19979.ledger.application.outbox.OutboxRelay
import io.github.carlos19979.ledger.application.transfer.DepositCommand
import io.github.carlos19979.ledger.domain.Money
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.util.Currency

class OutboxRelaySpec : FunSpec({
    fun appWithEvents(): InMemoryUseCases {
        val app = InMemoryUseCases(Clock.systemUTC())
        val id = app.openAccount.execute(OpenAccountCommand("Ada", Currency.getInstance("EUR"), null)).id
        repeat(2) { app.transferMoney.deposit(DepositCommand("d$it", id, Money.of("10", "EUR"))) } // 6 events
        return app
    }

    test("publishes pending messages in order and marks them as sent") {
        val app = appWithEvents()
        val broker = RecordingBroker()
        val relay = OutboxRelay(app.store.outbox, broker, app.store.transactions, Clock.systemUTC())

        relay.publishPending() shouldBe 6

        broker.received.map { it.eventId } shouldBe app.store.allOutbox.map { it.message.eventId }
        app.store.outbox.pendingCount() shouldBe 0
        relay.publishPending() shouldBe 0
    }

    test("stops at the first failure so nothing overtakes it, and resumes from there") {
        val app = appWithEvents()
        val broker = RecordingBroker().apply { failAfter = 2 }
        val relay = OutboxRelay(app.store.outbox, broker, app.store.transactions, Clock.systemUTC())

        relay.publishPending() shouldBe 2
        app.store.outbox.pendingCount() shouldBe 4
        app.store.allOutbox[2].attempts shouldBe 1

        broker.failAfter = Int.MAX_VALUE
        relay.publishPending() shouldBe 4
        broker.received.map { it.eventId } shouldBe app.store.allOutbox.map { it.message.eventId }
    }
})
