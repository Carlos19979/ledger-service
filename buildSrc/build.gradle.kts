plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.plugin.kotlin)
    implementation(libs.plugin.kotlin.allopen)
    implementation(libs.plugin.spring.boot)
    implementation(libs.plugin.detekt)
    implementation(libs.plugin.ktlint)
    implementation(libs.plugin.kover)
}
