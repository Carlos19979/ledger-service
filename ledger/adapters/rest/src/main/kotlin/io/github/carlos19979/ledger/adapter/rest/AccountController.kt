package io.github.carlos19979.ledger.adapter.rest

import io.github.carlos19979.ledger.application.account.AccountLookup
import io.github.carlos19979.ledger.application.account.ChangeAccountStatus
import io.github.carlos19979.ledger.application.account.GetAccount
import io.github.carlos19979.ledger.application.account.GetStatement
import io.github.carlos19979.ledger.application.account.OpenAccount
import io.github.carlos19979.ledger.application.account.OpenAccountCommand
import io.github.carlos19979.ledger.application.account.StatementResult
import io.github.carlos19979.ledger.application.account.StatusChange
import io.github.carlos19979.ledger.application.transfer.DepositCommand
import io.github.carlos19979.ledger.application.transfer.TransferMoney
import io.github.carlos19979.ledger.domain.AccountId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.Currency
import java.util.UUID

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts")
class AccountController(
    private val openAccount: OpenAccount,
    private val getAccount: GetAccount,
    private val changeStatus: ChangeAccountStatus,
    private val statement: GetStatement,
    private val transferMoney: TransferMoney,
) {
    @PostMapping
    @Operation(summary = "Open a customer account")
    fun open(
        @Valid @RequestBody request: OpenAccountRequest,
    ): ResponseEntity<AccountResponse> {
        val currency =
            runCatching { Currency.getInstance(request.currency) }
                .getOrElse { throw InvalidRequestException("Unknown currency ${request.currency}") }
        val account =
            openAccount.execute(
                OpenAccountCommand(request.owner, currency, request.dailyDebitLimit?.toPositiveMoney()),
            )
        return ResponseEntity.created(URI.create("/api/v1/accounts/${account.id}")).body(AccountResponse.of(account))
    }

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<Any> =
        when (val result = getAccount.execute(AccountId(id))) {
            is AccountLookup.Found -> ResponseEntity.ok(AccountResponse.of(result.account))
            is AccountLookup.NotFound -> Problems.notFound("account", id)
        }

    @PostMapping("/{id}/deposits")
    @Operation(summary = "Credit an account from the bank's funding account. Idempotent per Idempotency-Key.")
    fun deposit(
        @PathVariable id: UUID,
        @RequestHeader("Idempotency-Key") @Pattern(regexp = IDEMPOTENCY_KEY) idempotencyKey: String,
        @Valid @RequestBody request: DepositRequest,
    ): ResponseEntity<Any> =
        TransferResponses.of(
            transferMoney.deposit(DepositCommand(idempotencyKey, AccountId(id), request.amount.toPositiveMoney())),
        )

    @PostMapping("/{id}/block")
    fun block(
        @PathVariable id: UUID,
    ) = status(id, StatusChange.BLOCK)

    @PostMapping("/{id}/unblock")
    fun unblock(
        @PathVariable id: UUID,
    ) = status(id, StatusChange.UNBLOCK)

    @GetMapping("/{id}/entries")
    @Operation(summary = "Account statement, newest first, paged with an opaque cursor")
    fun entries(
        @PathVariable id: UUID,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "50") @Min(1) @Max(GetStatement.MAX_PAGE_SIZE.toLong()) limit: Int,
    ): ResponseEntity<Any> =
        when (val result = statement.execute(AccountId(id), Cursor.decode(cursor), limit)) {
            is StatementResult.Page ->
                ResponseEntity.ok(
                    StatementResponse(
                        result.page.entries.map(EntryResponse::of),
                        result.page.nextCursor?.let(Cursor::encode),
                    ),
                )
            is StatementResult.NotFound -> Problems.notFound("account", id)
        }

    private fun status(id: UUID, change: StatusChange): ResponseEntity<Any> =
        when (val result = changeStatus.execute(AccountId(id), change)) {
            is AccountLookup.Found -> ResponseEntity.ok(AccountResponse.of(result.account))
            is AccountLookup.NotFound -> Problems.notFound("account", id)
        }

    companion object {
        const val IDEMPOTENCY_KEY = "[A-Za-z0-9_-]{1,64}"
    }
}

/** Statement cursors are opaque to clients, so the paging strategy can change without breaking them. */
internal object Cursor {
    private val encoder =
        java.util.Base64
            .getUrlEncoder()
            .withoutPadding()
    private val decoder = java.util.Base64.getUrlDecoder()

    fun encode(position: Long): String = encoder.encodeToString(position.toString().toByteArray())

    fun decode(cursor: String?): Long? =
        cursor?.let {
            runCatching {
                String(
                    decoder.decode(it),
                ).toLong()
            }.getOrElse { throw InvalidRequestException("Invalid cursor") }
        }
}
