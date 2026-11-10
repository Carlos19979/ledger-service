package io.github.carlos19979.ledger.adapter.rest

import io.github.carlos19979.ledger.application.InMemoryUseCases
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.time.Clock

/** Anchor for @WebMvcTest; the real application lives in :ledger:app. */
@SpringBootApplication
class RestTestApplication

/** Real use cases over the in-memory ports: the controllers are tested without mocks or a database. */
@TestConfiguration
class InMemoryUseCaseBeans {
    @Bean
    fun useCases() = InMemoryUseCases(Clock.systemUTC())

    @Bean
    fun openAccount(app: InMemoryUseCases) = app.openAccount

    @Bean
    fun getAccount(app: InMemoryUseCases) = app.getAccount

    @Bean
    fun changeStatus(app: InMemoryUseCases) = app.changeStatus

    @Bean
    fun statement(app: InMemoryUseCases) = app.statement

    @Bean
    fun transferMoney(app: InMemoryUseCases) = app.transferMoney

    @Bean
    fun getTransfer(app: InMemoryUseCases) = app.getTransfer
}
