package io.github.carlos19979.ledger.adapter.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.carlos19979.ledger.application.account.GetStatement
import io.github.carlos19979.ledger.application.account.OpenAccount
import io.github.carlos19979.ledger.application.outbox.OutboxRelay
import io.github.carlos19979.ledger.application.port.EventEncoder
import io.github.carlos19979.ledger.application.port.MessageBroker
import io.github.carlos19979.ledger.application.port.OutboxMessage
import io.github.carlos19979.ledger.application.transfer.TransferMoney
import io.github.carlos19979.ledger.domain.AccountId
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** One PostgreSQL container for the whole test run; every spec gets its own freshly migrated database. */
object TestDatabase {
    private val container = PostgreSQLContainer("postgres:17-alpine").apply { start() }
    private val counter = AtomicInteger()

    fun fresh(): HikariDataSource {
        val name = "ledger_${counter.incrementAndGet()}"
        container.createConnection("").use { it.createStatement().execute("CREATE DATABASE $name") }
        val dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = container.jdbcUrl.replace("/${container.databaseName}", "/$name")
                    username = container.username
                    password = container.password
                    maximumPoolSize = 20
                },
            )
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/ledger")
            .load()
            .migrate()
        return dataSource
    }
}

val EUR_FUNDING = AccountId(UUID.fromString("00000000-0000-0000-0000-000000000978"))

/** The production adapters wired to a test database, without Spring Boot. */
class PostgresLedger(val dataSource: HikariDataSource = TestDatabase.fresh()) {
    val clock: Clock = Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000))
    val jdbc: JdbcClient = JdbcClient.create(dataSource)
    val tx = JdbcTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource)))
    val accounts = PostgresAccountRepository(jdbc)
    val transfers = PostgresTransferRepository(jdbc)
    val entries = PostgresLedgerEntryRepository(jdbc)
    val idempotency = PostgresIdempotencyStore(jdbc)
    val outbox = PostgresOutboxStore(jdbc)
    private val encoder =
        EventEncoder { event ->
            OutboxMessage(
                event.eventId.value,
                "test",
                "key",
                event.javaClass.simpleName,
                1,
                """{"e":"${event.eventId}"}""",
                event.occurredAt,
            )
        }

    val openAccount = OpenAccount(accounts, tx, clock)
    val transferMoney = TransferMoney(accounts, transfers, entries, idempotency, outbox, encoder, tx, clock)
    val statement = GetStatement(accounts, entries)

    fun relay(broker: MessageBroker) = OutboxRelay(outbox, broker, tx, clock)
}
