plugins {
    id("org.jetbrains.kotlinx.kover")
}

// One coverage report for the whole repository: `./gradlew koverHtmlReport`.
dependencies {
    subprojects.filter { it.buildFile.exists() }.forEach { kover(it) }
}

kover {
    reports {
        filters {
            excludes {
                annotatedBy("org.springframework.boot.autoconfigure.SpringBootApplication")
            }
        }
        verify {
            rule {
                minBound(80)
            }
        }
    }
}
