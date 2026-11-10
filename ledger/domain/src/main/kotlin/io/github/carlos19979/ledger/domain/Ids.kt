package io.github.carlos19979.ledger.domain

import java.util.UUID

@JvmInline
value class AccountId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): AccountId = AccountId(UUID.randomUUID())
    }
}

@JvmInline
value class TransferId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): TransferId = TransferId(UUID.randomUUID())
    }
}

@JvmInline
value class EntryId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): EntryId = EntryId(UUID.randomUUID())
    }
}

@JvmInline
value class EventId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): EventId = EventId(UUID.randomUUID())
    }
}
