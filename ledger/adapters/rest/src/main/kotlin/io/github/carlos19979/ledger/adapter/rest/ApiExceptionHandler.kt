package io.github.carlos19979.ledger.adapter.rest

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * Framework errors (bad JSON, failed validation, missing headers) already become Problem Details through the base
 * class. This adds the API's own request errors and makes sure nothing internal leaks to clients.
 */
@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(InvalidRequestException::class)
    fun invalidRequest(e: InvalidRequestException): ProblemDetail =
        Problems.of(HttpStatus.BAD_REQUEST, "invalid-request", e.message ?: "Invalid request")

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ProblemDetail {
        log.error("Unhandled error", e)
        return Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Something went wrong on our side")
    }
}
