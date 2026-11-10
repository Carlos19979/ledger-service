package io.github.carlos19979.fraud

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant
import java.util.UUID

class FraudRulesSpec : FunSpec({
    fun observation(amount: Long = 100, settled: Boolean = true) =
        Observation(UUID.randomUUID(), UUID.randomUUID(), amount, "EUR", settled, Instant.EPOCH)

    test("large amount fires at the threshold, only for settled transfers") {
        val rule = LargeAmountRule(thresholdMinor = 500_000)
        listOf(
            Triple(499_999L, true, false),
            Triple(500_000L, true, true),
            Triple(900_000L, false, false),
        ).forEach { (amount, settled, fires) ->
            (rule.evaluate(observation(amount, settled), WindowCounts(1, 0)) != null) shouldBe fires
        }
    }

    test("velocity fires once the count in the window exceeds the limit") {
        val rule = VelocityRule(maxTransfers = 5, window = Duration.ofMinutes(2))

        rule.evaluate(observation(), WindowCounts(settled = 5, rejected = 0)).shouldBeNull()
        rule.evaluate(observation(), WindowCounts(settled = 6, rejected = 0)).shouldNotBeNull().rule shouldBe "velocity"
    }

    test("repeated rejections look at rejected transfers only") {
        val rule = RepeatedRejectionsRule(maxRejections = 3, window = Duration.ofMinutes(10))

        rule.evaluate(observation(settled = false), WindowCounts(settled = 10, rejected = 2)).shouldBeNull()
        rule.evaluate(observation(settled = false), WindowCounts(settled = 0, rejected = 3)).shouldNotBeNull()
        rule.evaluate(observation(settled = true), WindowCounts(settled = 0, rejected = 3)).shouldBeNull()
    }
})
