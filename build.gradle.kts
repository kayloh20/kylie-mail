buildscript {
    dependencies {
        // AGP 9 compiles Kotlin itself (built-in Kotlin) and pulls in an older Kotlin Gradle plugin by default.
        // Putting this one on the classpath picks the Kotlin version from the version catalog instead.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
