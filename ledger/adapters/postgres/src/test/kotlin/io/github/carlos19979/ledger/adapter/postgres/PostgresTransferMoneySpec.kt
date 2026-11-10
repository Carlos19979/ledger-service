package io.github.carlos19979.ledger.adapter.postgres

import io.github.carlos19979.ledger.application.account.OpenAccountCommand
import io.github.carlos19979.ledger.application.account.StatementResult
import io.github.carlos19979.ledger.application.transfer.DepositCommand
import io.github.carlos19979.ledger.application.transfer.TransferCommand
import io.github.carlos19979.ledger.application.transfer.TransferResult
import io.github.carlos19979.ledger.domain.AccountId
import io.github.carlos19979.ledger.domain.Money
import io.github.carlos19979.ledger.domain.TransferStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.Currency

class PostgresTransferMoneySpec : FunSpec({
    val ledger = PostgresLedger()
    val eur = Currency.getInstance("EUR")

    fun open(limit: Money? = null) = ledger.openAccount.execute(OpenAccountCommand("Ada", eur, limit)).id

    fun deposit(id: AccountId, amount: String, key: String = "dep-${java.util.UUID.randomUUID()}") =
        ledger.transferMoney.deposit(DepositCommand(key, id, Money.of(amount, "EUR")))

    test("round-trips accounts exactly as stored") {
        val id = open(limit = Money.of("250.00", "EUR"))

        val stored = ledger.accounts.findById(id).shouldNotBeNull()
        stored.dailyDebitLimit shouldBe Money.of("250.00", "EUR")
        stored.version shouldBe 0
    }

    test("a transfer persists the transfer, both entries and the new balances") {
        val from = open()
        val to = open()
        deposit(from, "100.00")

        val result =
            ledger.transferMoney
                .transfer(TransferCommand("t1", from, to, Money.of("40.00", "EUR"), "rent"))
                .shouldBeInstanceOf<TransferResult.Completed>()

        ledger.transfers.findById(result.transfer.id) shouldBe result.transfer
        ledger.accounts.findById(from)!!.balance shouldBe Money.of("60.00", "EUR")
        ledger.accounts.findById(to)!!.balance shouldBe Money.of("40.00", "EUR")
        ledger.accounts
            .findById(EUR_FUNDING)!!
            .balance.isNegative shouldBe true
        val statement = ledger.statement.execute(from, null, 10).shouldBeInstanceOf<StatementResult.Page>()
        statement.page.entries.map { it.balanceAfter } shouldBe listOf("60.00", "100.00").map { Money.of(it, "EUR") }
    }

    test("replaying a key returns the stored transfer") {
        val from = open()
        val to = open()
        deposit(from, "10.00")
        val command = TransferCommand("replay", from, to, Money.of("3.00", "EUR"), null)

        val first = ledger.transferMoney.transfer(command).shouldBeInstanceOf<TransferResult.Completed>()
        val second = ledger.transferMoney.transfer(command).shouldBeInstanceOf<TransferResult.Completed>()

        second.replayed shouldBe true
        second.transfer shouldBe first.transfer
    }

    test("rejections are stored with their reason") {
        val result =
            ledger.transferMoney
                .transfer(TransferCommand("r", open(), open(), Money.of("1.00", "EUR"), null))
                .shouldBeInstanceOf<TransferResult.Completed>()

        val stored = ledger.transfers.findById(result.transfer.id).shouldNotBeNull()
        stored.status shouldBe TransferStatus.REJECTED
        stored.rejection?.code shouldBe "insufficient-funds"
    }

    test("the statement pages with an opaque cursor") {
        val id = open()
        repeat(5) { deposit(id, "${it + 1}.00") }

        val first =
            ledger.statement
                .execute(id, null, 2)
                .shouldBeInstanceOf<StatementResult.Page>()
                .page
        val second =
            ledger.statement
                .execute(id, first.nextCursor, 2)
                .shouldBeInstanceOf<StatementResult.Page>()
                .page
        val third =
            ledger.statement
                .execute(id, second.nextCursor, 2)
                .shouldBeInstanceOf<StatementResult.Page>()
                .page

        (first.entries + second.entries + third.entries).map { it.amount.minorUnits } shouldBe
            listOf(500L, 400, 300, 200, 100)
        third.entries shouldHaveSize 1
        third.nextCursor shouldBe null
    }

    test("the daily limit reads today's debits from the database") {
        val from = open(limit = Money.of("50.00", "EUR"))
        val to = open()
        deposit(from, "200.00")
        ledger.transferMoney.transfer(TransferCommand("a", from, to, Money.of("30.00", "EUR"), null))

        ledger.transferMoney
            .transfer(TransferCommand("b", from, to, Money.of("30.00", "EUR"), null))
            .shouldBeInstanceOf<TransferResult.Completed>()
            .transfer.rejection
            ?.code shouldBe "daily-limit-exceeded"
    }
})
