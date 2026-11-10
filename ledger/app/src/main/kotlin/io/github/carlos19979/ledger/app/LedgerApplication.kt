package io.github.carlos19979.ledger.app

import io.github.carlos19979.ledger.adapter.rest.AccountController
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(scanBasePackageClasses = [LedgerApplication::class, AccountController::class])
@ConfigurationPropertiesScan
@EnableScheduling
class LedgerApplication

fun main(args: Array<String>) {
    runApplication<LedgerApplication>(*args)
}
