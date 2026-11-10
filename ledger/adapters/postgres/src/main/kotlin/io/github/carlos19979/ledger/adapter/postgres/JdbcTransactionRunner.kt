package io.github.carlos19979.ledger.adapter.postgres

import io.github.carlos19979.ledger.application.port.TransactionRunner
import org.springframework.transaction.support.TransactionTemplate

class JdbcTransactionRunner(private val template: TransactionTemplate) : TransactionRunner {
    override fun <T> inTransaction(block: () -> T): T = template.execute { block() }
}
