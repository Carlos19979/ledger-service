// Deployable Spring Boot applications.
plugins {
    id("ledger.spring-conventions")
    id("org.springframework.boot")
}

dependencies {
    implementation(lib("spring-boot-starter-actuator"))
    implementation(lib("micrometer-prometheus"))
    testImplementation(lib("spring-boot-starter-test"))
}
