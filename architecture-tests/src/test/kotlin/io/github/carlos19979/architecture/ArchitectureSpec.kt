package io.github.carlos19979.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import io.kotest.core.spec.style.FunSpec

private const val ROOT = "io.github.carlos19979"

class ArchitectureSpec : FunSpec({
    val production = Konsist.scopeFromProduction()

    fun filesIn(packagePrefix: String) =
        production.files.filter {
            it.packagee?.name?.startsWith(
                packagePrefix,
            ) == true
        }

    test("the domain depends on nothing but the Kotlin and Java standard libraries") {
        filesIn("$ROOT.ledger.domain").assertTrue { file ->
            file.imports.all {
                it.name.startsWith("java.") ||
                    it.name.startsWith("kotlin.") ||
                    it.name.startsWith("$ROOT.ledger.domain")
            }
        }
    }

    test("the application layer knows the domain and its own ports, never a framework or an adapter") {
        filesIn("$ROOT.ledger.application").assertFalse { file ->
            file.imports.any {
                it.name.startsWith("org.springframework") ||
                    it.name.startsWith("jakarta.") ||
                    it.name.startsWith("tools.jackson") ||
                    it.name.startsWith("org.apache.kafka") ||
                    it.name.startsWith("$ROOT.ledger.adapter") ||
                    it.name.startsWith("$ROOT.contracts")
            }
        }
    }

    test("adapters do not depend on each other") {
        val adapters = listOf("postgres", "rest", "kafka")
        adapters.forEach { adapter ->
            filesIn("$ROOT.ledger.adapter.$adapter").assertFalse { file ->
                file.imports.any { import ->
                    (adapters - adapter).any { import.name.startsWith("$ROOT.ledger.adapter.$it") }
                }
            }
        }
    }

    test("consumers only see the published contract, never the ledger's code") {
        listOf("$ROOT.activity", "$ROOT.fraud").forEach { consumer ->
            filesIn(consumer).assertFalse { file -> file.imports.any { it.name.startsWith("$ROOT.ledger") } }
        }
    }

    test("the event contract is plain data with no dependencies") {
        filesIn("$ROOT.contracts").assertTrue { file ->
            file.imports.all { it.name.startsWith("java.") || it.name.startsWith("kotlin.") }
        }
    }

    test("use cases are wired explicitly: no Spring stereotypes outside the app and adapters") {
        filesIn("$ROOT.ledger.application").plus(filesIn("$ROOT.ledger.domain")).assertFalse { file ->
            file.classes().any { cls -> cls.annotations.any { it.name in setOf("Component", "Service", "Repository") } }
        }
    }
})
