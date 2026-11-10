package io.github.carlos19979.ledger.adapter.kafka

import io.github.carlos19979.contracts.ledger.EntryPostedV1
import io.github.carlos19979.contracts.ledger.EventTypes
import io.github.carlos19979.contracts.ledger.LedgerTopics
import io.github.carlos19979.contracts.ledger.TransferCompletedV1
import io.github.carlos19979.ledger.application.port.EventEncoder
import io.github.carlos19979.ledger.application.port.OutboxMessage
import io.github.carlos19979.ledger.domain.LedgerEvent
import io.github.carlos19979.ledger.domain.Transfer
import tools.jackson.databind.json.JsonMapper

/** Translates domain events into the versioned public contract. The domain never sees these shapes. */
class JsonEventEncoder(private val json: JsonMapper) : EventEncoder {
    override fun encode(event: LedgerEvent): OutboxMessage =
        when (event) {
            is LedgerEvent.EntryPosted -> {
                val entry = event.entry
                message(
                    event = event,
                    topic = LedgerTopics.ENTRIES,
                    key = entry.accountId.toString(),
                    type = EventTypes.ENTRY_POSTED,
                    payload =
                        EntryPostedV1(
                            eventId = event.eventId.value,
                            entryId = entry.id.value,
                            accountId = entry.accountId.value,
                            transferId = entry.transferId.value,
                            direction = entry.direction.name,
                            amountMinor = entry.amount.minorUnits,
                            currency = entry.amount.currency.currencyCode,
                            balanceAfterMinor = entry.balanceAfter.minorUnits,
                            accountVersion = entry.accountVersion,
                            postedAt = entry.postedAt,
                        ),
                )
            }
            is LedgerEvent.TransferSettled -> transferMessage(event, event.transfer, EventTypes.TRANSFER_SETTLED)
            is LedgerEvent.TransferRejected -> transferMessage(event, event.transfer, EventTypes.TRANSFER_REJECTED)
        }

    private fun transferMessage(event: LedgerEvent, transfer: Transfer, type: String) =
        message(
            event = event,
            topic = LedgerTopics.TRANSFERS,
            key = transfer.sourceAccountId.toString(),
            type = type,
            payload =
                TransferCompletedV1(
                    eventId = event.eventId.value,
                    transferId = transfer.id.value,
                    kind = transfer.kind.name,
                    sourceAccountId = transfer.sourceAccountId.value,
                    targetAccountId = transfer.targetAccountId.value,
                    amountMinor = transfer.amount.minorUnits,
                    currency = transfer.amount.currency.currencyCode,
                    status = transfer.status.name,
                    rejectionCode = transfer.rejection?.code,
                    occurredAt = transfer.createdAt,
                ),
        )

    private fun message(event: LedgerEvent, topic: String, key: String, type: String, payload: Any) =
        OutboxMessage(
            eventId = event.eventId.value,
            topic = topic,
            key = key,
            eventType = type,
            schemaVersion = SCHEMA_VERSION,
            payload = json.writeValueAsString(payload),
            occurredAt = event.occurredAt,
        )

    private companion object {
        const val SCHEMA_VERSION = 1
    }
}
