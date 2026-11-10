package io.github.carlos19979.ledger.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AccountSpec : FunSpec({
    test("a new customer account is active, empty and at version 0") {
        val account = Account.open("  Grace Hopper ", EUR, eur("1000.00"), NOW)

        account.owner shouldBe "Grace Hopper"
        account.balance shouldBe eur("0.00")
        account.status shouldBe AccountStatus.ACTIVE
        account.version shouldBe 0
    }

    test("every change bumps the version") {
        val account = customer(eur("10.00"))

        account.block().version shouldBe account.version + 1
        account.block().unblock().status shouldBe AccountStatus.ACTIVE
    }

    test("customer balances can never be negative, even when rebuilt from storage") {
        shouldThrow<IllegalArgumentException> { customer(eur("-0.01")) }
    }

    test("the daily limit must be positive and in the account currency") {
        shouldThrow<IllegalArgumentException> { Account.open("Ada", EUR, eur("0.00"), NOW) }
        shouldThrow<IllegalArgumentException> { Account.open("Ada", EUR, Money.of("10", "USD"), NOW) }
    }

    test("owner names are required and bounded") {
        shouldThrow<IllegalArgumentException> { Account.open(" ", EUR, null, NOW) }
        shouldThrow<IllegalArgumentException> { Account.open("x".repeat(Account.MAX_OWNER_LENGTH + 1), EUR, null, NOW) }
    }
})
