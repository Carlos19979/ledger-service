// Architecture rules as tests: the build fails if a layer reaches where it must not.
plugins {
    id("ledger.kotlin-conventions")
}

dependencies {
    testImplementation(lib("konsist"))
}

tasks.test {
    // Konsist reads every module's sources, which Gradle cannot track as inputs; it is fast, so always run it.
    outputs.upToDateWhen { false }
}
