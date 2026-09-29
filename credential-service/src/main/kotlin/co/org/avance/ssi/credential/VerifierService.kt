package co.org.avance.ssi.credential

import co.org.avance.ssi.credentials.B64
import co.org.avance.ssi.credentials.CredentialException
import co.org.avance.ssi.credentials.CredentialProfiles
import co.org.avance.ssi.credentials.SdJwtVcVerifier
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Consulta DCQL mínima (OpenID4VP 1.0): una credencial dc+sd-jwt, con `vct_values` y rutas de claims de primer nivel. */
class Dcql(val id: String, val vctValues: List<String>, val claimPaths: List<String>, val raw: JsonObject) {
    companion object {
        fun parse(q: JsonObject): Dcql {
            val creds = (q["credentials"] as? JsonArray) ?: throw ProtocolError(HttpStatusCode.BadRequest, "invalid_request", "dcql_query.credentials es obligatorio")
            if (creds.size != 1) throw ProtocolError(HttpStatusCode.BadRequest, "invalid_request", "Este verificador admite una sola credencial por consulta")
            val c = creds.single().jsonObject
            val id = (c["id"] as? JsonPrimitive)?.contentOrNull ?: throw ProtocolError(HttpStatusCode.BadRequest, "invalid_request", "Falta credentials[].id")
            val format = (c["format"] as? JsonPrimitive)?.contentOrNull
            if (format != CredentialProfiles.FORMAT_SD_JWT_VC) throw ProtocolError(HttpStatusCode.BadRequest, "invalid_request", "Formato no soportado para presentación: '$format' (solo ${CredentialProfiles.FORMAT_SD_JWT_VC}; la presentación mdoc no está implementada)")
            val vct = ((c["meta"] as? JsonObject)?.get("vct_values") as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
            val paths = (c["claims"] as? JsonArray)?.map { cl -> (cl.jsonObject["path"] as JsonArray).single().jsonPrimitive.content }.orEmpty()
            return Dcql(id, vct, paths, q)
        }
    }
}

/**
 * VERIFIER BACKEND (OpenID4VP 1.0, `response_mode=direct_post`, prefijo de cliente `redirect_uri`). Valida la presentación SD-JWT VC:
 * firma del emisor (resolviendo su DID), divulgaciones, KB-JWT (aud = client_id, nonce de la solicitud, sd_hash) y la consulta DCQL.
 * Límites declarados: sin firma de la solicitud (JAR), sin cifrado de la respuesta (direct_post.jwt), sin revocación (status list), sin mdoc.
 */
class VerifierService(
    private val cfg: CredentialConfig,
    private val verifier: SdJwtVcVerifier,
    private val clock: Clock = Clock.systemUTC(),
) {
    class VpRequest(val id: String, val nonce: String, val state: String, val dcql: Dcql, val expires: Instant) {
        @Volatile var status = "PENDING"
        @Volatile var reason: String? = null
        @Volatile var claims: JsonObject? = null
        @Volatile var issuer: String? = null
    }

    private val requests = ConcurrentHashMap<String, VpRequest>()
    private val byState = ConcurrentHashMap<String, VpRequest>()
    private val random = SecureRandom()
    private fun rnd() = B64.encode(ByteArray(24).also(random::nextBytes))

    fun create(dcqlQuery: JsonObject, ttlSeconds: Long = 300): JsonObject {
        val dcql = Dcql.parse(dcqlQuery)
        val r = VpRequest(rnd(), rnd(), rnd(), dcql, clock.instant().plusSeconds(ttlSeconds))
        requests[r.id] = r; byState[r.state] = r
        val auth = buildJsonObject {
            put("client_id", JsonPrimitive(cfg.verifierClientId)); put("response_type", JsonPrimitive("vp_token")); put("response_mode", JsonPrimitive("direct_post"))
            put("response_uri", JsonPrimitive(cfg.verifierResponseUri)); put("nonce", JsonPrimitive(r.nonce)); put("state", JsonPrimitive(r.state)); put("dcql_query", dcqlQuery)
        }
        val uri = "openid4vp://authorize?client_id=" + enc(cfg.verifierClientId) + "&response_type=vp_token&response_mode=direct_post&response_uri=" + enc(cfg.verifierResponseUri) +
            "&nonce=" + enc(r.nonce) + "&state=" + enc(r.state) + "&dcql_query=" + enc(dcqlQuery.toString())
        return buildJsonObject { put("requestId", JsonPrimitive(r.id)); put("authorizationRequest", auth); put("uri", JsonPrimitive(uri)) }
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    /** Respuesta de la cartera (direct_post): `vp_token` = objeto {id de consulta: [presentaciones]} y `state`. */
    suspend fun receive(vpToken: String?, state: String?): JsonObject {
        val r = state?.let { byState[it] } ?: throw ProtocolError(HttpStatusCode.BadRequest, "invalid_request", "state desconocido")
        synchronized(r) {
            if (r.status != "PENDING") throw ProtocolError(HttpStatusCode.BadRequest, "invalid_request", "La solicitud ya fue respondida (${r.status})")
            if (clock.instant().isAfter(r.expires)) { r.status = "REJECTED"; r.reason = "EXPIRED"; throw ProtocolError(HttpStatusCode.BadRequest, "invalid_request", "La solicitud venció") }
            r.status = "PROCESSING" // una solicitud solo se responde una vez: evita repetición
        }
        try {
            val token = try { Json.parseToJsonElement(vpToken ?: throw ProtocolError(HttpStatusCode.BadRequest, "invalid_request", "Falta vp_token")).jsonObject } catch (e: ProtocolError) { throw e } catch (e: Exception) {
                throw ProtocolError(HttpStatusCode.BadRequest, "invalid_request", "vp_token debe ser un objeto JSON")
            }
            val presentations = (token[r.dcql.id] as? JsonArray) ?: throw ProtocolError(HttpStatusCode.BadRequest, "invalid_request", "vp_token no contiene la consulta '${r.dcql.id}'")
            if (presentations.size != 1) throw ProtocolError(HttpStatusCode.BadRequest, "invalid_request", "Se esperaba una presentación")
            val verified = try {
                verifier.verifyPresentation(presentations.single().jsonPrimitive.content, cfg.verifierClientId, r.nonce)
            } catch (e: CredentialException) { throw ProtocolError(HttpStatusCode.BadRequest, "invalid_request", "${e.code}: ${e.message}") }
            if (r.dcql.vctValues.isNotEmpty() && verified.vct !in r.dcql.vctValues) throw ProtocolError(HttpStatusCode.BadRequest, "invalid_request", "VCT_NOT_ACCEPTED: ${verified.vct}")
            val missing = r.dcql.claimPaths.filter { it !in verified.claims }
            if (missing.isNotEmpty()) throw ProtocolError(HttpStatusCode.BadRequest, "invalid_request", "MISSING_CLAIMS: $missing")
            // solo se conserva lo que la consulta pidió (minimización)
            r.claims = JsonObject(verified.claims.filterKeys { it in r.dcql.claimPaths || it == "vct" || it == "iss" })
            r.issuer = verified.issuer
            r.status = "VERIFIED"
            return JsonObject(emptyMap())
        } catch (e: ProtocolError) {
            r.status = "REJECTED"; r.reason = e.description
            throw e
        }
    }

    fun result(id: String): JsonObject {
        val r = requests[id] ?: throw ProtocolError(HttpStatusCode.NotFound, "not_found", "Solicitud inexistente")
        return buildJsonObject {
            put("requestId", JsonPrimitive(r.id)); put("status", JsonPrimitive(r.status))
            put("reason", r.reason?.let { JsonPrimitive(it) } ?: JsonNull)
            put("issuer", r.issuer?.let { JsonPrimitive(it) } ?: JsonNull)
            put("claims", (r.claims as JsonElement?) ?: JsonNull)
        }
    }
}
