package io.github.carlos19979.ledger.adapter.rest

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import java.net.URI

/** RFC 9457 Problem Details with a stable `type` per error, so clients branch on it rather than on prose. */
object Problems {
    fun of(status: HttpStatus, code: String, detail: String, extra: Map<String, Any> = emptyMap()): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            type = URI.create("https://ledger.example/problems/$code")
            title = code.replace('-', ' ').replaceFirstChar(Char::uppercase)
            setProperty("code", code)
            extra.forEach(::setProperty)
        }

    fun response(status: HttpStatus, code: String, detail: String, extra: Map<String, Any> = emptyMap()) =
        ResponseEntity.status(status).body<Any>(of(status, code, detail, extra))

    fun notFound(what: String, id: Any) = response(HttpStatus.NOT_FOUND, "$what-not-found", "No $what with id $id")
}
