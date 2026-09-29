package co.org.avance.ssi.wallet

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

class VdrException(val status: Int, val code: String, message: String, val details: List<String> = emptyList()) : RuntimeException(message)

data class VdrChallenge(val challengeId: String, val nonce: String, val audience: String, val expiresAt: String)
data class VdrWriteResult(val status: String, val version: Int, val hash: String, val publicUrl: String, val operationId: String)

/**
 * Comunicación Wallet Backend ↔ VDR (diagrama de Rocío, pasos 5 y 6). El backend actúa como una entidad más del VDR:
 * mTLS + OAuth2 client-credentials. NO firma nada por el titular: reenvía el desafío al dispositivo y devuelve la prueba que este firmó.
 */
interface VdrGateway {
    suspend fun challenge(did: String, purpose: String): VdrChallenge
    suspend fun write(did: String, purpose: String, expectedVersion: Int, idempotencyKey: String, challengeId: String, document: JsonObject?, proof: String): VdrWriteResult
}

class HttpVdrGateway(
    private val client: HttpClient,
    private val baseUrl: String,
    private val clientId: String,
    private val secret: String,
    /** Solo pruebas en proceso: simula los encabezados que nginx agrega al terminar mTLS. */
    private val extraHeaders: Map<String, String> = emptyMap(),
) : VdrGateway {
    @Volatile private var token: String? = null
    @Volatile private var tokenExpiresAtMs = 0L

    private fun HttpRequestBuilder.common() { extraHeaders.forEach { (k, v) -> header(k, v) } }

    private suspend fun bearer(force: Boolean = false): String {
        val cached = token
        if (!force && cached != null && System.currentTimeMillis() < tokenExpiresAtMs - 30_000) return cached
        val r = client.post("$baseUrl/admin/v1/oauth/token") {
            common()
            setBody(FormDataContent(Parameters.build { append("grant_type", "client_credentials"); append("client_id", clientId); append("client_secret", secret) }))
        }
        if (r.status.value != 200) throw error(r, "token")
        val o = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        token = o["access_token"]!!.jsonPrimitive.content
        tokenExpiresAtMs = System.currentTimeMillis() + (o["expires_in"]?.jsonPrimitive?.intOrNull ?: 60) * 1000L
        return token!!
    }

    private suspend fun error(r: HttpResponse, step: String): VdrException {
        val text = r.bodyAsText()
        val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        return VdrException(
            r.status.value, o?.get("error")?.jsonPrimitive?.contentOrNull ?: "VDR_ERROR", "VDR ($step): ${o?.get("message")?.jsonPrimitive?.contentOrNull ?: text.take(200)}",
            (o?.get("details") as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty(),
        )
    }

    private suspend fun <T> withToken(block: suspend (String) -> T): T = try { block(bearer()) } catch (e: VdrException) {
        if (e.status == 401) block(bearer(force = true)) else throw e
    }

    override suspend fun challenge(did: String, purpose: String): VdrChallenge = withToken { t ->
        val r = client.post("$baseUrl/admin/v1/challenges") {
            common(); header(HttpHeaders.Authorization, "Bearer $t"); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("did", JsonPrimitive(did)); put("purpose", JsonPrimitive(purpose)) }.toString())
        }
        if (r.status.value != 201) throw error(r, "desafío")
        val o = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        VdrChallenge(o["challengeId"]!!.jsonPrimitive.content, o["nonce"]!!.jsonPrimitive.content, o["audience"]!!.jsonPrimitive.content, o["expiresAt"]?.jsonPrimitive?.contentOrNull ?: "")
    }

    override suspend fun write(did: String, purpose: String, expectedVersion: Int, idempotencyKey: String, challengeId: String, document: JsonObject?, proof: String): VdrWriteResult = withToken { t ->
        val body = buildJsonObject { put("challengeId", JsonPrimitive(challengeId)); document?.let { put("document", it) }; put("proof", JsonPrimitive(proof)) }.toString()
        val r = client.put("$baseUrl/admin/v1/documents/$did") {
            common(); header(HttpHeaders.Authorization, "Bearer $t"); header(HttpHeaders.IfMatch, expectedVersion.toString()); header("Idempotency-Key", idempotencyKey)
            contentType(ContentType.Application.Json); setBody(body)
        }
        if (r.status.value !in 200..202) throw error(r, "escritura")
        val o = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        VdrWriteResult(o["status"]!!.jsonPrimitive.content, o["version"]!!.jsonPrimitive.content.toInt(), o["hash"]!!.jsonPrimitive.content, o["publicUrl"]!!.jsonPrimitive.content, o["operationId"]!!.jsonPrimitive.content)
    }

    companion object {
        /** Cliente con TLS mutuo (motor Java: el CIO de Ktor no presenta certificados cliente EC). */
        fun mtlsClient(caPath: String?, p12: String?, p12Pass: String?): HttpClient {
            val trust = caPath?.let { path ->
                val certs = CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(File(path).readBytes()))
                val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); certs.forEachIndexed { i, c -> setCertificateEntry("ca-$i", c) } }
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }.trustManagers
            }
            val keys = p12?.let {
                val pass = (p12Pass ?: "").toCharArray()
                val ks = KeyStore.getInstance("PKCS12").apply { File(it).inputStream().use { s -> load(s, pass) } }
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, pass) }.keyManagers
            }
            val ctx = SSLContext.getInstance("TLS").apply { init(keys, trust, null) }
            return HttpClient(Java) { expectSuccess = false; followRedirects = false; engine { config { sslContext(ctx) } } }
        }
    }
}
