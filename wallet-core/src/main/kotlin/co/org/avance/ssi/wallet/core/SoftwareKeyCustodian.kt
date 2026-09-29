package co.org.avance.ssi.wallet.core

import co.org.avance.ssi.didcore.DidKeys
import co.org.avance.ssi.didcore.Multikey
import co.org.avance.ssi.credentials.Jwk
import java.security.KeyPair
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Custodio SIMULADO en software. NO es hardware seguro: declara [ProtectionLevel.SOFTWARE] y así lo exige el resto del sistema.
 * Sirve para desarrollar y probar el contrato. Las claves privadas viven solo en un campo privado y no hay forma de sacarlas por la API.
 * (En la JVM un atacante con acceso al proceso podría usar reflexión: por eso esto no es una garantía de hardware. Ver informe ERSo 001.)
 */
open class SoftwareKeyCustodian(
    override val name: String = "software-simulated",
    private val clock: Clock = Clock.systemUTC(),
    /** Solo para pruebas: permite conocer la clave generada y comprobar que jamás aparece en ningún registro. */
    private val keyPairSource: () -> KeyPair = { DidKeys.generateP256() },
    override val maxProtection: ProtectionLevel = ProtectionLevel.SOFTWARE,
) : KeyCustodian {
    private class Entry(val pair: KeyPair, val descriptor: KeyDescriptor, val attestation: AttestationEvidence?)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val sealKey: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val random = SecureRandom()

    /** Punto de extensión: un dispositivo de laboratorio puede producir evidencia. El software puro no puede. */
    protected open fun buildAttestation(publicKey: ECPublicKey, challenge: ByteArray): AttestationEvidence? = null

    override fun generate(alias: String, policy: KeyPolicy, attestationChallenge: ByteArray?): KeyDescriptor {
        if (maxProtection.rank < policy.minimumLevel.rank) throw ProtectionBelowPolicyException(policy.minimumLevel, maxProtection)
        val pair = keyPairSource()
        val pub = pair.public as ECPublicKey
        val descriptor = KeyDescriptor(alias, Multikey.encodeP256(pub), Jwk.thumbprint(pub), maxProtection.name, "GENERATED", false, clock.instant().toString())
        val entry = Entry(pair, descriptor, attestationChallenge?.let { buildAttestation(pub, it) })
        if (entries.putIfAbsent(alias, entry) != null) throw IllegalStateException("Ya existe una clave con el alias '$alias'")
        return descriptor
    }

    override fun descriptor(alias: String): KeyDescriptor? = entries[alias]?.descriptor
    override fun publicKey(alias: String): ECPublicKey = (entry(alias).pair.public as ECPublicKey)
    override fun attestation(alias: String): AttestationEvidence? = entry(alias).attestation

    override fun sign(alias: String, data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSAinP1363Format").apply { initSign(entry(alias).pair.private); update(data) }.sign()

    override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val iv = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, sealKey, GCMParameterSpec(128, iv)); updateAAD(aad) }
        return iv + c.doFinal(plaintext)
    }

    override fun unseal(sealed: ByteArray, aad: ByteArray): ByteArray {
        require(sealed.size > 12 + 16) { "Dato sellado inválido" }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, sealKey, GCMParameterSpec(128, sealed.copyOfRange(0, 12))); updateAAD(aad) }
        return c.doFinal(sealed.copyOfRange(12, sealed.size))
    }

    override fun delete(alias: String) { entries.remove(alias) }
    override fun report() = DeviceReport(name, "JVM (simulado)", maxProtection.name, hardwareBacked = maxProtection.rank > 0)
    override fun toString(): String = "KeyCustodian($name, ${entries.size} claves)" // nunca imprime material de clave

    private fun entry(alias: String) = entries[alias] ?: throw NoSuchElementException("No existe la clave '$alias'")
}
