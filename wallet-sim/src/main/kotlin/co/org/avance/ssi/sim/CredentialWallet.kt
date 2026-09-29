package co.org.avance.ssi.sim

import co.org.avance.ssi.credentials.B64
import co.org.avance.ssi.credentials.IssuerKeyResolver
import co.org.avance.ssi.credentials.Jwk
import co.org.avance.ssi.credentials.MdocVerifier
import co.org.avance.ssi.credentials.SdJwtVcHolder
import co.org.avance.ssi.credentials.SdJwtVcVerifier
import co.org.avance.ssi.credentials.VerifiedMdoc
import co.org.avance.ssi.didcore.Jws
import co.org.avance.ssi.wallet.core.KeyCustodian
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Clock

class StoredCredential(val configId: String, val format: String, val raw: String, val mdoc: VerifiedMdoc?)

/** Respuesta de protocolo ya interpretada (`error` / `error_description` de OAuth). */
class ProtoResponse(val status: Int, val json: JsonObject?, val raw: String) {
    val ok get() = status in 200..299
    val error: String? get() = json?.get("error")?.jsonPrimitive?.contentOrNull
    val description: String? get() = json?.get("error_description")?.jsonPrimitive?.contentOrNull
}

/**
 * Parte del titular de OpenID4VCI 1.0 y OpenID4VP 1.0 (flujo de código pre-autorizado + direct_post). NO define protocolos propios:
 * habla exactamente los endpoints estándar que anuncia el emisor. La clave de la prueba de posesión es `holderAlias` del custodio.
 */
class CredentialWallet(
    private val http: HttpClient,
    private val custodian: KeyCustodian,
    private val holderAlias: String,
    issuerKeys: IssuerKeyResolver,
    private val clock: Clock = Clock.systemUTC(),
    /** Solo pruebas en proceso: el cliente no tiene host, así que las URL absolutas se convierten en rutas relativas. */
    private val relative: Boolean = false,
    private val issuerBase: String = "",
) {
    private fun ep(url: String): String = if (relative) "/" + url.substringAfter("://").substringAfter("/", "") else url
    private val sdVerifier = SdJwtVcVerifier(issuerKeys, clock)
    private val mdocVerifier = MdocVerifier(issuerKeys, clock)
    val store = mutableListOf<StoredCredential>()

    private suspend fun HttpResponse.proto(): ProtoResponse {
        val text = bodyAsText()
        return ProtoResponse(status.value, runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull(), text)
    }

    suspend fun issuerMetadata(issuer: String = issuerBase): ProtoResponse = http.get(ep("$issuer/.well-known/openid-credential-issuer")).proto()

    /** Canjea una oferta completa. Devuelve la primera respuesta de error de protocolo, o null si todo salió bien. */
    suspend fun redeem(offer: JsonObject, txCode: String? = null, proofOverride: ((String, String) -> String)? = null): ProtoResponse? {
        val issuer = offer["credential_issuer"]!!.jsonPrimitive.content
        val grant = offer["grants"]!!.jsonObject["urn:ietf:params:oauth:grant-type:pre-authorized_code"]!!.jsonObject
        val meta = issuerMetadata(issuer).json!!
        val asMeta = http.get(ep("$issuer/.well-known/oauth-authorization-server")).proto().json!!
        val tok = http.post(ep(asMeta["token_endpoint"]!!.jsonPrimitive.content)) {
            setBody(FormDataContent(Parameters.build {
                append("grant_type", "urn:ietf:params:oauth:grant-type:pre-authorized_code"); append("pre-authorized_code", grant["pre-authorized_code"]!!.jsonPrimitive.content)
                txCode?.let { append("tx_code", it) }
            }))
        }.proto()
        if (!tok.ok) return tok
        val access = tok.json!!["access_token"]!!.jsonPrimitive.content
        for (id in offer["credential_configuration_ids"]!!.jsonArray.map { it.jsonPrimitive.content }) {
            val nonceResp = http.post(ep(meta["nonce_endpoint"]!!.jsonPrimitive.content)).proto()
            val nonce = nonceResp.json!!["c_nonce"]!!.jsonPrimitive.content
            val proof = proofOverride?.invoke(issuer, nonce) ?: proofJwt(issuer, nonce)
            val r = http.post(ep(meta["credential_endpoint"]!!.jsonPrimitive.content)) {
                header(HttpHeaders.Authorization, "Bearer $access"); contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("credential_configuration_id", JsonPrimitive(id)); put("proofs", buildJsonObject { put("jwt", JsonArray(listOf(JsonPrimitive(proof)))) }) }.toString())
            }.proto()
            if (!r.ok) return r
            val credential = r.json!!["credentials"]!!.jsonArray.single().jsonObject["credential"]!!.jsonPrimitive.content
            val fmt = meta["credential_configurations_supported"]!!.jsonObject[id]!!.jsonObject["format"]!!.jsonPrimitive.content
            // la cartera NO confía en lo recibido: verifica la firma del emisor (resolviendo su DID) antes de guardar
            if (fmt == "dc+sd-jwt") { sdVerifier.verifyIssuance(credential); store += StoredCredential(id, fmt, credential, null) }
            else { val bytes = B64.decode(credential); store += StoredCredential(id, fmt, credential, mdocVerifier.verify(bytes)) }
        }
        return null
    }

    fun proofJwt(issuer: String, nonce: String, alias: String = holderAlias, iat: Long = clock.instant().epochSecond, aud: String = issuer): String {
        val payload = buildJsonObject { put("aud", JsonPrimitive(aud)); put("iat", JsonPrimitive(iat)); put("nonce", JsonPrimitive(nonce)) }
        return Jws.signWith(null, payload.toString().toByteArray(), typ = "openid4vci-proof+jwt", extraHeader = mapOf("jwk" to Jwk.fromPublic(custodian.publicKey(alias)))) { custodian.sign(alias, it) }
    }

    /** Responde una solicitud OpenID4VP (direct_post) revelando solo `reveal`. */
    suspend fun present(authRequest: JsonObject, credential: StoredCredential, reveal: Set<String>, nonceOverride: String? = null, audOverride: String? = null): ProtoResponse {
        val clientId = audOverride ?: authRequest["client_id"]!!.jsonPrimitive.content
        val nonce = nonceOverride ?: authRequest["nonce"]!!.jsonPrimitive.content
        val presentation = SdJwtVcHolder.present(credential.raw, reveal, clientId, nonce, clock) { custodian.sign(holderAlias, it) }
        val queryId = authRequest["dcql_query"]!!.jsonObject["credentials"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content
        val vpToken = buildJsonObject { put(queryId, JsonArray(listOf(JsonPrimitive(presentation)))) }
        return http.post(ep(authRequest["response_uri"]!!.jsonPrimitive.content)) {
            setBody(FormDataContent(Parameters.build { append("vp_token", vpToken.toString()); append("state", authRequest["state"]!!.jsonPrimitive.content) }))
        }.proto()
    }
}
