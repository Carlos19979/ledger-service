package io.github.carlos19979.ledger.application.transfer

import io.github.carlos19979.ledger.application.port.AccountRepository
import io.github.carlos19979.ledger.application.port.EventEncoder
import io.github.carlos19979.ledger.application.port.IdempotencyClaim
import io.github.carlos19979.ledger.application.port.IdempotencyStore
import io.github.carlos19979.ledger.application.port.LedgerEntryRepository
import io.github.carlos19979.ledger.application.port.OutboxStore
import io.github.carlos19979.ledger.application.port.TransactionRunner
import io.github.carlos19979.ledger.application.port.TransferRepository
import io.github.carlos19979.ledger.domain.AccountId
import io.github.carlos19979.ledger.domain.Ledger
import io.github.carlos19979.ledger.domain.Money
import io.github.carlos19979.ledger.domain.PostingResult
import io.github.carlos19979.ledger.domain.Transfer
import io.github.carlos19979.ledger.domain.TransferId
import io.github.carlos19979.ledger.domain.TransferKind
import io.github.carlos19979.ledger.domain.TransferRequest
import java.security.MessageDigest
import java.time.Clock
import java.time.ZoneOffset

data class TransferCommand(
    val idempotencyKey: String,
    val sourceAccountId: AccountId,
    val targetAccountId: AccountId,
    val amount: Money,
    val reference: String?,
)

data class DepositCommand(val idempotencyKey: String, val accountId: AccountId, val amount: Money)

sealed interface TransferResult {
    /** The transfer reached a terminal state: settled or rejected. [replayed] means it was decided earlier. */
    data class Completed(val transfer: Transfer, val replayed: Boolean) : TransferResult

    data class AccountNotFound(val id: AccountId) : TransferResult

    /** Same idempotency key, different request: almost always a client bug, never silently accepted. */
    data object IdempotencyKeyReused : TransferResult

    data class UnsupportedCurrency(val currency: java.util.Currency) : TransferResult
}

/**
 * Moves money between two accounts in one transaction:
 * claim the idempotency key, lock both accounts in id order, let the domain decide, persist the outcome and its
 * events. Deposits are transfers from the bank's funding account for the currency.
 */
class TransferMoney(
    private val accounts: AccountRepository,
    private val transfers: TransferRepository,
    private val entries: LedgerEntryRepository,
    private val idempotency: IdempotencyStore,
    private val outbox: OutboxStore,
    private val encoder: EventEncoder,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {
    fun transfer(command: TransferCommand): TransferResult =
        move(
            kind = TransferKind.TRANSFER,
            key = command.idempotencyKey,
            source = command.sourceAccountId,
            target = command.targetAccountId,
            amount = command.amount,
            reference = command.reference,
        )

    fun deposit(command: DepositCommand): TransferResult {
        val funding =
            accounts.fundingAccountFor(command.amount.currency)
                ?: return TransferResult.UnsupportedCurrency(command.amount.currency)
        return move(TransferKind.DEPOSIT, command.idempotencyKey, funding, command.accountId, command.amount, null)
    }

    private fun move(
        kind: TransferKind,
        key: String,
        source: AccountId,
        target: AccountId,
        amount: Money,
        reference: String?,
    ): TransferResult {
        require(key.isNotBlank()) { "Idempotency key is required" }
        val hash = requestHash(kind, source, target, amount, reference)
        return try {
            tx.inTransaction {
                val transferId = TransferId.new()
                when (val claim = idempotency.claim(kind.name, key, hash, transferId)) {
                    is IdempotencyClaim.AlreadyUsed -> replay(claim, hash)
                    IdempotencyClaim.Claimed ->
                        post(
                            TransferRequest(transferId, kind, source, target, amount, reference, clock.instant()),
                        )
                }
            }
        } catch (missing: MissingAccount) {
            // Thrown inside the transaction so the idempotency claim rolls back with it.
            TransferResult.AccountNotFound(missing.id)
        }
    }

    private fun replay(claim: IdempotencyClaim.AlreadyUsed, hash: String): TransferResult {
        if (claim.requestHash != hash) return TransferResult.IdempotencyKeyReused
        val transfer =
            checkNotNull(transfers.findById(claim.transferId)) { "Claimed transfer ${claim.transferId} is missing" }
        return TransferResult.Completed(transfer, replayed = true)
    }

    private fun post(request: TransferRequest): TransferResult {
        val locked = accounts.lockForUpdate(setOf(request.sourceAccountId, request.targetAccountId))
        val source = locked[request.sourceAccountId] ?: throw MissingAccount(request.sourceAccountId)
        val target = locked[request.targetAccountId] ?: throw MissingAccount(request.targetAccountId)

        val debitedToday =
            if (source.dailyDebitLimit == null) {
                Money.zero(source.currency)
            } else {
                val startOfDay =
                    request.requestedAt
                        .atOffset(
                            ZoneOffset.UTC,
                        ).toLocalDate()
                        .atStartOfDay()
                        .toInstant(ZoneOffset.UTC)
                entries.debitedSince(source.id, startOfDay, source.currency)
            }

        val result = Ledger.post(request, source, target, debitedToday)
        transfers.insert(result.transfer)
        if (result is PostingResult.Posted) {
            // Same-account transfers are rejected by the domain, so source and target are distinct rows here.
            accounts.update(result.source, source.version)
            accounts.update(result.target, target.version)
            entries.insertAll(result.entries)
        }
        outbox.append(result.events.map(encoder::encode))
        return TransferResult.Completed(result.transfer, replayed = false)
    }

    private class MissingAccount(val id: AccountId) : RuntimeException("Account $id not found")

    private fun requestHash(
        kind: TransferKind,
        source: AccountId,
        target: AccountId,
        amount: Money,
        reference: String?,
    ): String {
        val canonical =
            listOf(kind, source, target, amount.minorUnits, amount.currency.currencyCode, reference.orEmpty())
                .joinToString("|")
        return MessageDigest
            .getInstance(
                "SHA-256",
            ).digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

class GetTransfer(private val transfers: TransferRepository) {
    fun execute(id: TransferId): Transfer? = transfers.findById(id)
}
