package io.github.carlos19979.ledger.adapter.postgres

import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

internal fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

internal fun ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()

internal fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }

internal fun Instant.toTimestamp(): OffsetDateTime = atOffset(ZoneOffset.UTC)
