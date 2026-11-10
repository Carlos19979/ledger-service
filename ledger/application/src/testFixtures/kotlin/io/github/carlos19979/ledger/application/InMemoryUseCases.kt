package io.github.carlos19979.ledger.application

import io.github.carlos19979.ledger.application.account.ChangeAccountStatus
import io.github.carlos19979.ledger.application.account.GetAccount
import io.github.carlos19979.ledger.application.account.GetStatement
import io.github.carlos19979.ledger.application.account.OpenAccount
import io.github.carlos19979.ledger.application.transfer.GetTransfer
import io.github.carlos19979.ledger.application.transfer.TransferMoney
import java.time.Clock

/** All use cases wired to one [InMemoryLedger], the same way the Spring app wires them to PostgreSQL. */
class InMemoryUseCases(val clock: Clock) {
    val store = InMemoryLedger(clock)
    val openAccount = OpenAccount(store.accounts, store.transactions, clock)
    val getAccount = GetAccount(store.accounts)
    val changeStatus = ChangeAccountStatus(store.accounts, store.transactions)
    val statement = GetStatement(store.accounts, store.entries)
    val transferMoney =
        TransferMoney(
            store.accounts,
            store.transfers,
            store.entries,
            store.idempotency,
            store.outbox,
            store.encoder,
            store.transactions,
            clock,
        )
    val getTransfer = GetTransfer(store.transfers)
}
