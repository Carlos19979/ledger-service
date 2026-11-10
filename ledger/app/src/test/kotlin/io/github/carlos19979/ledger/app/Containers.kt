package io.github.carlos19979.ledger.app

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Duration
import java.util.UUID

/**
 * Real PostgreSQL and Kafka for every Spring test; Boot reads their connection details automatically.
 * Started once per test JVM and shared by every test context, which is faster and avoids re-binding ports.
 */
@TestConfiguration(proxyBeanMethods = false)
class Containers {
    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer = POSTGRES

    @Bean
    @ServiceConnection
    fun kafka(): KafkaContainer = KAFKA

    companion object {
        val POSTGRES = PostgreSQLContainer("postgres:17-alpine").apply { start() }
        val KAFKA = KafkaContainer("apache/kafka:4.1.0").apply { start() }
    }
}

/** Reads everything on the given topics from the beginning, for asserting on what the ledger published. */
class TopicReader(bootstrapServers: String, topics: List<String>) : AutoCloseable {
    private val consumer =
        KafkaConsumer<String, String>(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG to "test-${UUID.randomUUID()}",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ),
        ).apply { subscribe(topics) }
    val records = mutableListOf<ConsumerRecord<String, String>>()

    fun poll(): List<ConsumerRecord<String, String>> {
        records += consumer.poll(Duration.ofMillis(500))
        return records
    }

    override fun close() = consumer.close()
}

fun ConsumerRecord<String, String>.header(name: String): String? = headers().lastHeader(name)?.value()?.let(::String)
