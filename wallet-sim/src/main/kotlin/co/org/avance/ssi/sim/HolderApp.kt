package co.org.avance.ssi.sim

import co.org.avance.ssi.credentials.Jwk
import co.org.avance.ssi.didcore.Jws
import co.org.avance.ssi.wallet.core.HolderDidService
import co.org.avance.ssi.wallet.core.HolderIdentity
import co.org.avance.ssi.wallet.core.KeyCustodian
import co.org.avance.ssi.wallet.core.KeyPolicy
import co.org.avance.ssi.wallet.core.ProtectionLevel
import co.org.avance.ssi.wallet.core.SealedDocumentStore
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.util.Base64

/** Respuesta HTTP ya interpretada. */
class Api(val status: Int, val json: JsonObject?, val raw: String) {
    val ok get() = status in 200..299
    fun str(k: String) = json?.get(k)?.jsonPrimitive?.content ?: error("Falta '$k' en $raw")
    fun obj(k: String) = json?.get(k)?.jsonObject ?: error("Falta '$k' en $raw")
    val error: String? get() = json?.get("error")?.jsonPrimitive?.content
    val details: List<String> get() = (json?.get("details") as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
}

/** Cliente HTTP del Wallet Backend (el `HttpClient` es inyectable: real por TLS o en proceso en las pruebas). */
class WalletBackendClient(private val http: HttpClient, private val baseUrl: String = "") {
    private suspend fun HttpResponse.api(): Api {
        val text = bodyAsText()
        return Api(status.value, runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull(), text)
    }

    suspend fun post(path: String, body: JsonElement, token: String? = null): Api = http.post("$baseUrl/wallet/v1$path") {
        contentType(ContentType.Application.Json); token?.let { header(HttpHeaders.Authorization, "Bearer $it") }; setBody(body.toString())
    }.api()

    suspend fun put(path: String, body: JsonElement, token: String): Api = http.put("$baseUrl/wallet/v1$path") {
        contentType(ContentType.Application.Json); header(HttpHeaders.Authorization, "Bearer $token"); setBody(body.toString())
    }.api()

