package io.github.carlos19979.ledger.domain

import java.time.Instant

enum class EntryDirection { DEBIT, CREDIT }

/**
 * One side of a double-entry posting. Entries are append-only: a mistake is corrected by a new transfer, never by
 * editing history. [accountVersion] is the account's version after this entry, which lets downstream readers order
 * an account's entries without relying on delivery order.
 */
data class LedgerEntry(
    val id: EntryId,
    val accountId: AccountId,
    val transferId: TransferId,
    val direction: EntryDirection,
    val amount: Money,
    val balanceAfter: Money,
    val accountVersion: Long,
    val postedAt: Instant,
) {
    init {
        require(amount.isPositive) { "Entry amount must be positive; direction carries the sign" }
        require(balanceAfter.currency == amount.currency) { "Entry currencies must match" }
    }

    /** The entry's effect on its account's balance. */
    val signedAmount: Money get() = if (direction == EntryDirection.CREDIT) amount else -amount
}
