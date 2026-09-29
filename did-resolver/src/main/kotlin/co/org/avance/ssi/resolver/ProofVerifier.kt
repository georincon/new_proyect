package co.org.avance.ssi.resolver

import co.org.avance.ssi.didcore.InvalidKeyException
import co.org.avance.ssi.didcore.Jws
import co.org.avance.ssi.didcore.JwsException
import co.org.avance.ssi.didcore.Multikey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class VerificationRelationship(val term: String) {
    AUTHENTICATION("authentication"),
    ASSERTION_METHOD("assertionMethod"),
}

sealed interface VerificationResult {
    data class Valid(val did: String, val keyId: String, val payload: ByteArray) : VerificationResult
    data class Invalid(val code: String, val message: String) : VerificationResult
}

/** Verifica una prueba (JWS ES256) contra la clave Multikey resuelta y autorizada para la relación pedida. Solo lectura. */
class ProofVerifier(private val resolver: DidResolver) {

    suspend fun verify(jws: String, purpose: VerificationRelationship, expectedDid: String? = null): VerificationResult {
        val parsed = try {
            Jws.parse(jws)
        } catch (e: JwsException) {
            return VerificationResult.Invalid("MALFORMED_PROOF", e.message ?: "Prueba mal formada")
        }
        val kid = try {
            parsed.kid
        } catch (e: JwsException) {
            return VerificationResult.Invalid("MALFORMED_PROOF", e.message ?: "Falta kid")
        }
        if (!kid.contains('#')) return VerificationResult.Invalid("MALFORMED_PROOF", "El kid debe ser una DID URL con fragmento")
        val did = kid.substringBefore('#')
        if (expectedDid != null && did != expectedDid) {
            return VerificationResult.Invalid("DID_MISMATCH", "La prueba fue firmada por $did y se esperaba $expectedDid")
        }

        val resolved = resolver.resolve(did)
        if (resolved.documentMetadata.deactivated) return VerificationResult.Invalid("DEACTIVATED", "El DID está desactivado")
        val doc = resolved.didDocument
            ?: return VerificationResult.Invalid("RESOLUTION_FAILED", "${resolved.resolutionMetadata.error}: ${resolved.resolutionMetadata.message}")

        val method = resolver.dereference(kid).contentStream
            ?: return VerificationResult.Invalid("KEY_NOT_FOUND", "La clave $kid no existe en el documento")
        if (!isAuthorized(doc, kid, purpose)) {
            return VerificationResult.Invalid("KEY_NOT_AUTHORIZED", "La clave $kid no está autorizada para ${purpose.term}")
        }
        val key = try {
            Multikey.decodeP256((method["publicKeyMultibase"] as? JsonPrimitive)?.contentOrNull ?: "")
        } catch (e: InvalidKeyException) {
            return VerificationResult.Invalid("INVALID_KEY", e.message ?: "Clave inválida")
        }
        return if (Jws.verify(parsed, key)) VerificationResult.Valid(did, kid, parsed.payload)
        else VerificationResult.Invalid("INVALID_SIGNATURE", "La firma no corresponde a la clave publicada")
    }

    private fun isAuthorized(doc: JsonObject, kid: String, purpose: VerificationRelationship): Boolean =
        (doc[purpose.term] as? JsonArray).orEmpty().any {
            (it is JsonPrimitive && it.contentOrNull == kid) || (it is JsonObject && (it["id"] as? JsonPrimitive)?.contentOrNull == kid)
        }
}

class KeyResolutionException(val code: String, message: String) : RuntimeException(message)

/**
 * Resuelve la clave pública de una DID URL (`did#key-1`) para una relación de verificación concreta. Solo lectura.
 * Es lo que necesitan quienes verifican credenciales (ERSo 2026-002): la clave del EMISOR se descubre por su DID, no se configura a mano.
 */
class VerificationKeyResolver(private val resolver: DidResolver) {
    suspend fun resolve(kid: String, purpose: VerificationRelationship = VerificationRelationship.ASSERTION_METHOD): java.security.interfaces.ECPublicKey {
        if (!kid.contains('#')) throw KeyResolutionException("MALFORMED_KID", "El kid debe ser una DID URL con fragmento")
        val resolved = resolver.resolve(kid.substringBefore('#'))
        if (resolved.documentMetadata.deactivated) throw KeyResolutionException("DEACTIVATED", "El DID del emisor está desactivado")
        val doc = resolved.didDocument ?: throw KeyResolutionException("RESOLUTION_FAILED", "${resolved.resolutionMetadata.error}: ${resolved.resolutionMetadata.message}")
        val authorized = (doc[purpose.term] as? JsonArray).orEmpty().any { (it is JsonPrimitive && it.contentOrNull == kid) || (it is JsonObject && (it["id"] as? JsonPrimitive)?.contentOrNull == kid) }
        if (!authorized) throw KeyResolutionException("KEY_NOT_AUTHORIZED", "La clave $kid no está autorizada para ${purpose.term}")
        val method = resolver.dereference(kid).contentStream ?: throw KeyResolutionException("KEY_NOT_FOUND", "La clave $kid no existe en el documento")
        return try { Multikey.decodeP256((method["publicKeyMultibase"] as? JsonPrimitive)?.contentOrNull ?: "") } catch (e: InvalidKeyException) {
            throw KeyResolutionException("INVALID_KEY", e.message ?: "Clave inválida")
        }
    }
}
