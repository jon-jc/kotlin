plugins {
    id("com.diffplug.spotless") version "7.2.1"
    id("com.android.application") version "8.13.0" apply false
    id("com.android.library") version "8.13.0" apply false
    id("com.android.test") version "8.13.0" apply false
    kotlin("android") version "2.2.20" apply false
    kotlin("jvm") version "2.2.20" apply false
    kotlin("kapt") version "2.2.20" apply false
    kotlin("plugin.compose") version "2.2.20" apply false
    kotlin("plugin.serialization") version "2.2.20" apply false
}

spotless {
    kotlin {
        target("core/src/**/*.kt", "data/src/**/*.kt", "app/src/**/*.kt", "benchmark/src/**/*.kt")
        ktfmt("0.58").kotlinlangStyle()
    }
    kotlinGradle {
        target("*.gradle.kts", "*/build.gradle.kts")
        ktfmt("0.58").kotlinlangStyle()
    }
}
