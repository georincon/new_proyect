package co.org.avance.ssi.credentials

import co.org.avance.ssi.didcore.Jws
import co.org.avance.ssi.didcore.JwsException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.time.Clock
import java.time.Duration

/** Divulgación selectiva: `[sal, nombre, valor]` codificada en base64url. El digest es sha-256 sobre esa cadena. */
data class Disclosure(val encoded: String, val salt: String, val name: String, val value: JsonElement) {
    val digest: String get() = B64.sha256Text(encoded)

    companion object {
        fun create(name: String, value: JsonElement, random: SecureRandom): Disclosure {
            val salt = B64.encode(ByteArray(16).also(random::nextBytes))
            val encoded = B64.encode(JsonArray(listOf(JsonPrimitive(salt), JsonPrimitive(name), value)).toString().toByteArray())
            return Disclosure(encoded, salt, name, value)
        }

        fun parse(encoded: String): Disclosure {
            val arr = try { Json.parseToJsonElement(String(B64.decode(encoded))).jsonArray } catch (e: CredentialException) { throw e } catch (e: Exception) {
                throw CredentialException("INVALID_DISCLOSURE", "Divulgación mal formada")
            }
            if (arr.size != 3) throw CredentialException("INVALID_DISCLOSURE", "Una divulgación de propiedad objeto tiene 3 elementos")
            val name = (arr[1] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw CredentialException("INVALID_DISCLOSURE", "Nombre inválido")
            if (name == "_sd" || name == "...") throw CredentialException("INVALID_DISCLOSURE", "Nombre de claim reservado")
            return Disclosure(encoded, (arr[0] as JsonPrimitive).content, name, arr[2])
        }
    }
}

/** Partes de la cadena combinada `jwt~d1~d2~[kb-jwt]`. */
class SdJwtParts(val jwt: String, val disclosures: List<String>, val keyBinding: String?) {
    /** Lo que cubre `sd_hash`: todo hasta (e incluyendo) el último `~`, sin el KB-JWT. */
    val signedPortion: String get() = jwt + "~" + disclosures.joinToString("") { "$it~" }

    companion object {
        fun parse(compact: String): SdJwtParts {
            val parts = compact.split("~")
            if (parts.size < 2) throw CredentialException("INVALID_SD_JWT", "Formato SD-JWT inválido (faltan '~')")
            val last = parts.last()
            return SdJwtParts(parts.first(), parts.subList(1, parts.size - 1), last.ifEmpty { null })
        }
    }
}

class VerifiedSdJwt(
    val issuer: String,
    val kid: String,
    val vct: String,
    val claims: JsonObject,
    val holderKey: ECPublicKey?,
    val holderBound: Boolean,
    val header: JsonObject,
)

/** EMISOR (Issuer Backend + Signature Service). Firma con la clave del emisor; NO conoce la clave privada del titular. */
class SdJwtVcIssuer(
    private val issuerId: String,
    private val kid: String,
    private val signer: (ByteArray) -> ByteArray,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
) {
    /** Todos los claims de `disclosable` quedan ocultos tras un digest; `visible` va en claro. */
    fun issue(vct: String, disclosable: JsonObject, holderKey: ECPublicKey, validity: Duration = Duration.ofDays(30), visible: JsonObject = JsonObject(emptyMap())): String {
        val disclosures = disclosable.map { (k, v) -> Disclosure.create(k, v, random) }
        val now = clock.instant()
        val payload = buildJsonObject {
            put("iss", JsonPrimitive(issuerId))
            put("iat", JsonPrimitive(now.epochSecond))
            put("exp", JsonPrimitive(now.plus(validity).epochSecond))
            put("vct", JsonPrimitive(vct))
            visible.forEach { (k, v) -> put(k, v) }
            put("cnf", buildJsonObject { put("jwk", Jwk.fromPublic(holderKey)) })
            put("_sd", JsonArray(disclosures.map { it.digest }.sorted().map(::JsonPrimitive)))
            put("_sd_alg", JsonPrimitive(CredentialProfiles.SD_ALG))
        }
        val jwt = Jws.signWith(kid, payload.toString().toByteArray(), typ = CredentialProfiles.TYP_SD_JWT_VC, signer = signer)
        return jwt + "~" + disclosures.joinToString("") { it.encoded + "~" }
    }
}

/** TITULAR: elige qué revelar y prueba posesión de la clave de `cnf` firmando un KB-JWT. La firma la hace el custodio (callback). */
object SdJwtVcHolder {
    fun disclosedNames(compact: String): List<String> = SdJwtParts.parse(compact).disclosures.map { Disclosure.parse(it).name }

