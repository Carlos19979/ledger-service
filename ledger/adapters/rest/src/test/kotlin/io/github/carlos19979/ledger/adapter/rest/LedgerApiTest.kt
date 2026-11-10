package io.github.carlos19979.ledger.adapter.rest

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

@WebMvcTest(controllers = [AccountController::class, TransferController::class])
@Import(InMemoryUseCaseBeans::class)
class LedgerApiTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val json: JsonMapper,
) {
    private fun openAccount(owner: String = "Ada", limit: String? = null): String {
        val limitJson = limit?.let { ""","dailyDebitLimit":{"amount":"$it","currency":"EUR"}""" }.orEmpty()
        val body =
            mvc
                .post("/api/v1/accounts") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"owner":"$owner","currency":"EUR"$limitJson}"""
                }.andExpect { status { isCreated() } }
                .andReturn()
                .response.contentAsString
        return json.readTree(body)["id"].asString()
    }

    private fun deposit(account: String, amount: String, key: String = UUID.randomUUID().toString()) =
        mvc.post("/api/v1/accounts/$account/deposits") {
            header("Idempotency-Key", key)
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount":{"amount":"$amount","currency":"EUR"}}"""
        }

    private fun transfer(from: String, to: String, amount: String, key: String) =
        mvc.post("/api/v1/transfers") {
            header("Idempotency-Key", key)
            contentType = MediaType.APPLICATION_JSON
            content =
                """{"sourceAccountId":"$from","targetAccountId":"$to","amount":{"amount":"$amount","currency":"EUR"}}"""
        }

    @Test
    fun `opens an account and reads it back`() {
        val id = openAccount(limit = "500.00")

        mvc.get("/api/v1/accounts/$id").andExpect {
            status { isOk() }
            jsonPath("$.balance.amount") { value("0.00") }
            jsonPath("$.dailyDebitLimit.amount") { value("500.00") }
            jsonPath("$.status") { value("ACTIVE") }
        }
    }

    @Test
    fun `money is a decimal string with the currency's precision`() {
        val id = openAccount()
        deposit(id, "10.5").andExpect {
            status { isCreated() }
            jsonPath("$.amount.amount") { value("10.50") }
        }
    }

    @Test
    fun `a transfer settles with 201 and a replay returns the same transfer`() {
        val from = openAccount()
        val to = openAccount("Grace")
        deposit(from, "100.00")

        val first =
            transfer(from, to, "25.00", "pay-1")
                .andExpect {
                    status { isCreated() }
                    header { string("Idempotent-Replayed", "false") }
                    jsonPath("$.status") { value("SETTLED") }
                }.andReturn()
                .response.contentAsString

        transfer(from, to, "25.00", "pay-1").andExpect {
            status { isCreated() }
            header { string("Idempotent-Replayed", "true") }
            jsonPath("$.id") { value(json.readTree(first)["id"].asString()) }
        }
        mvc.get("/api/v1/accounts/$from").andExpect { jsonPath("$.balance.amount") { value("75.00") } }
    }

    @Test
    fun `a business rejection is a 422 problem that carries the stored transfer`() {
        val from = openAccount()
        val to = openAccount("Grace")

        transfer(from, to, "1.00", "broke").andExpect {
            status { isUnprocessableContent() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.type") { value("https://ledger.example/problems/insufficient-funds") }
            jsonPath("$.code") { value("insufficient-funds") }
            jsonPath("$.transfer.status") { value("REJECTED") }
        }
    }

    @Test
    fun `reusing an idempotency key for another request is refused`() {
        val from = openAccount()
        val to = openAccount("Grace")
        deposit(from, "100.00")
        transfer(from, to, "10.00", "k")

        transfer(from, to, "11.00", "k").andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.code") { value("idempotency-key-reused") }
        }
    }

    @Test
    fun `requests are validated before reaching the use case`() {
        val from = openAccount()
        val to = openAccount("Grace")

        mvc
            .post("/api/v1/transfers") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"sourceAccountId":"$from","targetAccountId":"$to","amount":{"amount":"1","currency":"EUR"}}"""
            }.andExpect { status { isBadRequest() } }
        transfer(from, to, "1.001", "precision").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("invalid-request") }
        }
        transfer(from, to, "0", "zero").andExpect { status { isBadRequest() } }
        transfer(from, to, "1", "bad key!").andExpect { status { isBadRequest() } }
        mvc
            .post("/api/v1/accounts") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"owner":"","currency":"euro"}"""
            }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `unknown resources are 404 problems`() {
        val unknown = UUID.randomUUID()
        mvc.get("/api/v1/accounts/$unknown").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("account-not-found") }
        }
        mvc.get("/api/v1/transfers/$unknown").andExpect { status { isNotFound() } }
        transfer(openAccount(), unknown.toString(), "1.00", "nobody").andExpect { status { isNotFound() } }
    }

    @Test
    fun `blocking an account makes transfers from it fail`() {
        val from = openAccount()
        val to = openAccount("Grace")
        deposit(from, "10.00")

        mvc.post("/api/v1/accounts/$from/block").andExpect { jsonPath("$.status") { value("BLOCKED") } }

        transfer(from, to, "1.00", "blocked").andExpect { jsonPath("$.code") { value("account-not-active") } }
    }

    @Test
    fun `the statement pages with an opaque cursor`() {
        val id = openAccount()
        repeat(3) { deposit(id, "${it + 1}.00") }

        val page =
            mvc
                .get("/api/v1/accounts/$id/entries?limit=2")
                .andExpect {
                    status { isOk() }
                    jsonPath("$.entries.length()") { value(2) }
                    jsonPath("$.entries[0].amount.amount") { value("3.00") }
                }.andReturn()
                .response.contentAsString
        val cursor = json.readTree(page)["nextCursor"].asString()

        mvc.get("/api/v1/accounts/$id/entries?limit=2&cursor=$cursor").andExpect {
            jsonPath("$.entries.length()") { value(1) }
            jsonPath("$.nextCursor") { doesNotExist() }
        }
        mvc.get("/api/v1/accounts/$id/entries?cursor=%%%").andExpect { status { isBadRequest() } }
    }
}
