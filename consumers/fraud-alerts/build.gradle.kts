// Stateful rules over ledger.transfers.v1: velocity, large amounts and repeated rejections per account.
plugins {
    id("ledger.spring-boot-app")
}

dependencies {
    implementation(project(":contracts:events"))
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
    mainClass.set("io.github.carlos19979.fraud.FraudAlertsApplicationKt")
}