    fun present(
        compact: String,
        reveal: Set<String>,
        audience: String,
        nonce: String,
        clock: Clock = Clock.systemUTC(),
        signer: (ByteArray) -> ByteArray,
    ): String {
        val parts = SdJwtParts.parse(compact)
        val chosen = parts.disclosures.filter { Disclosure.parse(it).name in reveal }
        val presentation = SdJwtParts(parts.jwt, chosen, null).signedPortion
        val kbPayload = buildJsonObject {
            put("iat", JsonPrimitive(clock.instant().epochSecond))
            put("aud", JsonPrimitive(audience))
            put("nonce", JsonPrimitive(nonce))
            put("sd_hash", JsonPrimitive(B64.sha256Text(presentation)))
        }
        val kb = Jws.signWith(null, kbPayload.toString().toByteArray(), typ = CredentialProfiles.TYP_KEY_BINDING, signer = signer)
        return presentation + kb
    }
}

/**
 * VERIFICADOR. La firma del emisor y la prueba del titular se validan por caminos INDEPENDIENTES:
 * la primera con la clave que resuelve el DID del emisor, la segunda con la clave de `cnf` (ERSo 002, criterio 4).
 */
class SdJwtVcVerifier(
    private val resolver: IssuerKeyResolver,
    private val clock: Clock = Clock.systemUTC(),
    /** Perfil estricto: `vc+sd-jwt` (perfil anterior) se rechaza salvo que se habilite explícitamente. */
    private val acceptLegacyTyp: Boolean = false,
    private val log: ExecutionLog? = null,
    private val clockSkew: Duration = Duration.ofSeconds(60),
) {
    /** Verifica solo la firma del emisor y las divulgaciones (lo que hace la cartera al RECIBIR). */
    suspend fun verifyIssuance(compact: String): VerifiedSdJwt = verify(SdJwtParts.parse(compact), requireKeyBinding = false, aud = null, nonce = null, maxAge = Duration.ZERO)

    /** Verifica una presentación completa: emisor + divulgaciones + KB-JWT (audiencia, nonce y sd_hash). */
    suspend fun verifyPresentation(compact: String, audience: String, nonce: String, maxAge: Duration = Duration.ofMinutes(5)): VerifiedSdJwt =
        verify(SdJwtParts.parse(compact), requireKeyBinding = true, aud = audience, nonce = nonce, maxAge = maxAge)

    private suspend fun verify(parts: SdJwtParts, requireKeyBinding: Boolean, aud: String?, nonce: String?, maxAge: Duration): VerifiedSdJwt {
        val jws = try { Jws.parse(parts.jwt) } catch (e: JwsException) { throw CredentialException("INVALID_JWS", e.message ?: "JWS inválido") }
        val typ = jws.typ
        val typOk = typ == CredentialProfiles.TYP_SD_JWT_VC || (acceptLegacyTyp && typ == CredentialProfiles.TYP_SD_JWT_VC_LEGACY)
        if (!typOk) throw CredentialException("INVALID_TYP", "typ '$typ' no es '${CredentialProfiles.TYP_SD_JWT_VC}' (el perfil anterior 'vc+sd-jwt' ${if (acceptLegacyTyp) "está habilitado" else "no se acepta"})")
        val kid = try { jws.kid } catch (e: JwsException) { throw CredentialException("MISSING_KID", "El JWT del emisor no lleva 'kid'") }
        val payload = try { Json.parseToJsonElement(String(jws.payload)).jsonObject } catch (e: Exception) { throw CredentialException("INVALID_PAYLOAD", "Payload no es JSON") }
        val iss = (payload["iss"] as? JsonPrimitive)?.contentOrNull ?: throw CredentialException("MISSING_ISS", "Falta 'iss'")
        if (kid.substringBefore('#') != iss) throw CredentialException("ISSUER_KEY_MISMATCH", "El kid '$kid' no pertenece al emisor '$iss'")

        val issuerKey = try { resolver.resolve(kid) } catch (e: CredentialException) { throw e } catch (e: Exception) {
            throw CredentialException("ISSUER_KEY_UNRESOLVED", "No se pudo resolver la clave del emisor: ${e.message}")
        }
        if (!Jws.verify(jws, issuerKey)) throw CredentialException("INVALID_ISSUER_SIGNATURE", "La firma del emisor no es válida")

        val now = clock.instant().epochSecond
        val skew = clockSkew.seconds
        (payload["exp"] as? JsonPrimitive)?.longOrNull?.let { if (now - skew >= it) throw CredentialException("EXPIRED", "La credencial expiró") }
            ?: throw CredentialException("MISSING_EXP", "Falta 'exp'")
        (payload["iat"] as? JsonPrimitive)?.longOrNull?.let { if (it > now + skew) throw CredentialException("NOT_YET_VALID", "iat en el futuro") }
        val vct = (payload["vct"] as? JsonPrimitive)?.contentOrNull ?: throw CredentialException("MISSING_VCT", "Falta 'vct'")
        if ((payload["_sd_alg"] as? JsonPrimitive)?.contentOrNull != CredentialProfiles.SD_ALG) throw CredentialException("UNSUPPORTED_SD_ALG", "Solo se admite _sd_alg=${CredentialProfiles.SD_ALG}")

        val claims = revealClaims(payload, parts.disclosures)
        val cnfJwk = (payload["cnf"] as? JsonObject)?.get("jwk") as? JsonObject
        val holderKey = cnfJwk?.let { Jwk.toPublic(it) }
        var bound = false
        if (requireKeyBinding) {
            val kbCompact = parts.keyBinding ?: throw CredentialException("KEY_BINDING_REQUIRED", "La presentación no incluye KB-JWT")
            if (holderKey == null) throw CredentialException("NO_CNF", "La credencial no tiene 'cnf': no se puede verificar la prueba del titular")
            verifyKeyBinding(kbCompact, holderKey, parts.signedPortion, aud!!, nonce!!, maxAge, now, skew)
            bound = true
        }
        log?.record(Observed("sd-jwt-vc emisor", CredentialProfiles.FORMAT_SD_JWT_VC, typ, jws.header["alg"]!!.jsonPrimitive.content, payload["_sd_alg"]!!.jsonPrimitive.content))
        return VerifiedSdJwt(iss, kid, vct, claims, holderKey, bound, jws.header)
    }

    private fun verifyKeyBinding(kb: String, holderKey: ECPublicKey, signedPortion: String, aud: String, nonce: String, maxAge: Duration, now: Long, skew: Long) {
        val jws = try { Jws.parse(kb) } catch (e: JwsException) { throw CredentialException("INVALID_KB_JWT", e.message ?: "KB-JWT inválido") }
        if (jws.typ != CredentialProfiles.TYP_KEY_BINDING) throw CredentialException("INVALID_KB_TYP", "typ del KB-JWT debe ser ${CredentialProfiles.TYP_KEY_BINDING}")
        if (!Jws.verify(jws, holderKey)) throw CredentialException("INVALID_HOLDER_PROOF", "La prueba de posesión del titular no es válida para la clave de 'cnf'")
        val p = try { Json.parseToJsonElement(String(jws.payload)).jsonObject } catch (e: Exception) { throw CredentialException("INVALID_KB_JWT", "Payload del KB-JWT no es JSON") }
        if ((p["aud"] as? JsonPrimitive)?.contentOrNull != aud) throw CredentialException("AUDIENCE_MISMATCH", "aud del KB-JWT no es la esperada")
        if ((p["nonce"] as? JsonPrimitive)?.contentOrNull != nonce) throw CredentialException("NONCE_MISMATCH", "nonce del KB-JWT no coincide (¿repetición?)")
        if ((p["sd_hash"] as? JsonPrimitive)?.contentOrNull != B64.sha256Text(signedPortion)) throw CredentialException("SD_HASH_MISMATCH", "sd_hash no corresponde a las divulgaciones presentadas")
        val iat = (p["iat"] as? JsonPrimitive)?.longOrNull ?: throw CredentialException("MISSING_IAT", "El KB-JWT no lleva iat")
        if (iat > now + skew || now - iat > maxAge.seconds + skew) throw CredentialException("KB_JWT_STALE", "El KB-JWT no es reciente")
        log?.record(Observed("sd-jwt-vc prueba del titular (kb-jwt)", CredentialProfiles.FORMAT_SD_JWT_VC, jws.typ, jws.header["alg"]!!.jsonPrimitive.content, null))
    }

    private fun revealClaims(payload: JsonObject, disclosures: List<String>): JsonObject {
        val sd = (payload["_sd"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty().toSet()
        val seen = mutableSetOf<String>()
        val revealed = linkedMapOf<String, JsonElement>()
        for (enc in disclosures) {
            val d = Disclosure.parse(enc)
            if (d.digest !in sd) throw CredentialException("UNMATCHED_DISCLOSURE", "Divulgación sin digest en la credencial: '${d.name}'")
            if (!seen.add(d.digest)) throw CredentialException("DUPLICATE_DISCLOSURE", "Divulgación repetida: '${d.name}'")
            if (d.name in revealed || payload.containsKey(d.name)) throw CredentialException("CLAIM_COLLISION", "El claim '${d.name}' ya existe")
            revealed[d.name] = d.value
        }
        val plain = payload.filterKeys { it !in setOf("_sd", "_sd_alg") }
        return JsonObject(plain + revealed)
    }
}
