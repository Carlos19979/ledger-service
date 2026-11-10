package io.github.carlos19979.ledger.application

import io.github.carlos19979.ledger.application.port.AccountRepository
import io.github.carlos19979.ledger.application.port.EventEncoder
import io.github.carlos19979.ledger.application.port.IdempotencyClaim
import io.github.carlos19979.ledger.application.port.IdempotencyStore
import io.github.carlos19979.ledger.application.port.LedgerEntryRepository
import io.github.carlos19979.ledger.application.port.MessageBroker
import io.github.carlos19979.ledger.application.port.OutboxMessage
import io.github.carlos19979.ledger.application.port.OutboxStore
import io.github.carlos19979.ledger.application.port.PendingMessage
import io.github.carlos19979.ledger.application.port.StatementPage
import io.github.carlos19979.ledger.application.port.TransactionRunner
import io.github.carlos19979.ledger.application.port.TransferRepository
import io.github.carlos19979.ledger.domain.Account
import io.github.carlos19979.ledger.domain.AccountId
import io.github.carlos19979.ledger.domain.EntryDirection
import io.github.carlos19979.ledger.domain.Ledger
import io.github.carlos19979.ledger.domain.LedgerEntry
import io.github.carlos19979.ledger.domain.LedgerEvent
import io.github.carlos19979.ledger.domain.Money
import io.github.carlos19979.ledger.domain.Transfer
import io.github.carlos19979.ledger.domain.TransferId
import java.time.Clock
import java.time.Instant
import java.util.Currency

/**
 * Every port backed by plain collections, with real rollback: a transaction snapshots the state and restores it if
 * the block throws. Single-threaded by design; concurrency is tested against PostgreSQL.
 */
class InMemoryLedger(clock: Clock) {
    private var state = State()

    private data class State(
        val accounts: Map<AccountId, Account> = emptyMap(),
        val transfers: Map<TransferId, Transfer> = emptyMap(),
        val entries: List<LedgerEntry> = emptyList(),
        val keys: Map<Pair<String, String>, Pair<String, TransferId>> = emptyMap(),
        val outbox: List<PendingMessage> = emptyList(),
        val published: Set<Long> = emptySet(),
    )

    val fundingEur: Account = Ledger.fundingAccount(AccountId.new(), Currency.getInstance("EUR"), clock.instant())

    init {
        state = state.copy(accounts = mapOf(fundingEur.id to fundingEur))
    }

    val transactions =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                val snapshot = state
                return try {
                    block()
                } catch (e: Throwable) {
                    state = snapshot
                    throw e
                }
            }
        }

    val accounts =
        object : AccountRepository {
            override fun insert(account: Account) {
                check(account.id !in state.accounts) { "Duplicate account ${account.id}" }
                state = state.copy(accounts = state.accounts + (account.id to account))
            }

            override fun findById(id: AccountId) = state.accounts[id]

            override fun lockForUpdate(ids: Set<AccountId>) =
                ids
                    .mapNotNull {
                        state.accounts[it]
                    }.associateBy { it.id }

            override fun update(account: Account, expectedVersion: Long) {
                val current = checkNotNull(state.accounts[account.id]) { "Unknown account ${account.id}" }
                check(current.version == expectedVersion) { "Stale version for ${account.id}" }
                state = state.copy(accounts = state.accounts + (account.id to account))
            }

            override fun fundingAccountFor(currency: Currency) =
                state.accounts.values
                    .firstOrNull { it.allowsOverdraft && it.currency == currency }
                    ?.id
        }

    val transfers =
        object : TransferRepository {
            override fun insert(transfer: Transfer) {
                check(transfer.id !in state.transfers) { "Duplicate transfer ${transfer.id}" }
                state = state.copy(transfers = state.transfers + (transfer.id to transfer))
            }

            override fun findById(id: TransferId) = state.transfers[id]
        }

    val entries =
        object : LedgerEntryRepository {
            override fun insertAll(entries: List<LedgerEntry>) {
                state = state.copy(entries = state.entries + entries)
            }

            override fun debitedSince(accountId: AccountId, since: Instant, currency: Currency) =
                state.entries
                    .filter {
                        it.accountId == accountId &&
                            it.direction == EntryDirection.DEBIT &&
                            !it.postedAt.isBefore(
                                since,
                            )
                    }.fold(Money.zero(currency)) { acc, e -> acc + e.amount }

            override fun statement(accountId: AccountId, before: Long?, limit: Int): StatementPage {
                val indexed =
                    state.entries
                        .withIndex()
                        .filter { it.value.accountId == accountId }
                        .reversed()
                        .filter { before == null || it.index < before }
                val page = indexed.take(limit)
                val next = if (indexed.size > limit) page.last().index.toLong() else null
                return StatementPage(page.map { it.value }, next)
            }
        }

    val idempotency =
        object : IdempotencyStore {
            override fun claim(
                scope: String,
                key: String,
                requestHash: String,
                transferId: TransferId,
            ): IdempotencyClaim {
                val existing = state.keys[scope to key]
                if (existing != null) return IdempotencyClaim.AlreadyUsed(existing.first, existing.second)
                state = state.copy(keys = state.keys + ((scope to key) to (requestHash to transferId)))
                return IdempotencyClaim.Claimed
            }
        }

    val outbox =
        object : OutboxStore {
            override fun append(messages: List<OutboxMessage>) {
                val start = state.outbox.size.toLong()
                val pending = messages.mapIndexed { i, m -> PendingMessage(start + i + 1, m, attempts = 0) }
                state = state.copy(outbox = state.outbox + pending)
            }

            override fun lockPendingBatch(limit: Int) = state.outbox.filter { it.id !in state.published }.take(limit)

            override fun markPublished(ids: List<Long>, at: Instant) {
                state = state.copy(published = state.published + ids)
            }

            override fun recordFailure(id: Long, error: String) {
                state =
                    state.copy(
                        outbox =
                            state.outbox.map {
                                if (it.id ==
                                    id
                                ) {
                                    it.copy(attempts = it.attempts + 1)
                                } else {
                                    it
                                }
                            },
                    )
            }

            override fun pendingCount() = state.outbox.count { it.id !in state.published }.toLong()
        }

    val allEntries: List<LedgerEntry> get() = state.entries
    val allOutbox: List<PendingMessage> get() = state.outbox

    /** Encodes events with their type and key so tests can assert on what would be published. */
    val encoder =
        EventEncoder { event ->
            val (type, key) =
                when (event) {
                    is LedgerEvent.EntryPosted -> "EntryPosted" to event.entry.accountId.toString()
                    is LedgerEvent.TransferSettled -> "TransferSettled" to event.transfer.sourceAccountId.toString()
                    is LedgerEvent.TransferRejected -> "TransferRejected" to event.transfer.sourceAccountId.toString()
                }
            OutboxMessage(event.eventId.value, "test-topic", key, type, 1, event.toString(), event.occurredAt)
        }
}

/** A broker that records what it receives and can be told to fail. */
class RecordingBroker : MessageBroker {
    val received = mutableListOf<OutboxMessage>()
    var failAfter: Int = Int.MAX_VALUE

    override fun publish(message: OutboxMessage) {
        if (received.size >= failAfter) error("broker unavailable")
        received += message
    }
}
