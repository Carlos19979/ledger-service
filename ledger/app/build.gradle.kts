// The deployable ledger service: wires the adapters to the use cases and runs the outbox relay.
plugins {
    id("ledger.spring-boot-app")
}

dependencies {
    implementation(project(":ledger:application"))
    implementation(project(":ledger:adapters:postgres"))
    implementation(project(":ledger:adapters:rest"))
    implementation(project(":ledger:adapters:kafka"))
    implementation(lib("spring-boot-starter-webmvc"))
    implementation(lib("spring-boot-starter-jdbc"))
    implementation(lib("spring-boot-starter-flyway"))
    implementation(lib("spring-boot-starter-kafka"))
    implementation(lib("jackson-module-kotlin"))
    runtimeOnly(lib("flyway-postgresql"))
    runtimeOnly(lib("postgresql"))

    testImplementation(lib("spring-boot-testcontainers"))
    testImplementation(lib("testcontainers-junit"))
    testImplementation(lib("testcontainers-postgresql"))
    testImplementation(lib("testcontainers-kafka"))
    testImplementation(lib("awaitility"))
}

springBoot {
    mainClass.set("io.github.carlos19979.ledger.app.LedgerApplicationKt")
}
