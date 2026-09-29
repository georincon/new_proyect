package co.org.avance.ssi.credential

import co.org.avance.ssi.credentials.ArtifactInspector
import co.org.avance.ssi.credentials.B64
import co.org.avance.ssi.credentials.CredentialException
import co.org.avance.ssi.credentials.CredentialProfiles
import co.org.avance.ssi.credentials.ExecutionLog
import co.org.avance.ssi.credentials.Jwk
import co.org.avance.ssi.credentials.MdocIssuer
import co.org.avance.ssi.credentials.SdJwtVcIssuer
import co.org.avance.ssi.didcore.Jws
import co.org.avance.ssi.didcore.JwsException
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

const val PRE_AUTHORIZED_GRANT = "urn:ietf:params:oauth:grant-type:pre-authorized_code"
const val CONFIG_SDJWT = "AcademicCredential_dc+sd-jwt"
const val CONFIG_MDOC = "AcademicCredential_mso_mdoc"
const val VCT_ACADEMIC = "urn:avance:credential:academic:1"
const val DOCTYPE_ACADEMIC = "org.avance.academic.1"

/**
 * ISSUER BACKEND + SIGNATURE SERVICE (OpenID4VCI 1.0, flujo de código pre-autorizado). El servicio firma con la clave del EMISOR;
 * la clave del TITULAR solo aparece como clave PÚBLICA en la prueba de posesión y termina en `cnf` (SD-JWT) o `deviceKeyInfo` (mdoc).
 * Límites declarados: sin DPoP, sin cifrado de solicitud/respuesta (JWE), sin endpoint diferido ni de notificación, estado en memoria.
 */
