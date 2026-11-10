package io.github.carlos19979.ledger.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class LedgerSpec : FunSpec({
    test("a settled transfer moves money and produces two balancing entries") {
        val source = customer(eur("100.00"))
        val target = customer(eur("5.00"))

        val result = Ledger.post(request(source, target, eur("30.00")), source, target, eur("0.00"))

        result.shouldBeInstanceOf<PostingResult.Posted>()
        result.source.balance shouldBe eur("70.00")
        result.target.balance shouldBe eur("35.00")
        result.transfer.status shouldBe TransferStatus.SETTLED
        result.entries.map { it.direction } shouldBe listOf(EntryDirection.DEBIT, EntryDirection.CREDIT)
        result.entries.map { it.signedAmount }.reduce(Money::plus) shouldBe eur("0.00")
        result.entries.map { it.balanceAfter } shouldBe listOf(eur("70.00"), eur("35.00"))
        result.entries.map { it.accountVersion } shouldBe listOf(source.version + 1, target.version + 1)
    }

    test("a settlement publishes one event per entry plus the transfer") {
        val source = customer(eur("10.00"))
        val target = customer()

        val events = Ledger.post(request(source, target, eur("1.00")), source, target, eur("0.00")).events

        events shouldHaveSize 3
        events.last().shouldBeInstanceOf<LedgerEvent.TransferSettled>()
    }

    context("rejections leave both accounts untouched and say why") {
        fun rejectionOf(source: Account, target: Account, amount: Money, debitedToday: Money = eur("0.00")) =
            Ledger
                .post(request(source, target, amount), source, target, debitedToday)
                .shouldBeInstanceOf<PostingResult.Rejected>()

        test("insufficient funds") {
            val source = customer(eur("10.00"))
            val result = rejectionOf(source, customer(), eur("10.01"))

            result.reason.shouldBeInstanceOf<TransferRejection.InsufficientFunds>()
            result.transfer.rejection?.code shouldBe "insufficient-funds"
            result.events.single().shouldBeInstanceOf<LedgerEvent.TransferRejected>()
        }

        test("same account") {
            val account = customer(eur("10.00"))
            rejectionOf(account, account, eur("1.00")).reason shouldBe TransferRejection.SameAccount
        }

        test("currency mismatch on either side") {
            val dollars = customer(Money.of("10", "USD"))
            rejectionOf(customer(eur("10.00")), dollars, eur("1.00"))
                .reason
                .shouldBeInstanceOf<TransferRejection.CurrencyMismatch>()
        }

        test("blocked source or target") {
            val blocked = customer(eur("10.00")).block()
            rejectionOf(blocked, customer(), eur("1.00"))
                .reason
                .shouldBeInstanceOf<TransferRejection.AccountNotActive>()
            rejectionOf(customer(eur("10.00")), customer().block(), eur("1.00"))
                .reason
                .shouldBeInstanceOf<TransferRejection.AccountNotActive>()
        }

        test("daily limit counts what was already sent today") {
            val source = customer(eur("1000.00"), limit = eur("500.00"))

            rejectionOf(source, customer(), eur("200.01"), debitedToday = eur("300.00"))
                .reason
                .shouldBeInstanceOf<TransferRejection.DailyLimitExceeded>()
            val target = customer()
            Ledger
                .post(request(source, target, eur("200.00")), source, target, eur("300.00"))
                .shouldBeInstanceOf<PostingResult.Posted>()
        }
    }

    test("the funding account may go negative: it is the counterpart of every deposit") {
        val funding = Ledger.fundingAccount(AccountId.new(), EUR, NOW)
        val target = customer()

        val deposit = request(funding, target, eur("250.00"), TransferKind.DEPOSIT)

        val result = Ledger.post(deposit, funding, target, eur("0"))

        result.shouldBeInstanceOf<PostingResult.Posted>()
        result.source.balance shouldBe eur("-250.00")
    }
})
