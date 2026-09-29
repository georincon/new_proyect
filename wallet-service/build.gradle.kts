plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(21) }

application { mainClass.set("co.org.avance.ssi.wallet.MainKt") }

dependencies {
    implementation(project(":did-core"))
    implementation(project(":credentials-core"))
    implementation(project(":wallet-core"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.java)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.postgres)
    implementation(libs.hikari)
    implementation(libs.logback)
    testImplementation(kotlin("test"))
    testImplementation(project(":wallet-sim"))
    testImplementation(project(":vdr-service"))
    testImplementation(libs.ktor.server.auth)
    testImplementation(libs.ktor.server.auth.jwt)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
}

tasks.test {
    useJUnitPlatform()
    environment("TEST_DB_URL", System.getenv("TEST_DB_URL") ?: "")
}