class IssuerService(
    private val cfg: CredentialConfig,
    private val signer: (ByteArray) -> ByteArray,
    private val clock: Clock = Clock.systemUTC(),
    val log: ExecutionLog = ExecutionLog(),
) {
    private class Offer(val configIds: Set<String>, val claims: JsonObject, val txCodeHash: ByteArray?, val expires: Instant, var attempts: Int = 0, var used: Boolean = false)
    private class TokenState(val remaining: MutableSet<String>, val claims: JsonObject, val expires: Instant)

    private val offers = ConcurrentHashMap<String, Offer>()
    private val tokens = ConcurrentHashMap<String, TokenState>()
    private val nonces = ConcurrentHashMap<String, Instant>()
    private val random = SecureRandom()
    private fun rnd(n: Int = 32) = B64.encode(ByteArray(n).also(random::nextBytes))
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())

    private val sdJwt = SdJwtVcIssuer(cfg.issuerDid, cfg.issuerKid, signer, clock)
    private val mdoc = MdocIssuer(cfg.issuerKid, signer, clock)

    // ---------------------------------------------------------------- metadatos
    fun metadata(): JsonObject = buildJsonObject {
        put("credential_issuer", JsonPrimitive(cfg.issuerId))
        put("credential_endpoint", JsonPrimitive("${cfg.issuerId}/credential"))
        put("nonce_endpoint", JsonPrimitive("${cfg.issuerId}/nonce"))
        put("credential_configurations_supported", buildJsonObject {
            put(CONFIG_SDJWT, buildJsonObject {
                put("format", JsonPrimitive(CredentialProfiles.FORMAT_SD_JWT_VC)); put("vct", JsonPrimitive(VCT_ACADEMIC)); put("scope", JsonPrimitive("academic_sdjwt"))
                put("cryptographic_binding_methods_supported", JsonArray(listOf(JsonPrimitive("jwk"))))
                put("credential_signing_alg_values_supported", JsonArray(listOf(JsonPrimitive(CredentialProfiles.JOSE_ALG))))
                put("proof_types_supported", proofTypes())
                put("credential_metadata", buildJsonObject { put("display", JsonArray(listOf(buildJsonObject { put("name", JsonPrimitive("Credencial académica")); put("locale", JsonPrimitive("es")) }))) })
            })
            put(CONFIG_MDOC, buildJsonObject {
                put("format", JsonPrimitive(CredentialProfiles.FORMAT_MDOC)); put("doctype", JsonPrimitive(DOCTYPE_ACADEMIC)); put("scope", JsonPrimitive("academic_mdoc"))
                put("cryptographic_binding_methods_supported", JsonArray(listOf(JsonPrimitive("cose_key"))))
                put("credential_signing_alg_values_supported", JsonArray(listOf(JsonPrimitive(CredentialProfiles.COSE_ALG_ES256))))
                put("proof_types_supported", proofTypes())
                put("credential_metadata", buildJsonObject { put("display", JsonArray(listOf(buildJsonObject { put("name", JsonPrimitive("Credencial académica (mdoc)")); put("locale", JsonPrimitive("es")) }))) })
            })
        })
    }

    private fun proofTypes() = buildJsonObject { put("jwt", buildJsonObject { put("proof_signing_alg_values_supported", JsonArray(listOf(JsonPrimitive(CredentialProfiles.JOSE_ALG)))) }) }

    /** El emisor es su propio servidor de autorización (flujo pre-autorizado). */
    fun authorizationServerMetadata(): JsonObject = buildJsonObject {
        put("issuer", JsonPrimitive(cfg.issuerId)); put("token_endpoint", JsonPrimitive("${cfg.issuerId}/token"))
        put("grant_types_supported", JsonArray(listOf(JsonPrimitive(PRE_AUTHORIZED_GRANT))))
        put("pre-authorized_grant_anonymous_access_supported", JsonPrimitive(true))
    }

    // ---------------------------------------------------------------- oferta (portal de administración del emisor)
    class OfferView(val offer: JsonObject, val uri: String, val txCode: String?)

    fun createOffer(configIds: List<String>, claims: JsonObject, withTxCode: Boolean): OfferView {
        if (configIds.isEmpty() || configIds.any { it != CONFIG_SDJWT && it != CONFIG_MDOC }) throw ProtocolError(HttpStatusCode.BadRequest, "invalid_request", "credential_configuration_ids desconocidos")
        if (claims.isEmpty() || claims.values.any { (it as? JsonPrimitive)?.isString != true }) throw ProtocolError(HttpStatusCode.BadRequest, "invalid_request", "claims debe ser un objeto de textos")
        val code = rnd(); val tx = if (withTxCode) String.format("%06d", random.nextInt(1_000_000)) else null
        offers[code] = Offer(configIds.toSet(), claims, tx?.let(::sha), clock.instant().plusSeconds(cfg.offerTtlSeconds))
        val offer = buildJsonObject {
            put("credential_issuer", JsonPrimitive(cfg.issuerId))
            put("credential_configuration_ids", JsonArray(configIds.map(::JsonPrimitive)))
            put("grants", buildJsonObject { put(PRE_AUTHORIZED_GRANT, buildJsonObject {
                put("pre-authorized_code", JsonPrimitive(code))
                if (tx != null) put("tx_code", buildJsonObject { put("input_mode", JsonPrimitive("numeric")); put("length", JsonPrimitive(6)) })
            }) })
        }
        return OfferView(offer, "openid-credential-offer://?credential_offer=" + URLEncoder.encode(offer.toString(), Charsets.UTF_8), tx)
    }

    // ---------------------------------------------------------------- endpoint de token (OAuth 2.0, pre-authorized_code)
    fun token(form: Map<String, String?>): JsonObject {
        if (form["grant_type"] != PRE_AUTHORIZED_GRANT) throw ProtocolError(HttpStatusCode.BadRequest, "unsupported_grant_type", "Solo $PRE_AUTHORIZED_GRANT")
        val code = form["pre-authorized_code"] ?: throw ProtocolError(HttpStatusCode.BadRequest, "invalid_request", "Falta pre-authorized_code")
        val offer = offers[code]
        val now = clock.instant()
        if (offer == null || offer.used || now.isAfter(offer.expires)) throw ProtocolError(HttpStatusCode.BadRequest, "invalid_grant", "Código pre-autorizado inválido, usado o vencido")
        synchronized(offer) {
            if (offer.txCodeHash != null) {
                val given = form["tx_code"]
                if (given == null || !MessageDigest.isEqual(sha(given), offer.txCodeHash)) {
                    if (++offer.attempts >= 3) offer.used = true // 3 fallos y la oferta se quema: evita fuerza bruta del código
                    throw ProtocolError(HttpStatusCode.BadRequest, "invalid_grant", "tx_code inválido")
                }
            }
            offer.used = true
        }
        val access = rnd()
        tokens[B64.encode(sha(access))] = TokenState(offer.configIds.toMutableSet(), offer.claims, now.plusSeconds(cfg.accessTokenTtlSeconds))
        return buildJsonObject { put("access_token", JsonPrimitive(access)); put("token_type", JsonPrimitive("Bearer")); put("expires_in", JsonPrimitive(cfg.accessTokenTtlSeconds)) }
    }

    // ---------------------------------------------------------------- endpoint de nonce (OpenID4VCI 1.0)
    fun newNonce(): JsonObject {
        val n = rnd(); nonces[n] = clock.instant().plusSeconds(cfg.nonceTtlSeconds)
        return buildJsonObject { put("c_nonce", JsonPrimitive(n)) }
    }

    // ---------------------------------------------------------------- endpoint de credencial
    /** Se autentica ANTES de leer el cuerpo: una solicitud sin token válido no debe llegar a procesarse. */
    fun requireAccessToken(bearer: String?) { state(bearer) }

    private fun state(bearer: String?): TokenState {
        val s = bearer?.let { tokens[B64.encode(sha(it))] }
        if (s == null || clock.instant().isAfter(s.expires)) throw ProtocolError(HttpStatusCode.Unauthorized, "invalid_token", "Token de acceso ausente, inválido o vencido")
        return s
    }

    fun credential(bearer: String?, body: JsonObject): JsonObject {
        val state = state(bearer)
        val configId = (body["credential_configuration_id"] as? JsonPrimitive)?.contentOrNull ?: throw ProtocolError(HttpStatusCode.BadRequest, "invalid_credential_request", "Falta credential_configuration_id")
        if (configId != CONFIG_SDJWT && configId != CONFIG_MDOC) throw ProtocolError(HttpStatusCode.BadRequest, "unknown_credential_configuration", "Configuración desconocida")
        if (configId !in state.remaining) throw ProtocolError(HttpStatusCode.BadRequest, "invalid_credential_request", "Esa credencial no fue autorizada por la oferta o ya se emitió")
        val proofs = (body["proofs"] as? JsonObject) ?: throw ProtocolError(HttpStatusCode.BadRequest, "invalid_credential_request", "Falta 'proofs'")
        val jwts = (proofs["jwt"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
        if (proofs.size != 1 || jwts.size != 1) throw ProtocolError(HttpStatusCode.BadRequest, "invalid_credential_request", "'proofs' debe traer exactamente una prueba de tipo jwt")
        val holderKey = verifyProof(jwts.single())

        val credential: String = try {
            when (configId) {
                CONFIG_SDJWT -> sdJwt.issue(VCT_ACADEMIC, state.claims, holderKey).also { log.record(ArtifactInspector.sdJwt(it, "sd-jwt-vc emitido")) }
                else -> {
                    val bytes = mdoc.issue(DOCTYPE_ACADEMIC, mapOf(DOCTYPE_ACADEMIC to state.claims.mapValues { it.value.jsonPrimitive.content }), holderKey)
                    log.record(ArtifactInspector.mdoc(bytes, "mso_mdoc emitido"))
                    B64.encode(bytes) // OpenID4VCI 1.0, Anexo A.2: base64url del IssuerSigned CBOR
                }
            }
        } catch (e: CredentialException) { throw ProtocolError(HttpStatusCode.BadRequest, "invalid_credential_request", e.message ?: "No se pudo emitir") }
        state.remaining.remove(configId)
        return buildJsonObject { put("credentials", JsonArray(listOf(buildJsonObject { put("credential", JsonPrimitive(credential)) }))) }
    }

    /** Prueba de posesión del titular: JWT `openid4vci-proof+jwt` firmado con la clave que quedará en `cnf`. Devuelve esa clave PÚBLICA. */
    private fun verifyProof(jwt: String): ECPublicKey {
        fun bad(code: String, msg: String) = ProtocolError(HttpStatusCode.BadRequest, code, msg)
        val p = try { Jws.parse(jwt) } catch (e: JwsException) { throw bad("invalid_proof", "Prueba mal formada: ${e.message}") }
        if (p.typ != CredentialProfiles.TYP_PROOF_OF_POSSESSION) throw bad("invalid_proof", "typ debe ser ${CredentialProfiles.TYP_PROOF_OF_POSSESSION}")
        val jwk = (p.header["jwk"] as? JsonObject) ?: throw bad("invalid_proof", "La cabecera debe traer la clave pública en 'jwk'")
        val key = try { Jwk.toPublic(jwk) } catch (e: CredentialException) { throw bad("invalid_proof", e.message ?: "jwk inválida") }
        if (!Jws.verify(p, key)) throw bad("invalid_proof", "La firma de la prueba no corresponde a la clave 'jwk'")
        val payload = try { Json.parseToJsonElement(String(p.payload)).jsonObject } catch (e: Exception) { throw bad("invalid_proof", "Payload no es JSON") }
        if ((payload["aud"] as? JsonPrimitive)?.contentOrNull != cfg.issuerId) throw bad("invalid_proof", "aud debe ser el identificador del emisor")
        val iat = (payload["iat"] as? JsonPrimitive)?.longOrNull ?: throw bad("invalid_proof", "Falta iat")
        val now = clock.instant().epochSecond
        if (iat > now + 60 || now - iat > cfg.proofMaxAgeSeconds) throw bad("invalid_proof", "La prueba no es reciente")
        val nonce = (payload["nonce"] as? JsonPrimitive)?.contentOrNull ?: throw ProtocolError(HttpStatusCode.BadRequest, "invalid_nonce", "Falta el c_nonce; pídalo en el endpoint de nonce")
        val exp = nonces.remove(nonce) // de un solo uso
        if (exp == null || clock.instant().isAfter(exp)) throw ProtocolError(HttpStatusCode.BadRequest, "invalid_nonce", "c_nonce desconocido, usado o vencido")
        return key
    }

}
