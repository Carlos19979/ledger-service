package io.github.carlos19979.ledger.app

import io.github.carlos19979.contracts.ledger.LedgerTopics
import io.github.carlos19979.ledger.adapter.kafka.JsonEventEncoder
import io.github.carlos19979.ledger.adapter.kafka.KafkaMessageBroker
import io.github.carlos19979.ledger.adapter.postgres.JdbcTransactionRunner
import io.github.carlos19979.ledger.adapter.postgres.PostgresAccountRepository
import io.github.carlos19979.ledger.adapter.postgres.PostgresIdempotencyStore
import io.github.carlos19979.ledger.adapter.postgres.PostgresLedgerEntryRepository
import io.github.carlos19979.ledger.adapter.postgres.PostgresOutboxStore
import io.github.carlos19979.ledger.adapter.postgres.PostgresTransferRepository
import io.github.carlos19979.ledger.application.account.ChangeAccountStatus
import io.github.carlos19979.ledger.application.account.GetAccount
import io.github.carlos19979.ledger.application.account.GetStatement
import io.github.carlos19979.ledger.application.account.OpenAccount
import io.github.carlos19979.ledger.application.outbox.OutboxRelay
import io.github.carlos19979.ledger.application.port.AccountRepository
import io.github.carlos19979.ledger.application.port.EventEncoder
import io.github.carlos19979.ledger.application.port.IdempotencyStore
import io.github.carlos19979.ledger.application.port.LedgerEntryRepository
import io.github.carlos19979.ledger.application.port.MessageBroker
import io.github.carlos19979.ledger.application.port.OutboxStore
import io.github.carlos19979.ledger.application.port.TransactionRunner
import io.github.carlos19979.ledger.application.port.TransferRepository
import io.github.carlos19979.ledger.application.transfer.GetTransfer
import io.github.carlos19979.ledger.application.transfer.TransferMoney
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration

@ConfigurationProperties("ledger")
data class LedgerProperties(
    val outbox: Outbox = Outbox(),
    val topicPartitions: Int = 3,
) {
    data class Outbox(
        val batchSize: Int = 100,
        val publishTimeout: Duration = DEFAULT_PUBLISH_TIMEOUT,
    )

    private companion object {
        val DEFAULT_PUBLISH_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}

/**
 * Explicit wiring: every use case gets its ports by constructor, so the dependency graph is readable in one file and
 * the application layer never needs a Spring annotation.
 */
@Configuration(proxyBeanMethods = false)
class LedgerConfiguration {
    // PostgreSQL keeps microseconds; ticking the clock at that precision keeps stored and in-memory values equal.
    @Bean
    fun clock(): Clock = Clock.tick(Clock.systemUTC(), Duration.ofNanos(NANOS_PER_MICRO))

    @Bean
    fun transactionRunner(template: TransactionTemplate): TransactionRunner = JdbcTransactionRunner(template)

    @Bean
    fun accountRepository(jdbc: JdbcClient): AccountRepository = PostgresAccountRepository(jdbc)

    @Bean
    fun transferRepository(jdbc: JdbcClient): TransferRepository = PostgresTransferRepository(jdbc)

    @Bean
    fun ledgerEntryRepository(jdbc: JdbcClient): LedgerEntryRepository = PostgresLedgerEntryRepository(jdbc)

    @Bean
    fun idempotencyStore(jdbc: JdbcClient): IdempotencyStore = PostgresIdempotencyStore(jdbc)

    @Bean
    fun outboxStore(jdbc: JdbcClient): OutboxStore = PostgresOutboxStore(jdbc)

    @Bean
    fun eventEncoder(json: JsonMapper): EventEncoder = JsonEventEncoder(json)

    @Bean
    fun messageBroker(kafka: KafkaTemplate<String, String>, properties: LedgerProperties): MessageBroker =
        KafkaMessageBroker(kafka, properties.outbox.publishTimeout)

    @Bean
    fun openAccount(accounts: AccountRepository, tx: TransactionRunner, clock: Clock) = OpenAccount(accounts, tx, clock)

    @Bean
    fun getAccount(accounts: AccountRepository) = GetAccount(accounts)

    @Bean
    fun changeAccountStatus(accounts: AccountRepository, tx: TransactionRunner) = ChangeAccountStatus(accounts, tx)

    @Bean
    fun getStatement(accounts: AccountRepository, entries: LedgerEntryRepository) = GetStatement(accounts, entries)

    @Bean
    @Suppress("LongParameterList") // one parameter per port the use case needs
    fun transferMoney(
        accounts: AccountRepository,
        transfers: TransferRepository,
        entries: LedgerEntryRepository,
        idempotency: IdempotencyStore,
        outbox: OutboxStore,
        encoder: EventEncoder,
        tx: TransactionRunner,
        clock: Clock,
    ) = TransferMoney(accounts, transfers, entries, idempotency, outbox, encoder, tx, clock)

    @Bean
    fun getTransfer(transfers: TransferRepository) = GetTransfer(transfers)

    @Bean
    fun outboxRelay(
        outbox: OutboxStore,
        broker: MessageBroker,
        tx: TransactionRunner,
        clock: Clock,
        properties: LedgerProperties,
    ) = OutboxRelay(outbox, broker, tx, clock, properties.outbox.batchSize)

    @Bean
    fun entriesTopic(properties: LedgerProperties): NewTopic =
        TopicBuilder.name(LedgerTopics.ENTRIES).partitions(properties.topicPartitions).build()

    @Bean
    fun transfersTopic(properties: LedgerProperties): NewTopic =
        TopicBuilder.name(LedgerTopics.TRANSFERS).partitions(properties.topicPartitions).build()

    private companion object {
        const val NANOS_PER_MICRO = 1_000L
    }
}
