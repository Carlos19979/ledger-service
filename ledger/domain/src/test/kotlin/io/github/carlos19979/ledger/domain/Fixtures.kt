package io.github.carlos19979.ledger.domain

import java.time.Instant
import java.util.Currency

val EUR: Currency = Currency.getInstance("EUR")
val USD: Currency = Currency.getInstance("USD")
val NOW: Instant = Instant.parse("2026-11-10T10:00:00Z")

fun eur(amount: String): Money = Money.of(amount, "EUR")

fun customer(balance: Money = eur("0.00"), limit: Money? = null, currency: Currency = balance.currency): Account =
    Account.rehydrate(
        id = AccountId.new(),
        owner = "Ada Lovelace",
        type = AccountType.CUSTOMER,
        currency = currency,
        balance = balance,
        status = AccountStatus.ACTIVE,
        dailyDebitLimit = limit,
        version = 3,
        openedAt = NOW,
    )

fun request(source: Account, target: Account, amount: Money, kind: TransferKind = TransferKind.TRANSFER) =
    TransferRequest(TransferId.new(), kind, source.id, target.id, amount, "rent", NOW)
