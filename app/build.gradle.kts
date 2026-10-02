import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

val localConfiguration =
    Properties().apply {
        rootProject.file("roam.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
    }
val publicConfiguration =
    listOf("ROAM_API_URL", "SUPABASE_URL", "SUPABASE_PUBLISHABLE_KEY", "STRIPE_PUBLISHABLE_KEY")
        .associateWith {
            providers.environmentVariable(it).orNull ?: localConfiguration.getProperty(it, "")
        }

// Reject misplaced server credentials before any variant can embed them in an APK.
publicConfiguration
    .getValue("SUPABASE_PUBLISHABLE_KEY")
    .takeIf { it.isNotBlank() }
    ?.let {
        check(it.startsWith("sb_publishable_")) {
            "SUPABASE_PUBLISHABLE_KEY accepts only a public sb_publishable_ key."
        }
    }

publicConfiguration
    .getValue("STRIPE_PUBLISHABLE_KEY")
    .takeIf { it.isNotBlank() }
    ?.let {
        check(it.startsWith("pk_test_") || it.startsWith("pk_live_")) {
            "STRIPE_PUBLISHABLE_KEY accepts only a public pk_ key."
        }
    }

for (name in listOf("ROAM_API_URL", "SUPABASE_URL")) {
    publicConfiguration
        .getValue(name)
        .takeIf { it.isNotBlank() }
        ?.let {
            val uri = URI(it)
            check(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "$name must not contain credentials, queries, or fragments."
            }
        }
}

fun quoted(value: String) =
    "\"" +
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r") +
        "\""

val validateReleaseConfiguration by
    tasks.registering {
        group = "verification"
        description = "Rejects unconfigured production builds and test payment keys."
        doLast {
            for (name in listOf("ROAM_API_URL", "SUPABASE_URL")) {
                val uri = runCatching { URI(publicConfiguration.getValue(name)) }.getOrNull()
                check(
                    uri != null &&
                        uri.scheme == "https" &&
                        !uri.host.isNullOrBlank() &&
                        uri.userInfo == null &&
                        uri.rawQuery == null &&
                        uri.rawFragment == null &&
                        uri.host !in setOf("localhost", "127.0.0.1", "10.0.2.2")
                ) {
                    "$name must be a public HTTPS URL for release."
                }
            }
            check(
                publicConfiguration
                    .getValue("SUPABASE_PUBLISHABLE_KEY")
                    .startsWith("sb_publishable_")
            ) {
                "Release requires a Supabase publishable key."
            }
            check(publicConfiguration.getValue("STRIPE_PUBLISHABLE_KEY").startsWith("pk_live_")) {
                "Release requires a Stripe live publishable key; use staging for test payments."
            }
        }
    }

android {
    namespace = "com.roam.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.roam.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "2.0.0"
        buildConfigField("boolean", "ROAM_CONNECTED", "false")
        publicConfiguration.forEach { (name, value) ->
            buildConfigField("String", name, quoted(value))
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }
    buildTypes {
        release {
            buildConfigField("boolean", "ROAM_CONNECTED", "true")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("benchmark") {
            initWith(getByName("release"))
            buildConfigField("boolean", "ROAM_CONNECTED", "false")
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
        create("staging") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            buildConfigField("boolean", "ROAM_CONNECTED", "true")
            matchingFallbacks += listOf("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        animationsDisabled = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

kotlin { jvmToolchain(17) }

tasks
    .matching { it.name == "preReleaseBuild" }
    .configureEach { dependsOn(validateReleaseConfiguration) }

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":network"))
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.9.5")
    implementation("com.stripe:stripe-android:23.21.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.09.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.room:room-runtime:2.8.1")
    androidTestImplementation("androidx.test:core:1.7.0")
}
