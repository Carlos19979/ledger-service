// Modules that use Spring: the Boot BOM pins every Spring, Jackson, Kafka and Testcontainers version.
plugins {
    id("ledger.kotlin-conventions")
    id("org.jetbrains.kotlin.plugin.spring")
}

dependencies {
    implementation(platform(lib("spring-boot-bom")))
    testImplementation(platform(lib("spring-boot-bom")))
    implementation(lib("kotlin-reflect"))
}
