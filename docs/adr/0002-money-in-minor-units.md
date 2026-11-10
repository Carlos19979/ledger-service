# ADR 0002 — Money as `Long` minor units with its currency

**Status:** accepted

## Context
Binary floating point cannot represent 0.10. `BigDecimal` is exact but carries an arbitrary scale, so `10.0` and
`10.00` are different values and rounding has to be decided at every operation.

## Decision
`Money(minorUnits: Long, currency: Currency)`. Arithmetic uses `Math.addExact` and friends, so overflow throws
instead of wrapping. Mixing currencies throws: there is no implicit FX. Converting from decimal uses
`RoundingMode.UNNECESSARY`, so `10.005 EUR` is rejected rather than rounded. On the wire amounts are decimal
strings (`"12.50"`), and in events they are minor units, never JSON numbers with decimals.

## Consequences
- Equality and comparison are trivial and exact; balances are `bigint` in PostgreSQL.
- `Long` covers ±92 quadrillion cents, far beyond any single-currency balance this service would hold.
- Currencies without minor units, or with three decimals, work through `Currency.defaultFractionDigits`.
