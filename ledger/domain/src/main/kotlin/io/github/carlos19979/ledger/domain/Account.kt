package io.github.carlos19979.ledger.domain

import java.time.Instant
import java.util.Currency

enum class AccountType {
    /** A customer's account: can never go below zero. */
    CUSTOMER,

    /** The bank's own funding account for a currency: the counterpart of every deposit, so it runs negative. */
    INTERNAL,
}

enum class AccountStatus { ACTIVE, BLOCKED }

/**
 * Account aggregate. Immutable: every change returns a new instance with [version] bumped, which persistence uses as
 * an optimistic-concurrency guard on top of the row lock taken by the transfer use case.
 */
class Account private constructor(
    val id: AccountId,
    val owner: String,
    val type: AccountType,
    val currency: Currency,
    val balance: Money,
    val status: AccountStatus,
    val dailyDebitLimit: Money?,
    val version: Long,
    val openedAt: Instant,
) {
    init {
        require(owner.isNotBlank() && owner.length <= MAX_OWNER_LENGTH) { "Owner must be 1..$MAX_OWNER_LENGTH chars" }
        require(balance.currency == currency) { "Balance must be in the account currency" }
        require(type == AccountType.INTERNAL || !balance.isNegative) { "Customer balance cannot be negative" }
        dailyDebitLimit?.let {
            require(it.currency == currency && it.isPositive) { "Daily limit must be positive and in $currency" }
        }
    }

    val allowsOverdraft: Boolean get() = type == AccountType.INTERNAL

    internal fun debit(amount: Money): Account = copy(balance = balance - amount, version = version + 1)

    internal fun credit(amount: Money): Account = copy(balance = balance + amount, version = version + 1)

    fun block(): Account = copy(status = AccountStatus.BLOCKED, version = version + 1)

    fun unblock(): Account = copy(status = AccountStatus.ACTIVE, version = version + 1)

    private fun copy(
        balance: Money = this.balance,
        status: AccountStatus = this.status,
        version: Long = this.version,
    ) = Account(id, owner, type, currency, balance, status, dailyDebitLimit, version, openedAt)

    override fun equals(other: Any?): Boolean = other is Account && other.id == id && other.version == version

    override fun hashCode(): Int = 31 * id.hashCode() + version.hashCode()

    override fun toString(): String = "Account($id, $type, $balance, $status, v$version)"

    companion object {
        const val MAX_OWNER_LENGTH = 200

        fun open(
            owner: String,
            currency: Currency,
            dailyDebitLimit: Money?,
            openedAt: Instant,
            id: AccountId = AccountId.new(),
        ): Account =
            Account(
                id = id,
                owner = owner.trim(),
                type = AccountType.CUSTOMER,
                currency = currency,
                balance = Money.zero(currency),
                status = AccountStatus.ACTIVE,
                dailyDebitLimit = dailyDebitLimit,
                version = 0,
                openedAt = openedAt,
            )

        /** Rebuilds an account from storage; validation still runs, so corrupt rows fail loudly. */
        fun rehydrate(
            id: AccountId,
            owner: String,
            type: AccountType,
            currency: Currency,
            balance: Money,
            status: AccountStatus,
            dailyDebitLimit: Money?,
            version: Long,
            openedAt: Instant,
        ): Account = Account(id, owner, type, currency, balance, status, dailyDebitLimit, version, openedAt)
    }
}
