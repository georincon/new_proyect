package co.org.avance.ssi.wallet.core

import kotlinx.serialization.Serializable
import java.security.interfaces.ECPublicKey

/**
 * Nivel de protección de una clave (ERSo 2026-001, criterio 3). Coincide con los niveles de seguridad de Android Key Attestation
 * (Software / TrustedEnvironment / StrongBox). El proyecto NUNCA declara un nivel superior al que el custodio demuestra.
 */
enum class ProtectionLevel(val rank: Int, val attestationCode: Int) {
    SOFTWARE(0, 0),
    TEE(1, 1),
    STRONGBOX(2, 2);

    companion object {
        fun fromAttestationCode(code: Int): ProtectionLevel = entries.firstOrNull { it.attestationCode == code }
            ?: throw IllegalArgumentException("Nivel de seguridad de attestation desconocido: $code")
    }
}

/** Política de creación de claves: `minimumLevel` evita el "fallback silencioso" a software (brecha detectada en el wallet de referencia). */
data class KeyPolicy(val minimumLevel: ProtectionLevel = ProtectionLevel.SOFTWARE)

class ProtectionBelowPolicyException(val required: ProtectionLevel, val available: ProtectionLevel) :
    IllegalStateException("El custodio ofrece $available y la política exige al menos $required; no se genera la clave")

/** Descripción PÚBLICA de una clave. No existe ningún campo con material privado. */
@Serializable
data class KeyDescriptor(
    val alias: String,
    val publicKeyMultibase: String,
    val thumbprint: String,
    val protectionLevel: String,
    /** GENERATED = nació dentro del custodio; una clave importada nunca puede ser "no exportable". */
    val origin: String,
    val exportable: Boolean,
    val createdAt: String,
)

/** Evidencia de que la clave vive en el custodio: cadena de certificados (hoja primero) con el desafío incrustado. */
class AttestationEvidence(val chainDer: List<ByteArray>)

@Serializable
data class DeviceReport(val custodian: String, val platform: String, val maxProtection: String, val hardwareBacked: Boolean)

/**
 * HARDWARE SEGURO (abstracción). Contrato de NO EXPORTABILIDAD (ERSo 001, criterio 2):
 *  - ningún método devuelve una clave privada ni material equivalente (`PrivateKey`, `KeyPair`, `SecretKey`);
 *  - la única operación con la clave privada es `sign`, que recibe datos y devuelve una firma;
 *  - la prueba `NonExportabilityTest` verifica esto sobre la firma de la interfaz y sobre el comportamiento.
 * En Android se implementa con Android Keystore (StrongBox/TEE); en iOS con Secure Enclave. Aquí solo hay una simulación en software.
 */
interface KeyCustodian {
    val name: String
    /** Máximo nivel que este custodio realmente puede ofrecer (nunca exagerado). */
    val maxProtection: ProtectionLevel

    /** Genera un par DENTRO del custodio. Si `maxProtection` < `policy.minimumLevel` lanza [ProtectionBelowPolicyException]. */
    fun generate(alias: String, policy: KeyPolicy = KeyPolicy(), attestationChallenge: ByteArray? = null): KeyDescriptor
    fun descriptor(alias: String): KeyDescriptor?
    fun publicKey(alias: String): ECPublicKey

    /** ES256: devuelve r||s (64 bytes). Único uso de la clave privada. */
    fun sign(alias: String, data: ByteArray): ByteArray

    /** Evidencia generada al crear la clave con `attestationChallenge`; null si este custodio no puede demostrar nada. */
    fun attestation(alias: String): AttestationEvidence?

    /** Sellado de datos locales con una clave interna del custodio (AES-GCM); la clave de sellado tampoco sale. */
    fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray
    fun unseal(sealed: ByteArray, aad: ByteArray): ByteArray

    fun delete(alias: String)
    fun report(): DeviceReport
}
