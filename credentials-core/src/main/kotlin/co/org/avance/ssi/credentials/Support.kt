package co.org.avance.ssi.credentials

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64

/** Error de dominio con código estable (los servicios lo traducen a errores de protocolo). */
class CredentialException(val code: String, message: String) : RuntimeException(message)

object B64 {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    fun encode(bytes: ByteArray): String = enc.encodeToString(bytes)
    fun decode(text: String): ByteArray = try { dec.decode(text) } catch (e: IllegalArgumentException) { throw CredentialException("INVALID_ENCODING", "Base64url inválido") }
    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    /** Resumen sha-256 en base64url de un texto ASCII (así se calculan los digest de SD-JWT y sd_hash). */
    fun sha256Text(text: String): String = encode(sha256(text.toByteArray(Charsets.US_ASCII)))
}

/** JWK de clave pública EC P-256 (la que va en `cnf` y en las pruebas de posesión). Nunca contiene el parámetro privado `d`. */
object Jwk {
    private val spec: ECParameterSpec = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
    private val p: BigInteger = (spec.curve.field as ECFieldFp).p

    fun fromPublic(key: ECPublicKey): JsonObject = buildJsonObject {
        put("kty", JsonPrimitive("EC")); put("crv", JsonPrimitive("P-256"))
        put("x", JsonPrimitive(B64.encode(fixed32(key.w.affineX)))); put("y", JsonPrimitive(B64.encode(fixed32(key.w.affineY))))
    }

    fun toPublic(jwk: JsonObject): ECPublicKey {
        if (jwk.containsKey("d")) throw CredentialException("PRIVATE_KEY_IN_JWK", "La JWK contiene material privado")
        if ((jwk["kty"] as? JsonPrimitive)?.contentOrNull != "EC" || (jwk["crv"] as? JsonPrimitive)?.contentOrNull != "P-256") {
            throw CredentialException("UNSUPPORTED_KEY", "Solo se admite EC P-256")
        }
        val x = BigInteger(1, B64.decode((jwk["x"] as? JsonPrimitive)?.contentOrNull ?: throw CredentialException("INVALID_JWK", "Falta x")))
        val y = BigInteger(1, B64.decode((jwk["y"] as? JsonPrimitive)?.contentOrNull ?: throw CredentialException("INVALID_JWK", "Falta y")))
        val lhs = y.multiply(y).mod(p)
        val rhs = x.pow(3).add(spec.curve.a.multiply(x)).add(spec.curve.b).mod(p)
        if (x >= p || y >= p || lhs != rhs) throw CredentialException("INVALID_JWK", "El punto no pertenece a la curva P-256")
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), spec)) as ECPublicKey
    }

    /** Huella RFC 7638 (identifica una clave sin ambigüedad; se usa en la matriz de claves por rol). */
    fun thumbprint(key: ECPublicKey): String {
        val j = fromPublic(key)
        val canonical = """{"crv":"P-256","kty":"EC","x":"${(j["x"] as JsonPrimitive).content}","y":"${(j["y"] as JsonPrimitive).content}"}"""
        return B64.encode(B64.sha256(canonical.toByteArray(Charsets.UTF_8)))
    }

    private fun fixed32(v: BigInteger): ByteArray {
        val b = v.toByteArray().let { if (it.size > 1 && it[0].toInt() == 0) it.copyOfRange(1, it.size) else it }
        return ByteArray(32 - b.size) + b
    }
}

/** Resuelve la clave pública de un emisor a partir del `kid` (URL DID). En servicios se implementa con el consumidor conforme (ERSo 007). */
fun interface IssuerKeyResolver {
    suspend fun resolve(kid: String): ECPublicKey
}
