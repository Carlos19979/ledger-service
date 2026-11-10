package io.github.carlos19979.ledger.domain

import java.time.Instant

enum class TransferKind {
    /** Customer to customer. */
    TRANSFER,

    /** From the bank's funding account into a customer account. */
    DEPOSIT,
}

/** Transfers are settled synchronously, so they are only ever stored in a terminal state. */
enum class TransferStatus { SETTLED, REJECTED }

/** Why a transfer was refused, in the form it is stored and reported. */
data class Rejection(val code: String, val detail: String)

data class Transfer(
    val id: TransferId,
    val kind: TransferKind,
    val sourceAccountId: AccountId,
    val targetAccountId: AccountId,
    val amount: Money,
    val reference: String?,
    val status: TransferStatus,
    val rejection: Rejection?,
    val createdAt: Instant,
) {
    init {
        require(amount.isPositive) { "Transfer amount must be positive" }
        require((status == TransferStatus.REJECTED) == (rejection != null)) { "Only rejected transfers have a reason" }
        require(reference == null || reference.length <= MAX_REFERENCE_LENGTH) { "Reference too long" }
    }

    companion object {
        const val MAX_REFERENCE_LENGTH = 140
    }
}
