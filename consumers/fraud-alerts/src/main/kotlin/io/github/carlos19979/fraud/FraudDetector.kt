package io.github.carlos19979.fraud

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@ConfigurationProperties("fraud")
data class FraudProperties(
    val largeAmountMinor: Long,
    val velocity: Velocity,
    val rejections: Rejections,
) {
    data class Velocity(val maxTransfers: Int, val window: Duration)

    data class Rejections(val maxRejections: Int, val window: Duration)

    fun rules(): List<FraudRule> =
        listOf(
            LargeAmountRule(largeAmountMinor),
            VelocityRule(velocity.maxTransfers, velocity.window),
            RepeatedRejectionsRule(rejections.maxRejections, rejections.window),
        )
}

data class Alert(
    val id: UUID,
    val accountId: UUID,
    val rule: String,
    val detail: String,
    val transferId: UUID,
    val occurredAt: Instant,
)

@Repository
class FraudRepository(private val jdbc: JdbcClient) {
    /** Records the transfer; false if it was already seen, which makes redelivery harmless. */
    fun record(o: Observation): Boolean =
        jdbc
            .sql(
                """
        INSERT INTO observed_transfers (transfer_id, account_id, amount_minor, currency, settled, occurred_at)
        VALUES (:id, :account, :amount, :currency, :settled, :at)
        ON CONFLICT DO NOTHING
        """,
            ).param("id", o.transferId)
            .param("account", o.accountId)
            .param("amount", o.amountMinor)
            .param("currency", o.currency)
            .param("settled", o.settled)
            .param("at", o.occurredAt.utc())
            .update() == 1

    fun countsBetween(accountId: UUID, from: Instant, to: Instant): WindowCounts =
        jdbc
            .sql(
                """
        SELECT count(*) FILTER (WHERE settled) AS settled, count(*) FILTER (WHERE NOT settled) AS rejected
        FROM observed_transfers
        WHERE account_id = :account AND occurred_at BETWEEN :from AND :to
        """,
            ).param("account", accountId)
            .param("from", from.utc())
            .param("to", to.utc())
            .query { rs, _ -> WindowCounts(rs.getInt("settled"), rs.getInt("rejected")) }
            .single()

    fun alertedSince(accountId: UUID, rule: String, since: Instant): Boolean =
        jdbc
            .sql(
                """
                SELECT exists(SELECT 1 FROM alerts WHERE account_id = :account AND rule = :rule AND occurred_at >= :since)
                """,
            ).param("account", accountId)
            .param("rule", rule)
            .param("since", since.utc())
            .query(Boolean::class.java)
            .single()

    fun save(alert: Alert) {
        jdbc
            .sql(
                """
            INSERT INTO alerts (id, account_id, rule, detail, transfer_id, occurred_at)
            VALUES (:id, :account, :rule, :detail, :transfer, :at)
            """,
            ).param("id", alert.id)
            .param("account", alert.accountId)
            .param("rule", alert.rule)
            .param("detail", alert.detail)
            .param("transfer", alert.transferId)
            .param("at", alert.occurredAt.utc())
            .update()
    }

    fun alertsFor(accountId: UUID): List<Alert> =
        jdbc
            .sql("SELECT * FROM alerts WHERE account_id = :account ORDER BY occurred_at DESC")
            .param("account", accountId)
            .query { rs, _ ->
                Alert(
                    id = rs.getObject("id", UUID::class.java),
                    accountId = rs.getObject("account_id", UUID::class.java),
                    rule = rs.getString("rule"),
                    detail = rs.getString("detail"),
                    transferId = rs.getObject("transfer_id", UUID::class.java),
                    occurredAt = rs.getObject("occurred_at", OffsetDateTime::class.java).toInstant(),
                )
            }.list()

    private fun Instant.utc() = atOffset(ZoneOffset.UTC)
}

/**
 * Records the observation and runs every rule against the account's recent history, all in one transaction.
 * A rule that already fired for the account within its window stays quiet, so a burst raises one alert, not ten.
 */
@Component
class FraudDetector(
    private val repository: FraudRepository,
    private val tx: TransactionTemplate,
    properties: FraudProperties,
) {
    private val rules = properties.rules()

    fun observe(observation: Observation): List<Alert> =
        tx.execute {
            if (!repository.record(observation)) return@execute emptyList()
            rules.mapNotNull { rule -> evaluate(rule, observation)?.also(repository::save) }
        }

    private fun evaluate(rule: FraudRule, observation: Observation): Alert? {
        val from = observation.occurredAt - rule.window
        val counts = repository.countsBetween(observation.accountId, from, observation.occurredAt)
        val signal = rule.evaluate(observation, counts) ?: return null
        val coolingDown = rule.window > Duration.ZERO && repository.alertedSince(observation.accountId, rule.name, from)
        if (coolingDown) return null
        return Alert(
            id = UUID.randomUUID(),
            accountId = observation.accountId,
            rule = signal.rule,
            detail = signal.detail,
            transferId = observation.transferId,
            occurredAt = observation.occurredAt,
        )
    }
}
