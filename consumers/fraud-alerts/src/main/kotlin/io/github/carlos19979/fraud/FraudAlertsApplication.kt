package io.github.carlos19979.fraud

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class FraudAlertsApplication

fun main(args: Array<String>) {
    runApplication<FraudAlertsApplication>(*args)
}
