// Publishes outbox messages to Kafka and defines how domain events map onto the public contract.
plugins {
    id("ledger.spring-conventions")
}

dependencies {
    implementation(project(":ledger:application"))
    api(project(":contracts:events"))
    implementation(lib("spring-boot-starter-kafka"))
    implementation(lib("jackson-module-kotlin"))
}
