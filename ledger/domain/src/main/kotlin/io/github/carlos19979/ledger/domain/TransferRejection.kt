package io.github.carlos19979.ledger.domain

import java.util.Currency

/**
 * Business reasons a transfer is refused. A sealed hierarchy instead of exceptions: callers must handle every case,
 * and a rejection is a normal, auditable outcome that gets stored and published like a settlement.
 */
sealed interface TransferRejection {
    val code: String
    val detail: String

    fun toRejection(): Rejection = Rejection(code, detail)

    data object SameAccount : TransferRejection {
        override val code = "same-account"
        override val detail = "Source and target accounts must differ"
    }

    data class CurrencyMismatch(val accountId: AccountId, val accountCurrency: Currency, val amountCurrency: Currency) :
        TransferRejection {
        override val code = "currency-mismatch"
        override val detail get() = "Account $accountId holds $accountCurrency, transfer is in $amountCurrency"
    }

    data class AccountNotActive(val accountId: AccountId, val status: AccountStatus) : TransferRejection {
        override val code = "account-not-active"
        override val detail get() = "Account $accountId is $status"
    }

    data class InsufficientFunds(val accountId: AccountId, val available: Money, val requested: Money) :
        TransferRejection {
        override val code = "insufficient-funds"
        override val detail get() = "Account $accountId has $available available, $requested requested"
    }

    data class DailyLimitExceeded(
        val accountId: AccountId,
        val limit: Money,
        val debitedToday: Money,
        val requested: Money,
    ) : TransferRejection {
        override val code = "daily-limit-exceeded"
        override val detail get() = "Account $accountId limit is $limit per day, $debitedToday already sent"
    }
}
