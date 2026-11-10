package io.github.carlos19979.ledger.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.triple
import io.kotest.property.checkAll

/**
 * Whatever sequence of deposits and transfers happens, double-entry invariants hold:
 * money is never created or destroyed, customers never go negative, and every balance equals the sum of its entries.
 */
class LedgerPropertySpec : FunSpec({
    test("random operations preserve the ledger invariants") {
        val operation = Arb.triple(Arb.int(0..4), Arb.int(0..4), Arb.long(1L..50_000L))

        checkAll(300, Arb.list(operation, 1..60)) { operations ->
            val funding = Ledger.fundingAccount(AccountId.new(), EUR, NOW)
            val accounts =
                (0..4)
                    .map { Account.open("Customer $it", EUR, eur("1000.00"), NOW) }
                    .associateBy { it.id }
                    .toMutableMap()
            accounts[funding.id] = funding
            val customers = accounts.keys.filter { it != funding.id }
            val entries = mutableListOf<LedgerEntry>()

            operations.forEachIndexed { index, (from, to, minor) ->
                // Every fifth operation is a deposit so there is money to move around.
                val sourceId = if (index % 5 == 0) funding.id else customers[from]
                val targetId = customers[to]
                val req =
                    TransferRequest(
                        TransferId.new(),
                        if (sourceId == funding.id) TransferKind.DEPOSIT else TransferKind.TRANSFER,
                        sourceId,
                        targetId,
                        Money(minor, EUR),
                        null,
                        NOW,
                    )
                val sentToday =
                    entries
                        .filter { it.accountId == sourceId && it.direction == EntryDirection.DEBIT }
                        .fold(Money.zero(EUR)) { acc, e -> acc + e.amount }
                val result = Ledger.post(req, accounts.getValue(sourceId), accounts.getValue(targetId), sentToday)
                if (result is PostingResult.Posted) {
                    accounts[result.source.id] = result.source
                    accounts[result.target.id] = result.target
                    entries += result.entries
                }
            }

            accounts.values.fold(Money.zero(EUR)) { acc, a -> acc + a.balance } shouldBe Money.zero(EUR)
            accounts.values.filter { !it.allowsOverdraft }.all { !it.balance.isNegative } shouldBe true
            accounts.values.forEach { account ->
                val fromEntries =
                    entries
                        .filter { it.accountId == account.id }
                        .fold(Money.zero(EUR)) { acc, e -> acc + e.signedAmount }
                fromEntries shouldBe account.balance
            }
        }
    }
})
