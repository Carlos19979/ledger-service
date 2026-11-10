import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

/** Typed-ish access to the version catalog from precompiled convention plugins. */
fun Project.lib(alias: String): Provider<MinimalExternalModuleDependency> =
    extensions.getByType<VersionCatalogsExtension>().named("libs").findLibrary(alias).orElseThrow {
        IllegalArgumentException("No library '$alias' in gradle/libs.versions.toml")
    }
