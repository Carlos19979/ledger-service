package io.github.carlos19979.ledger.adapter.rest

import io.github.carlos19979.ledger.domain.Account
import io.github.carlos19979.ledger.domain.LedgerEntry
import io.github.carlos19979.ledger.domain.Money
import io.github.carlos19979.ledger.domain.Transfer
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import java.util.UUID

/** Money on the wire: a decimal string, never a JSON number, so no client parses it into a float. */
data class MoneyDto(
    @field:NotBlank
    @field:Pattern(regexp = "-?\\d{1,15}(\\.\\d{1,4})?", message = "must be a decimal amount such as 12.50")
    val amount: String,
    @field:NotBlank
    @field:Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code such as EUR")
    val currency: String,
) {
    fun toMoney(): Money {
        val currency =
            try {
                Currency.getInstance(currency)
            } catch (_: IllegalArgumentException) {
                throw InvalidRequestException("Unknown currency $currency")
            }
        return try {
            Money.of(BigDecimal(amount), currency)
        } catch (e: IllegalArgumentException) {
            throw InvalidRequestException(e.message ?: "Invalid amount", e)
        }
    }

    fun toPositiveMoney(): Money =
        toMoney().also {
            if (!it.isPositive) throw InvalidRequestException("Amount must be greater than zero")
        }

    companion object {
        fun of(money: Money) = MoneyDto(money.toDecimal().toPlainString(), money.currency.currencyCode)
    }
}

class InvalidRequestException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class OpenAccountRequest(
    @field:NotBlank @field:Size(max = Account.MAX_OWNER_LENGTH) val owner: String,
    @field:NotBlank @field:Pattern(regexp = "[A-Z]{3}") val currency: String,
    @field:Valid val dailyDebitLimit: MoneyDto? = null,
)

data class DepositRequest(
    @field:NotNull @field:Valid val amount: MoneyDto,
)

data class TransferRequestDto(
    @field:NotNull val sourceAccountId: UUID,
    @field:NotNull val targetAccountId: UUID,
    @field:NotNull @field:Valid val amount: MoneyDto,
    @field:Size(max = Transfer.MAX_REFERENCE_LENGTH) val reference: String? = null,
)

data class AccountResponse(
    val id: UUID,
    val owner: String,
    val type: String,
    val currency: String,
    val balance: MoneyDto,
    val status: String,
    val dailyDebitLimit: MoneyDto?,
    val openedAt: Instant,
) {
    companion object {
        fun of(account: Account) =
            AccountResponse(
                id = account.id.value,
                owner = account.owner,
                type = account.type.name,
                currency = account.currency.currencyCode,
                balance = MoneyDto.of(account.balance),
                status = account.status.name,
                dailyDebitLimit = account.dailyDebitLimit?.let(MoneyDto::of),
                openedAt = account.openedAt,
            )
    }
}

data class RejectionDto(val code: String, val detail: String)

data class TransferResponse(
    val id: UUID,
    val kind: String,
    val sourceAccountId: UUID,
    val targetAccountId: UUID,
    val amount: MoneyDto,
    val reference: String?,
    val status: String,
    val rejection: RejectionDto?,
    val createdAt: Instant,
) {
    companion object {
        fun of(transfer: Transfer) =
            TransferResponse(
                id = transfer.id.value,
                kind = transfer.kind.name,
                sourceAccountId = transfer.sourceAccountId.value,
                targetAccountId = transfer.targetAccountId.value,
                amount = MoneyDto.of(transfer.amount),
                reference = transfer.reference,
                status = transfer.status.name,
                rejection = transfer.rejection?.let { RejectionDto(it.code, it.detail) },
                createdAt = transfer.createdAt,
            )
    }
}

data class EntryResponse(
    val id: UUID,
    val transferId: UUID,
    val direction: String,
    val amount: MoneyDto,
    val balanceAfter: MoneyDto,
    val postedAt: Instant,
) {
    companion object {
        fun of(entry: LedgerEntry) =
            EntryResponse(
                id = entry.id.value,
                transferId = entry.transferId.value,
                direction = entry.direction.name,
                amount = MoneyDto.of(entry.amount),
                balanceAfter = MoneyDto.of(entry.balanceAfter),
                postedAt = entry.postedAt,
            )
    }
}

data class StatementResponse(val entries: List<EntryResponse>, val nextCursor: String?)
