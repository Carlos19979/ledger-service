package io.github.carlos19979.ledger.application.account

import io.github.carlos19979.ledger.application.port.AccountRepository
import io.github.carlos19979.ledger.application.port.LedgerEntryRepository
import io.github.carlos19979.ledger.application.port.StatementPage
import io.github.carlos19979.ledger.application.port.TransactionRunner
import io.github.carlos19979.ledger.domain.Account
import io.github.carlos19979.ledger.domain.AccountId
import io.github.carlos19979.ledger.domain.Money
import java.time.Clock
import java.util.Currency

data class OpenAccountCommand(val owner: String, val currency: Currency, val dailyDebitLimit: Money?)

class OpenAccount(
    private val accounts: AccountRepository,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {
    fun execute(command: OpenAccountCommand): Account {
        val account = Account.open(command.owner, command.currency, command.dailyDebitLimit, clock.instant())
        tx.inTransaction { accounts.insert(account) }
        return account
    }
}

sealed interface AccountLookup {
    data class Found(val account: Account) : AccountLookup

    data class NotFound(val id: AccountId) : AccountLookup
}

class GetAccount(private val accounts: AccountRepository) {
    fun execute(id: AccountId): AccountLookup =
        accounts.findById(id)?.let(AccountLookup::Found) ?: AccountLookup.NotFound(id)
}

enum class StatusChange { BLOCK, UNBLOCK }

/** Blocking stops money leaving or entering the account; history is untouched. */
class ChangeAccountStatus(private val accounts: AccountRepository, private val tx: TransactionRunner) {
    fun execute(id: AccountId, change: StatusChange): AccountLookup =
        tx.inTransaction {
            val account = accounts.lockForUpdate(setOf(id))[id] ?: return@inTransaction AccountLookup.NotFound(id)
            val changed =
                when (change) {
                    StatusChange.BLOCK -> account.block()
                    StatusChange.UNBLOCK -> account.unblock()
                }
            accounts.update(changed, account.version)
            AccountLookup.Found(changed)
        }
}

sealed interface StatementResult {
    data class Page(val page: StatementPage) : StatementResult

    data class NotFound(val id: AccountId) : StatementResult
}

class GetStatement(private val accounts: AccountRepository, private val entries: LedgerEntryRepository) {
    fun execute(id: AccountId, before: Long?, limit: Int): StatementResult {
        require(limit in 1..MAX_PAGE_SIZE) { "limit must be 1..$MAX_PAGE_SIZE" }
        accounts.findById(id) ?: return StatementResult.NotFound(id)
        return StatementResult.Page(entries.statement(id, before, limit))
    }

    companion object {
        const val MAX_PAGE_SIZE = 200
    }
}
