package io.github.carlos19979.ledger.application.port

import io.github.carlos19979.ledger.domain.Account
import io.github.carlos19979.ledger.domain.AccountId
import io.github.carlos19979.ledger.domain.LedgerEntry
import io.github.carlos19979.ledger.domain.LedgerEvent
import io.github.carlos19979.ledger.domain.Money
import io.github.carlos19979.ledger.domain.Transfer
import io.github.carlos19979.ledger.domain.TransferId
import java.time.Instant
import java.util.Currency
import java.util.UUID

/** Runs a block atomically. Everything a use case writes inside it commits or rolls back together. */
interface TransactionRunner {
    fun <T> inTransaction(block: () -> T): T
}

interface AccountRepository {
    fun insert(account: Account)

    fun findById(id: AccountId): Account?

    /**
     * Locks the given accounts for the rest of the transaction, always in ascending id order so two transfers
     * between the same pair of accounts in opposite directions cannot deadlock. Missing ids are simply absent.
     */
    fun lockForUpdate(ids: Set<AccountId>): Map<AccountId, Account>

    /** Persists [account]; fails if the stored version is not [expectedVersion]. */
    fun update(account: Account, expectedVersion: Long)

    fun fundingAccountFor(currency: Currency): AccountId?
}

interface TransferRepository {
    fun insert(transfer: Transfer)

    fun findById(id: TransferId): Transfer?
}

/** One page of an account statement, newest first. */
data class StatementPage(val entries: List<LedgerEntry>, val nextCursor: Long?)

interface LedgerEntryRepository {
    fun insertAll(entries: List<LedgerEntry>)

    fun debitedSince(accountId: AccountId, since: Instant, currency: Currency): Money

    /** [before] is an opaque position from a previous page; null starts from the most recent entry. */
    fun statement(accountId: AccountId, before: Long?, limit: Int): StatementPage
}

sealed interface IdempotencyClaim {
    /** First time this key is seen: the caller owns it and must complete the operation in this transaction. */
    data object Claimed : IdempotencyClaim

    /** The key was used before, possibly by a concurrent request that has since committed. */
    data class AlreadyUsed(val requestHash: String, val transferId: TransferId) : IdempotencyClaim
}

interface IdempotencyStore {
    /**
     * Atomically reserves [key] in [scope] for [transferId]. If another transaction holds the same key, this waits
     * for it to finish, so a concurrent duplicate sees the winner's result instead of racing it.
     */
    fun claim(scope: String, key: String, requestHash: String, transferId: TransferId): IdempotencyClaim
}

/** A ledger event serialised for the outside world: where it goes, how it is keyed, and its payload. */
data class OutboxMessage(
    val eventId: UUID,
    val topic: String,
    val key: String,
    val eventType: String,
    val schemaVersion: Int,
    val payload: String,
    val occurredAt: Instant,
)

/** Maps domain events onto the published contract. Implemented next to the broker that understands it. */
fun interface EventEncoder {
    fun encode(event: LedgerEvent): OutboxMessage
}

data class PendingMessage(val id: Long, val message: OutboxMessage, val attempts: Int)

interface OutboxStore {
    fun append(messages: List<OutboxMessage>)

    /**
     * Returns the oldest unpublished messages, or nothing if another relay instance is already publishing. Only one
     * publisher at a time keeps per-key ordering intact across instances.
     */
    fun lockPendingBatch(limit: Int): List<PendingMessage>

    fun markPublished(ids: List<Long>, at: Instant)

    fun recordFailure(id: Long, error: String)

    fun pendingCount(): Long
}

/** Delivers one message and returns only once the broker acknowledged it. Throws on failure. */
fun interface MessageBroker {
    fun publish(message: OutboxMessage)
}