    suspend fun get(path: String, token: String? = null): Api = http.get("$baseUrl/wallet/v1$path") { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }.api()
}

class Activation(val instanceId: String, val token: String, val response: Api, val wiaThumbprint: String)
class PublishedDid(val identity: HolderIdentity, val publication: Api)

/**
 * HOLDER APP simulada (App móvil + SSI SDK + Secure Hardware). Toda operación con clave privada pasa por el [KeyCustodian];
 * esta clase jamás tiene acceso a una clave privada.
 */
class HolderApp(
    val custodian: KeyCustodian,
    private val backend: WalletBackendClient,
    private val domain: String,
    storeDir: Path,
) {
    private val store = SealedDocumentStore(custodian, storeDir)
    private val didService = HolderDidService(custodian, domain)
    var citizenRef: String? = null
    var activation: Activation? = null
    var didIdentity: HolderIdentity? = null

    /** Alta del ciudadano en el backend. Devuelve el código de recuperación (se muestra una sola vez). */
    suspend fun onboard(): String {
        val r = backend.post("/citizens", JsonObject(emptyMap()))
        check(r.ok) { "onboarding: ${r.raw}" }
        citizenRef = r.str("citizenRef")
        return r.str("recoveryCode")
    }

    /**
     * Registro y activación de la instancia. `declared` es lo que el dispositivo AFIRMA; por defecto, lo que su custodio realmente ofrece.
     * (Las pruebas lo cambian para simular un dispositivo que exagera.)
     */
    suspend fun activate(
        declared: ProtectionLevel = custodian.maxProtection,
        recoveryToken: String? = null,
        alias: String = "wia-${System.nanoTime()}",
        policy: KeyPolicy = KeyPolicy(),
        /** Solo pruebas: genera la clave con OTRO desafío, como quien reutiliza una attestation vieja. */
        attestationChallengeOverride: ByteArray? = null,
    ): Api {
        val ref = citizenRef ?: error("Falta el alta del ciudadano")
        val ch = backend.post("/challenges", buildJsonObject { put("purpose", JsonPrimitive("ACTIVATION")); put("citizenRef", JsonPrimitive(ref)) })
        check(ch.ok) { "desafío: ${ch.raw}" }
        val nonce = ch.str("nonce")
        val key = custodian.generate(alias, policy, attestationChallengeOverride ?: nonce.toByteArray())                 // la clave nace en el custodio con el desafío incrustado
        val pub = custodian.publicKey(alias)
        val jwk = Jwk.fromPublic(pub)
        val payload = buildJsonObject {
            put("aud", JsonPrimitive(ch.str("audience"))); put("challenge", JsonPrimitive(nonce)); put("citizenRef", JsonPrimitive(ref))
            put("declaredLevel", JsonPrimitive(declared.name)); put("thumbprint", JsonPrimitive(key.thumbprint))
        }
        val proof = Jws.signWith(null, payload.toString().toByteArray(), typ = "wallet-activation+jwt") { custodian.sign(alias, it) }
        val chain = custodian.attestation(alias)?.chainDer?.map { Base64.getEncoder().encodeToString(it) }
        val body = buildJsonObject {
            put("citizenRef", JsonPrimitive(ref)); put("challengeId", ch.json!!["challengeId"]!!)
            put("declaredLevel", JsonPrimitive(declared.name)); put("publicKey", jwk); put("proof", JsonPrimitive(proof))
            put("deviceInfo", buildJsonObject { put("platform", JsonPrimitive(custodian.report().platform)); put("custodian", JsonPrimitive(custodian.name)) })
            if (chain != null) put("attestation", buildJsonObject { put("chain", JsonArray(chain.map(::JsonPrimitive))) })
            recoveryToken?.let { put("recoveryToken", JsonPrimitive(it)) }
        }
        val r = backend.post("/instances", body)
        if (r.ok) activation = Activation(r.str("instanceId"), r.str("accessToken"), r, key.thumbprint) else custodian.delete(alias)
        return r
    }

    /**
     * ERSo 003 + diagrama de Rocío (pasos 1 a 6): crea el DID localmente, lo entrega al backend, firma el desafío del VDR con la clave del DID
     * y deja el documento sellado en el dispositivo junto con un respaldo cifrado en el backend.
     */
    suspend fun createAndPublishDid(alias: String = "holder-${System.nanoTime()}", backup: Boolean = true): PublishedDid {
        val act = activation ?: error("Primero hay que activar la cartera")
        val level = ProtectionLevel.valueOf(act.response.obj("protection")["verified"]!!.jsonPrimitive.content)
        val ch = backend.post("/challenges", buildJsonObject { put("purpose", JsonPrimitive("DID_KEY")); put("instanceId", JsonPrimitive(act.instanceId)) }, act.token)
        check(ch.ok) { "desafío DID_KEY: ${ch.raw}" }
        val identity = didService.create(alias, KeyPolicy(minimumLevel = level), ch.str("nonce").toByteArray())   // pasos 1-3
        didIdentity = identity
        val chain = custodian.attestation(alias)?.chainDer?.map { Base64.getEncoder().encodeToString(it) }
        val begin = backend.post("/instances/${act.instanceId}/did", buildJsonObject {                                // paso 4
            put("document", identity.document)
            if (chain != null) put("keyAttestation", buildJsonObject { put("challengeId", ch.json!!["challengeId"]!!); put("chain", JsonArray(chain.map(::JsonPrimitive))) })
        }, act.token)
        if (!begin.ok) return PublishedDid(identity, begin)
        val payload = begin.obj("signing").toString().toByteArray()
        val proof = didService.sign(identity, payload)                                                              // firma el custodio
        val done = backend.post("/instances/${act.instanceId}/did/${begin.str("publicationId")}/proof", buildJsonObject { put("proof", JsonPrimitive(proof)) }, act.token) // pasos 5-6
        if (done.ok) {
            store.save(act.instanceId, identity.did, identity.document)
            if (backup) {
                val sealed = custodian.seal(identity.document.toString().toByteArray(), "did-backup:${act.instanceId}".toByteArray())
                backend.put("/instances/${act.instanceId}/did-backup", buildJsonObject { put("ciphertext", JsonPrimitive(Base64.getUrlEncoder().withoutPadding().encodeToString(sealed))) }, act.token)
            }
        }
        return PublishedDid(identity, done)
    }

    fun storedDocument(): Pair<String, JsonObject> = store.load(activation!!.instanceId)
}
