// Use cases and the ports they need. Framework-free like the domain; adapters plug in from outside.
plugins {
    id("ledger.kotlin-conventions")
    `java-test-fixtures`
}

dependencies {
    api(project(":ledger:domain"))

    // In-memory implementations of every port, reused by the REST adapter's tests.
    testFixturesApi(project(":ledger:domain"))
}
