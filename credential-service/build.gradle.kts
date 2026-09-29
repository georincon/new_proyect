plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(21) }

application { mainClass.set("co.org.avance.ssi.credential.MainKt") }

dependencies {
    implementation(project(":did-core"))
    implementation(project(":did-resolver"))
    implementation(project(":credentials-core"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.java)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback)
    testImplementation(kotlin("test"))
    testImplementation(project(":wallet-core"))
    testImplementation(project(":wallet-sim"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
}

tasks.test { useJUnitPlatform() }
