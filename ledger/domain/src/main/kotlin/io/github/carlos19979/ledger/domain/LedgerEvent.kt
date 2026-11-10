package io.github.carlos19979.ledger.domain

import java.time.Instant

/** Facts the ledger tells the rest of the bank about. Written to the outbox in the same transaction. */
sealed interface LedgerEvent {
    val eventId: EventId
    val occurredAt: Instant

    data class EntryPosted(override val eventId: EventId, val entry: LedgerEntry) : LedgerEvent {
        override val occurredAt: Instant get() = entry.postedAt
    }

    data class TransferSettled(override val eventId: EventId, val transfer: Transfer) : LedgerEvent {
        override val occurredAt: Instant get() = transfer.createdAt
    }

    data class TransferRejected(override val eventId: EventId, val transfer: Transfer) : LedgerEvent {
        override val occurredAt: Instant get() = transfer.createdAt
    }
}
