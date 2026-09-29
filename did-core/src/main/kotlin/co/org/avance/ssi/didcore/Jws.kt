package co.org.avance.ssi.didcore

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.util.Base64

class JwsException(message: String) : IllegalArgumentException(message)

/** JWS compacto con ES256 (ECDSA P-256 + SHA-256, firma r||s de 64 bytes). Es el formato de "prueba" del proyecto. */
object Jws {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    private const val SIG_ALG = "SHA256withECDSAinP1363Format"

    class Parsed(val header: JsonObject, val payload: ByteArray, val signingInput: ByteArray, val signature: ByteArray) {
        val kid: String get() = header["kid"]?.jsonPrimitive?.contentOrNull ?: throw JwsException("Falta 'kid' en el encabezado")
        val typ: String? get() = header["typ"]?.jsonPrimitive?.contentOrNull
    }

    /** `kid` y `typ` son opcionales: las pruebas del VDR llevan solo `kid`; SD-JWT VC exige `typ` (dc+sd-jwt, kb+jwt). */
    fun sign(privateKey: PrivateKey, kid: String?, payload: ByteArray, typ: String? = null, extraHeader: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()): String =
        signWith(kid, payload, typ, extraHeader) { input ->
            Signature.getInstance(SIG_ALG).apply { initSign(privateKey); update(input) }.sign()
        }

    /**
     * Firma delegando la operación criptográfica en `signer` (entrada = "cabecera.contenido" en ASCII; salida = r||s de 64 bytes).
     * Así quien arma el JWS nunca toca la clave privada: la usa el custodio (hardware seguro, en un dispositivo real).
     */
    fun signWith(kid: String?, payload: ByteArray, typ: String? = null, extraHeader: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(), signer: (ByteArray) -> ByteArray): String {
        val header = buildJsonObject {
            put("alg", JsonPrimitive("ES256"))
            if (typ != null) put("typ", JsonPrimitive(typ))
            if (kid != null) put("kid", JsonPrimitive(kid))
            extraHeader.forEach { (k, v) -> put(k, v) }
        }
        val signingInput = enc.encodeToString(header.toString().toByteArray()) + "." + enc.encodeToString(payload)
        val sig = signer(signingInput.toByteArray(Charsets.US_ASCII))
        require(sig.size == 64) { "El firmante debe devolver una firma ES256 de 64 bytes (r||s)" }
        return signingInput + "." + enc.encodeToString(sig)
    }

    fun parse(compact: String): Parsed {
        val parts = compact.split(".")
        if (parts.size != 3) throw JwsException("JWS compacto mal formado")
        try {
            val header = Json.parseToJsonElement(String(dec.decode(parts[0]))).jsonObject
            if (header["alg"]?.jsonPrimitive?.contentOrNull != "ES256") throw JwsException("Algoritmo no permitido (solo ES256)")
            val signature = dec.decode(parts[2])
            if (signature.size != 64) throw JwsException("Firma ES256 con longitud inválida")
            return Parsed(header, dec.decode(parts[1]), (parts[0] + "." + parts[1]).toByteArray(Charsets.US_ASCII), signature)
        } catch (e: JwsException) {
            throw e
        } catch (e: Exception) {
            throw JwsException("JWS mal formado: ${e.message}")
        }
    }

    fun verify(parsed: Parsed, key: ECPublicKey): Boolean = try {
        Signature.getInstance(SIG_ALG).apply {
            initVerify(key)
            update(parsed.signingInput)
        }.verify(parsed.signature)
    } catch (e: Exception) {
        false
    }
}
