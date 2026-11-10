package io.github.carlos19979.fraud

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A customer transfer as the rules see it. */
data class Observation(
    val transferId: UUID,
    val accountId: UUID,
    val amountMinor: Long,
    val currency: String,
    val settled: Boolean,
    val occurredAt: Instant,
)

/** How many transfers the account made inside a rule's window, the current one included. */
data class WindowCounts(val settled: Int, val rejected: Int)

data class Signal(val rule: String, val detail: String)

/**
 * A rule is a pure function of the observation and the counts in its window. Windows use event time (when the
 * ledger decided the transfer), not arrival time, so a backlog replayed after an outage is judged the same way.
 */
sealed interface FraudRule {
    val name: String
    val window: Duration

    fun evaluate(observation: Observation, counts: WindowCounts): Signal?
}

class LargeAmountRule(private val thresholdMinor: Long) : FraudRule {
    override val name = "large-amount"
    override val window: Duration = Duration.ZERO

    override fun evaluate(observation: Observation, counts: WindowCounts) =
        if (observation.settled && observation.amountMinor >= thresholdMinor) {
            Signal(name, "Single transfer of ${observation.amountMinor} ${observation.currency} minor units")
        } else {
            null
        }
}

class VelocityRule(private val maxTransfers: Int, override val window: Duration) : FraudRule {
    override val name = "velocity"

    override fun evaluate(observation: Observation, counts: WindowCounts) =
        if (observation.settled && counts.settled > maxTransfers) {
            Signal(name, "${counts.settled} transfers within ${window.toMinutes()} min (limit $maxTransfers)")
        } else {
            null
        }
}

class RepeatedRejectionsRule(private val maxRejections: Int, override val window: Duration) : FraudRule {
    override val name = "repeated-rejections"

    override fun evaluate(observation: Observation, counts: WindowCounts) =
        if (!observation.settled && counts.rejected >= maxRejections) {
            Signal(name, "${counts.rejected} rejected transfers within ${window.toMinutes()} min")
        } else {
            null
        }
}
