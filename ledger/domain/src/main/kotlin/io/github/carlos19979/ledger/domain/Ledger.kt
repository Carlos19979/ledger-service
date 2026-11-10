package io.github.carlos19979.ledger.domain

import java.time.Instant

/** What the caller asks the ledger to do; ids are minted by the caller so the outcome is reproducible. */
data class TransferRequest(
    val id: TransferId,
    val kind: TransferKind,
    val sourceAccountId: AccountId,
    val targetAccountId: AccountId,
    val amount: Money,
    val reference: String?,
    val requestedAt: Instant,
) {
    init {
        require(amount.isPositive) { "Transfer amount must be positive" }
    }
}

sealed interface PostingResult {
    val transfer: Transfer
    val events: List<LedgerEvent>

    /** Money moved: both accounts changed and two balancing entries were produced. */
    data class Posted(
        override val transfer: Transfer,
        val source: Account,
        val target: Account,
        val entries: List<LedgerEntry>,
    ) : PostingResult {
        override val events: List<LedgerEvent>
            get() =
                entries.map { LedgerEvent.EntryPosted(EventId.new(), it) } +
                    LedgerEvent.TransferSettled(EventId.new(), transfer)
    }

    /** Nothing moved; the refusal itself is recorded. */
    data class Rejected(override val transfer: Transfer, val reason: TransferRejection) : PostingResult {
        override val events: List<LedgerEvent>
            get() = listOf(LedgerEvent.TransferRejected(EventId.new(), transfer))
    }
}

/**
 * Double-entry posting rules. Pure: given the locked accounts and how much the source already sent today, it decides
 * and computes the new state. Persisting that state atomically is the application layer's job.
 */
object Ledger {
    fun post(request: TransferRequest, source: Account, target: Account, debitedToday: Money): PostingResult {
        require(source.id == request.sourceAccountId && target.id == request.targetAccountId) {
            "Accounts do not match the request"
        }
        val rejection = check(request, source, target, debitedToday)
        if (rejection != null) {
            val rejected = transfer(request, TransferStatus.REJECTED, rejection.toRejection())
            return PostingResult.Rejected(rejected, rejection)
        }

        val debited = source.debit(request.amount)
        val credited = target.credit(request.amount)
        val transfer = transfer(request, TransferStatus.SETTLED, rejection = null)
        val entries =
            listOf(
                entry(request, debited, EntryDirection.DEBIT),
                entry(request, credited, EntryDirection.CREDIT),
            )
        return PostingResult.Posted(transfer, debited, credited, entries)
    }

    @Suppress("CyclomaticComplexMethod") // one branch per business rule, in evaluation order
    private fun check(request: TransferRequest, source: Account, target: Account, debitedToday: Money) =
        when {
            source.id == target.id -> TransferRejection.SameAccount
            request.amount.currency != source.currency ->
                TransferRejection.CurrencyMismatch(source.id, source.currency, request.amount.currency)
            request.amount.currency != target.currency ->
                TransferRejection.CurrencyMismatch(target.id, target.currency, request.amount.currency)
            source.status != AccountStatus.ACTIVE -> TransferRejection.AccountNotActive(source.id, source.status)
            target.status != AccountStatus.ACTIVE -> TransferRejection.AccountNotActive(target.id, target.status)
            !source.allowsOverdraft && source.balance < request.amount ->
                TransferRejection.InsufficientFunds(source.id, source.balance, request.amount)
            source.dailyDebitLimit != null && debitedToday + request.amount > source.dailyDebitLimit ->
                TransferRejection.DailyLimitExceeded(source.id, source.dailyDebitLimit, debitedToday, request.amount)
            else -> null
        }

    private fun transfer(request: TransferRequest, status: TransferStatus, rejection: Rejection?) =
        Transfer(
            id = request.id,
            kind = request.kind,
            sourceAccountId = request.sourceAccountId,
            targetAccountId = request.targetAccountId,
            amount = request.amount,
            reference = request.reference,
            status = status,
            rejection = rejection,
            createdAt = request.requestedAt,
        )

    private fun entry(request: TransferRequest, accountAfter: Account, direction: EntryDirection) =
        LedgerEntry(
            id = EntryId.new(),
            accountId = accountAfter.id,
            transferId = request.id,
            direction = direction,
            amount = request.amount,
            balanceAfter = accountAfter.balance,
            accountVersion = accountAfter.version,
            postedAt = request.requestedAt,
        )

    /** Funding accounts are created by migration, but tests and tools need the same shape. */
    fun fundingAccount(id: AccountId, currency: java.util.Currency, at: Instant): Account =
        Account.rehydrate(
            id = id,
            owner = "Funding account (${currency.currencyCode})",
            type = AccountType.INTERNAL,
            currency = currency,
            balance = Money.zero(currency),
            status = AccountStatus.ACTIVE,
            dailyDebitLimit = null,
            version = 0,
            openedAt = at,
        )
}
