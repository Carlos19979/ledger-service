package io.github.carlos19979.ledger.application

import io.github.carlos19979.ledger.application.account.AccountLookup
import io.github.carlos19979.ledger.application.account.OpenAccountCommand
import io.github.carlos19979.ledger.application.account.StatementResult
import io.github.carlos19979.ledger.application.account.StatusChange
import io.github.carlos19979.ledger.application.transfer.DepositCommand
import io.github.carlos19979.ledger.domain.AccountId
import io.github.carlos19979.ledger.domain.AccountStatus
import io.github.carlos19979.ledger.domain.Money
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.util.Currency

class AccountUseCasesSpec : FunSpec({
    val app = InMemoryUseCases(Clock.systemUTC())
    val eur = Currency.getInstance("EUR")

    test("unknown accounts are reported, not thrown") {
        val id = AccountId.new()
        app.getAccount.execute(id) shouldBe AccountLookup.NotFound(id)
        app.changeStatus.execute(id, StatusChange.BLOCK) shouldBe AccountLookup.NotFound(id)
        app.statement.execute(id, null, 10) shouldBe StatementResult.NotFound(id)
    }

    test("blocking and unblocking an account") {
        val id = app.openAccount.execute(OpenAccountCommand("Ada", eur, null)).id

        app.changeStatus
            .execute(id, StatusChange.BLOCK)
            .shouldBeInstanceOf<AccountLookup.Found>()
            .account.status shouldBe AccountStatus.BLOCKED
        app.changeStatus
            .execute(id, StatusChange.UNBLOCK)
            .shouldBeInstanceOf<AccountLookup.Found>()
            .account.status shouldBe AccountStatus.ACTIVE
    }

    test("the statement pages backwards through an account's entries") {
        val id = app.openAccount.execute(OpenAccountCommand("Ada", eur, null)).id
        repeat(5) { app.transferMoney.deposit(DepositCommand("d$it", id, Money.of("${it + 1}", "EUR"))) }

        val first =
            app.statement
                .execute(id, null, 3)
                .shouldBeInstanceOf<StatementResult.Page>()
                .page
        first.entries.map { it.amount } shouldBe listOf("5", "4", "3").map { Money.of(it, "EUR") }

        val second =
            app.statement
                .execute(id, first.nextCursor, 3)
                .shouldBeInstanceOf<StatementResult.Page>()
                .page
        second.entries shouldHaveSize 2
        second.nextCursor.shouldBeNull()
    }

    test("page size is bounded") {
        val id = app.openAccount.execute(OpenAccountCommand("Ada", eur, null)).id
        shouldThrow<IllegalArgumentException> { app.statement.execute(id, null, 0) }
    }
})
