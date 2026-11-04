// Top-level build file.
//
// AGP 9 provides built-in Kotlin support, so the org.jetbrains.kotlin.android plugin is
// intentionally not applied anywhere. Declaring the Compose compiler plugin here also puts the
// matching Kotlin Gradle plugin (see libs.versions.toml) on the build classpath, and AGP uses
// that newer version for compilation.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
