plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

kotlin { jvmToolchain(17) }

application { mainClass.set("com.roam.server.ApplicationKt") }

dependencies {
    implementation(project(":core"))
    implementation("io.ktor:ktor-server-core-jvm:3.3.3")
    implementation("io.ktor:ktor-server-netty-jvm:3.3.3")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:3.3.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:3.3.3")
    implementation("io.ktor:ktor-server-status-pages-jvm:3.3.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.zaxxer:HikariCP:6.3.2")
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("com.nimbusds:nimbus-jose-jwt:10.5")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.3.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.test {
    maxHeapSize = "384m"
    // Integration tests are deliberately required: no silent skip or in-memory substitute.
    environment(
        "ROAM_TEST_DATABASE_URL",
        System.getenv("ROAM_TEST_DATABASE_URL") ?: "jdbc:postgresql://localhost:55432/roam_test",
    )
    environment("ROAM_TEST_DATABASE_USER", System.getenv("ROAM_TEST_DATABASE_USER") ?: "roam_test")
    environment(
        "ROAM_TEST_DATABASE_PASSWORD",
        System.getenv("ROAM_TEST_DATABASE_PASSWORD") ?: "local-integration-only",
    )
}
