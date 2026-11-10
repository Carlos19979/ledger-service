// PostgreSQL implementations of the persistence, idempotency and outbox ports. Hand-written SQL, no ORM.
plugins {
    id("ledger.spring-conventions")
}

dependencies {
    implementation(project(":ledger:application"))
    implementation(lib("spring-boot-starter-jdbc"))
    runtimeOnly(lib("postgresql"))

    testImplementation(lib("testcontainers-postgresql"))
    testImplementation(lib("flyway-postgresql"))
    testImplementation(lib("postgresql"))
}
