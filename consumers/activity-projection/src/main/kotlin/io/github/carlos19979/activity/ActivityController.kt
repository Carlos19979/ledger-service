package io.github.carlos19979.activity

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.Currency
import java.util.UUID

data class ActivityResponse(
    val accountId: UUID,
    val balance: String,
    val credits: String,
    val debits: String,
    val currency: String,
    val entryCount: Long,
    val lastPostedAt: OffsetDateTime,
)

@RestController
@RequestMapping("/api/v1/activity")
class ActivityController(private val repository: ActivityRepository) {
    @GetMapping("/{accountId}")
    fun get(
        @PathVariable accountId: UUID,
    ): ResponseEntity<Any> {
        val activity =
            repository.find(accountId)
                ?: return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No activity for account $accountId"))
        val digits = Currency.getInstance(activity.currency).defaultFractionDigits

        fun decimal(minor: Long) = BigDecimal.valueOf(minor, digits).toPlainString()
        return ResponseEntity.ok(
            ActivityResponse(
                accountId = activity.accountId,
                balance = decimal(activity.balanceMinor),
                credits = decimal(activity.creditsMinor),
                debits = decimal(activity.debitsMinor),
                currency = activity.currency,
                entryCount = activity.entryCount,
                lastPostedAt = activity.lastPostedAt,
            ),
        )
    }
}
