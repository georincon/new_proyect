plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(21) }

application { mainClass.set("co.org.avance.ssi.sim.MainKt") }

// Dispositivo SIMULADO: no es hardware seguro. Genera cadenas de attestation de LABORATORIO (BouncyCastle) para poder probar el verificador.
dependencies {
    api(project(":wallet-core"))
    implementation(project(":did-resolver"))
    implementation(libs.bcprov)
    implementation(libs.bcpkix)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.java)
    implementation(libs.kotlinx.coroutines.core)
    runtimeOnly("org.slf4j:slf4j-nop:2.0.16")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
