package co.org.avance.ssi.credential

import co.org.avance.ssi.credentials.ExecutionLog
import co.org.avance.ssi.credentials.IssuerKeyResolver
import co.org.avance.ssi.credentials.SdJwtVcVerifier
import co.org.avance.ssi.didcore.DidDocumentBuilder
import co.org.avance.ssi.didcore.DidKeys
import co.org.avance.ssi.resolver.DidWebResolver
import co.org.avance.ssi.resolver.VerificationKeyResolver
import co.org.avance.ssi.sim.CredentialWallet
import co.org.avance.ssi.wallet.core.KeyCustodian
import co.org.avance.ssi.wallet.core.SoftwareKeyCustodian
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.TestApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.security.KeyPair
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Reloj que se puede adelantar (para probar expiraciones sin dormir). */
class MutableClock(private var now: Instant = Instant.now()) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
    fun advance(seconds: Long) { now = now.plusSeconds(seconds) }
}

class CredentialFixture : AutoCloseable {
    val clock = MutableClock()
    val issuerPair: KeyPair = DidKeys.generateP256()
    val cfg = CredentialConfig(adminToken = "admin-token-para-pruebas-0123456789")
    val didDoc: JsonObject = DidDocumentBuilder.build(cfg.issuerDid, DidKeys.multikey(issuerPair))
    var didAvailable = true

    private val didHttp = HttpClient(MockEngine { req ->
        if (didAvailable && req.url.toString() == "https://civica-desarrollo.avance.org.co/entidades/avance/did.json")
            respond(didDoc.toString(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/did+json"))
        else respond("", HttpStatusCode.NotFound)
    })
    val resolver = IssuerKeyResolver { kid -> VerificationKeyResolver(DidWebResolver(didHttp)).resolve(kid) }
    val log = ExecutionLog()
    val issuer = IssuerService(cfg, { data -> Signature.getInstance("SHA256withECDSAinP1363Format").apply { initSign(issuerPair.private); update(data) }.sign() }, clock, log)
    val verifier = VerifierService(cfg, SdJwtVcVerifier(resolver, clock, log = log), clock)
    val catalog = EndpointCatalog()
    private val app = TestApplication { application { credentialModule(cfg, issuer, verifier, catalog) } }
    val http: HttpClient get() = app.client

    init { kotlinx.coroutines.runBlocking { app.client.post("/health") } } // arranca la aplicación

    override fun close() { kotlinx.coroutines.runBlocking { runCatching { app.stop() } } }

    fun holder(alias: String = "holder-1", custodian: KeyCustodian = SoftwareKeyCustodian()): Pair<KeyCustodian, CredentialWallet> {
        custodian.generate(alias)
        return custodian to CredentialWallet(http, custodian, alias, resolver, clock, relative = true)
    }

    suspend fun admin(path: String, body: JsonObject, token: String? = cfg.adminToken): HttpResponse = http.post(path) {
        contentType(ContentType.Application.Json); token?.let { header(HttpHeaders.Authorization, "Bearer $it") }; setBody(body.toString())
    }

    suspend fun offer(ids: List<String>, claims: Map<String, String> = mapOf("given_name" to "Ana", "family_name" to "Pérez", "program" to "Ingeniería de Sistemas", "gpa" to "4.5"), tx: Boolean = false): Pair<JsonObject, String?> {
        val r = admin("/admin/offers", buildJsonObject {
            put("credential_configuration_ids", JsonArray(ids.map(::JsonPrimitive))); put("claims", JsonObject(claims.mapValues { JsonPrimitive(it.value) })); if (tx) put("tx_code", JsonPrimitive("true"))
        })
        check(r.status == HttpStatusCode.Created) { "oferta: ${r.status} ${r.bodyAsText()}" }
        val j = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        return j["credential_offer"]!!.jsonObject to (j["tx_code"] as? JsonPrimitive)?.content
    }
}
