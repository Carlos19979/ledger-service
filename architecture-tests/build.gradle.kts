// Architecture rules as tests: the build fails if a layer reaches where it must not.
plugins {
    id("ledger.kotlin-conventions")
}

dependencies {
    testImplementation(lib("konsist"))
}

tasks.test {
    // Konsist reads the sources of every module, so rerun whenever any of them changes.
    inputs.files(rootProject.fileTree(rootDir) { include("**/src/main/kotlin/**/*.kt") })
}
