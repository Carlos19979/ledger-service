// HTTP API: request parsing, validation and Problem Details. No business logic lives here.
plugins {
    id("ledger.spring-conventions")
}

dependencies {
    implementation(project(":ledger:application"))
    implementation(lib("spring-boot-starter-webmvc"))
    implementation(lib("spring-boot-starter-validation"))
    implementation(lib("jackson-module-kotlin"))
    implementation(lib("springdoc-webmvc-ui"))

    testImplementation(testFixtures(project(":ledger:application")))
    testImplementation(lib("spring-boot-starter-webmvc-test"))
}
