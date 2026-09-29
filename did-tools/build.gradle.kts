plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(21) }

application { mainClass.set("co.org.avance.ssi.tools.MainKt") }

dependencies {
    implementation(project(":did-core"))
    implementation(project(":did-resolver"))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.java)
    runtimeOnly("org.slf4j:slf4j-nop:2.0.16")
    implementation(libs.kotlinx.coroutines.core)
}
