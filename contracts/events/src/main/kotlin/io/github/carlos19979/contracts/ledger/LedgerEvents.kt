package io.github.carlos19979.contracts.ledger

import java.time.Instant
import java.util.UUID

object LedgerTopics {
    /** One message per ledger entry, keyed by account id: per-account order is preserved. */
    const val ENTRIES = "ledger.entries.v1"

    /** One message per settled or rejected transfer, keyed by source account id. */
    const val TRANSFERS = "ledger.transfers.v1"
}

/** Kafka record headers every ledger event carries, so consumers can route before parsing the payload. */
object EventHeaders {
    const val EVENT_ID = "event-id"
    const val EVENT_TYPE = "event-type"
    const val SCHEMA_VERSION = "schema-version"
}

object EventTypes {
    const val ENTRY_POSTED = "EntryPosted"
    const val TRANSFER_SETTLED = "TransferSettled"
    const val TRANSFER_REJECTED = "TransferRejected"
}

/**
 * Amounts travel in minor units with their ISO currency code, never as decimals or floats.
 * [accountVersion] increases by one with every entry of an account, so readers can order entries and detect gaps.
 */
data class EntryPostedV1(
    val eventId: UUID,
    val entryId: UUID,
    val accountId: UUID,
    val transferId: UUID,
    val direction: String,
    val amountMinor: Long,
    val currency: String,
    val balanceAfterMinor: Long,
    val accountVersion: Long,
    val postedAt: Instant,
)

/** Payload of both [EventTypes.TRANSFER_SETTLED] and [EventTypes.TRANSFER_REJECTED]. */
data class TransferCompletedV1(
    val eventId: UUID,
    val transferId: UUID,
    val kind: String,
    val sourceAccountId: UUID,
    val targetAccountId: UUID,
    val amountMinor: Long,
    val currency: String,
    val status: String,
    val rejectionCode: String?,
    val occurredAt: Instant,
)
