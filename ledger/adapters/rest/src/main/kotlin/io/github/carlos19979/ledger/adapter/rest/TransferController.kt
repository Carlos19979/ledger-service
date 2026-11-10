package io.github.carlos19979.ledger.adapter.rest

import io.github.carlos19979.ledger.application.transfer.GetTransfer
import io.github.carlos19979.ledger.application.transfer.TransferCommand
import io.github.carlos19979.ledger.application.transfer.TransferMoney
import io.github.carlos19979.ledger.application.transfer.TransferResult
import io.github.carlos19979.ledger.domain.AccountId
import io.github.carlos19979.ledger.domain.TransferId
import io.github.carlos19979.ledger.domain.TransferStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "Transfers")
class TransferController(private val transferMoney: TransferMoney, private val getTransfer: GetTransfer) {
    @PostMapping
    @Operation(
        summary = "Move money between two accounts",
        description =
            "Idempotent per Idempotency-Key: a retry returns the original outcome with " +
                "Idempotent-Replayed: true. A business rejection is stored and answered with 422.",
    )
    fun create(
        @RequestHeader("Idempotency-Key") @Pattern(regexp = AccountController.IDEMPOTENCY_KEY) idempotencyKey: String,
        @Valid @RequestBody request: TransferRequestDto,
    ): ResponseEntity<Any> =
        TransferResponses.of(
            transferMoney.transfer(
                TransferCommand(
                    idempotencyKey = idempotencyKey,
                    sourceAccountId = AccountId(request.sourceAccountId),
                    targetAccountId = AccountId(request.targetAccountId),
                    amount = request.amount.toPositiveMoney(),
                    reference = request.reference?.trim()?.ifEmpty { null },
                ),
            ),
        )

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<Any> =
        getTransfer.execute(TransferId(id))?.let { ResponseEntity.ok<Any>(TransferResponse.of(it)) }
            ?: Problems.notFound("transfer", id)
}

/** One mapping from use-case outcome to HTTP, shared by transfers and deposits. */
internal object TransferResponses {
    fun of(result: TransferResult): ResponseEntity<Any> =
        when (result) {
            is TransferResult.Completed -> completed(result)
            is TransferResult.AccountNotFound -> Problems.notFound("account", result.id)
            TransferResult.IdempotencyKeyReused ->
                Problems.response(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "idempotency-key-reused",
                    "This Idempotency-Key was already used for a different request",
                )
            is TransferResult.UnsupportedCurrency ->
                Problems.response(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "unsupported-currency",
                    "Deposits in ${result.currency.currencyCode} are not supported",
                )
        }

    private fun completed(result: TransferResult.Completed): ResponseEntity<Any> {
        val transfer = result.transfer
        val builder =
            if (transfer.status == TransferStatus.SETTLED) {
                ResponseEntity.created(URI.create("/api/v1/transfers/${transfer.id}"))
            } else {
                ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
            }
        builder.header("Idempotent-Replayed", result.replayed.toString())
        val rejection = transfer.rejection ?: return builder.body(TransferResponse.of(transfer))
        return builder.body(
            Problems.of(
                HttpStatus.UNPROCESSABLE_CONTENT,
                rejection.code,
                rejection.detail,
                mapOf("transfer" to TransferResponse.of(transfer)),
            ),
        )
    }
}
