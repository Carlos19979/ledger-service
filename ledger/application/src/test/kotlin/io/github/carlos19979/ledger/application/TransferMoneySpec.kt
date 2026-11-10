package io.github.carlos19979.ledger.application

import io.github.carlos19979.ledger.application.account.OpenAccountCommand
import io.github.carlos19979.ledger.application.transfer.DepositCommand
import io.github.carlos19979.ledger.application.transfer.TransferCommand
import io.github.carlos19979.ledger.application.transfer.TransferResult
import io.github.carlos19979.ledger.domain.AccountId
import io.github.carlos19979.ledger.domain.Money
import io.github.carlos19979.ledger.domain.TransferStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Currency

private val EUR = Currency.getInstance("EUR")

private fun eur(amount: String) = Money.of(amount, "EUR")

class TransferMoneySpec : FunSpec({
    lateinit var app: InMemoryUseCases

    beforeTest { app = InMemoryUseCases(Clock.fixed(Instant.parse("2026-11-10T09:30:00Z"), ZoneOffset.UTC)) }

    fun open(limit: Money? = null) = app.openAccount.execute(OpenAccountCommand("Ada", EUR, limit)).id

    fun fund(id: AccountId, amount: String) =
        app.transferMoney
            .deposit(DepositCommand("dep-$id-$amount", id, eur(amount)))
            .shouldBeInstanceOf<TransferResult.Completed>()

    fun send(key: String, from: AccountId, to: AccountId, amount: String, reference: String? = null) =
        app.transferMoney.transfer(TransferCommand(key, from, to, eur(amount), reference))

    test("a deposit credits the account from the funding account") {
        val account = open()

        fund(account, "100.00").transfer.status shouldBe TransferStatus.SETTLED

        app.getAccount
            .execute(
                account,
            ).shouldBeInstanceOf<io.github.carlos19979.ledger.application.account.AccountLookup.Found>()
            .account.balance shouldBe eur("100.00")
        app.store.accounts
            .findById(app.store.fundingEur.id)!!
            .balance shouldBe eur("-100.00")
    }

    test("a settled transfer writes two entries and three events in one go") {
        val from = open()
        val to = open()
        fund(from, "50.00")
        val outboxBefore = app.store.allOutbox.size

        val result = send("k1", from, to, "20.00").shouldBeInstanceOf<TransferResult.Completed>()

        result.transfer.status shouldBe TransferStatus.SETTLED
        app.store.allEntries.filter { it.transferId == result.transfer.id } shouldHaveSize 2
        app.store.allOutbox
            .drop(outboxBefore)
            .map { it.message.eventType } shouldBe
            listOf("EntryPosted", "EntryPosted", "TransferSettled")
    }

    test("a rejection is stored and published, and moves nothing") {
        val from = open()
        val to = open()

        val result = send("k1", from, to, "1.00").shouldBeInstanceOf<TransferResult.Completed>()

        result.transfer.status shouldBe TransferStatus.REJECTED
        result.transfer.rejection?.code shouldBe "insufficient-funds"
        app.getTransfer.execute(result.transfer.id) shouldBe result.transfer
        app.store.allEntries.shouldBeEmpty()
        app.store.allOutbox
            .last()
            .message.eventType shouldBe "TransferRejected"
    }

    context("idempotency") {
        test("retrying with the same key returns the original outcome and does not move money twice") {
            val from = open()
            val to = open()
            fund(from, "50.00")

            val first = send("same-key", from, to, "20.00").shouldBeInstanceOf<TransferResult.Completed>()
            val retry = send("same-key", from, to, "20.00").shouldBeInstanceOf<TransferResult.Completed>()

            retry.replayed shouldBe true
            retry.transfer shouldBe first.transfer
            app.store.accounts
                .findById(from)!!
                .balance shouldBe eur("30.00")
        }

        test("a rejected transfer replays as rejected even after funds arrive") {
            val from = open()
            val to = open()
            val first = send("k", from, to, "10.00").shouldBeInstanceOf<TransferResult.Completed>()
            fund(from, "100.00")

            val retry = send("k", from, to, "10.00").shouldBeInstanceOf<TransferResult.Completed>()

            retry.transfer shouldBe first.transfer
            retry.transfer.status shouldBe TransferStatus.REJECTED
        }

        test("reusing a key for a different request is refused") {
            val from = open()
            val to = open()
            fund(from, "50.00")
            send("k", from, to, "20.00")

            send("k", from, to, "20.01") shouldBe TransferResult.IdempotencyKeyReused
            send("k", from, to, "20.00", reference = "other") shouldBe TransferResult.IdempotencyKeyReused
        }
    }

    test("an unknown account rolls the whole attempt back, including the idempotency claim") {
        val from = open()
        fund(from, "50.00")
        val unknown = AccountId.new()

        send("k", from, unknown, "5.00") shouldBe TransferResult.AccountNotFound(unknown)

        // The key is free again: the same key can now be used for a valid transfer.
        val to = open()
        send("k", from, to, "5.00").shouldBeInstanceOf<TransferResult.Completed>().replayed shouldBe false
    }

    test("the daily limit counts debits since midnight UTC") {
        val from = open(limit = eur("100.00"))
        val to = open()
        fund(from, "500.00")
        send("a", from, to, "60.00")

        val second = send("b", from, to, "50.00").shouldBeInstanceOf<TransferResult.Completed>()

        second.transfer.rejection?.code shouldBe "daily-limit-exceeded"
    }

    test("deposits in a currency without a funding account are refused") {
        val account = open()

        app.transferMoney.deposit(DepositCommand("k", account, Money.of("1", "JPY"))) shouldBe
            TransferResult.UnsupportedCurrency(Currency.getInstance("JPY"))
    }
})
