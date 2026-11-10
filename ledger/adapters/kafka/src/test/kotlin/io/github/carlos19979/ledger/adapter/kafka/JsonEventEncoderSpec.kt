package io.github.carlos19979.ledger.adapter.kafka

import io.github.carlos19979.contracts.ledger.EntryPostedV1
import io.github.carlos19979.contracts.ledger.LedgerTopics
import io.github.carlos19979.contracts.ledger.TransferCompletedV1
import io.github.carlos19979.ledger.domain.Account
import io.github.carlos19979.ledger.domain.AccountId
import io.github.carlos19979.ledger.domain.Ledger
import io.github.carlos19979.ledger.domain.LedgerEvent
import io.github.carlos19979.ledger.domain.Money
import io.github.carlos19979.ledger.domain.PostingResult
import io.github.carlos19979.ledger.domain.TransferId
import io.github.carlos19979.ledger.domain.TransferKind
import io.github.carlos19979.ledger.domain.TransferRequest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue
import java.time.Instant
import java.util.Currency

class JsonEventEncoderSpec : FunSpec({
    val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    val encoder = JsonEventEncoder(json)
    val eur = Currency.getInstance("EUR")
    val now = Instant.parse("2026-11-10T10:00:00Z")
    val funding = Ledger.fundingAccount(AccountId.new(), eur, now)
    val customer = Account.open("Ada", eur, null, now)
    val posted =
        Ledger.post(
            TransferRequest(
                TransferId.new(),
                TransferKind.DEPOSIT,
                funding.id,
                customer.id,
                Money.of("12.34", "EUR"),
                null,
                now,
            ),
            funding,
            customer,
            Money.zero(eur),
        ) as PostingResult.Posted

    test("entries go to the entries topic, keyed by account, with exact minor units") {
        val event = posted.events.first { it is LedgerEvent.EntryPosted && it.entry.accountId == customer.id }

        val message = encoder.encode(event)

        message.topic shouldBe LedgerTopics.ENTRIES
        message.key shouldBe customer.id.toString()
        message.eventType shouldBe "EntryPosted"
        message.schemaVersion shouldBe 1
        val payload = json.readValue<EntryPostedV1>(message.payload)
        payload.amountMinor shouldBe 1234
        payload.balanceAfterMinor shouldBe 1234
        payload.accountVersion shouldBe 1
        message.payload shouldContain "\"postedAt\":\"2026-11-10T10:00:00Z\""
        message.payload shouldNotContain "12.34"
    }

    test("settlements go to the transfers topic, keyed by the source account") {
        val message = encoder.encode(posted.events.last())

        message.topic shouldBe LedgerTopics.TRANSFERS
        message.key shouldBe funding.id.toString()
        json.readValue<TransferCompletedV1>(message.payload).status shouldBe "SETTLED"
    }
})
