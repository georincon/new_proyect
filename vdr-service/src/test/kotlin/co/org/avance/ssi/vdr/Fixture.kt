package co.org.avance.ssi.vdr

import co.org.avance.ssi.didcore.CanonicalJson
import co.org.avance.ssi.didcore.DidDocumentBuilder
import co.org.avance.ssi.didcore.DidKeys
import co.org.avance.ssi.didcore.Jws
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.server.testing.TestApplication
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions
import java.security.KeyPair
import java.util.UUID

class FakeReader : PublicUrlReader {
    enum class Mode { SERVE, TIMEOUT, WRONG_CONTENT }

    @Volatile var mode = Mode.SERVE
    lateinit var registry: RegistryService
    lateinit var baseUrl: String

    /** Emula la lectura por la URL pública sirviendo lo mismo que serviría el canal público. */
    override suspend fun read(url: String): PublicRead {
        when (mode) {
            Mode.TIMEOUT -> { delay(10_000); return PublicRead(504, ByteArray(0)) }
            Mode.WRONG_CONTENT -> return PublicRead(200, "{}".toByteArray())
            Mode.SERVE -> Unit
        }
        val did = registry.didFromUrlPath(url.removePrefix(baseUrl).split("/").filter { it.isNotEmpty() }) ?: return PublicRead(404, ByteArray(0))
        return when (val d = registry.publicDocument(did)) {
            is PublicDoc.Found -> PublicRead(200, d.bytes)
            PublicDoc.Gone -> PublicRead(410, ByteArray(0))
            PublicDoc.NotFound -> PublicRead(404, ByteArray(0))
        }
    }
}

class Entity(val clientId: String, val secret: String, val namespace: String, val domain: String = "civica-desarrollo.avance.org.co") {
    val did = "did:web:$domain:" + namespace.replace("/", ":")
    var key: KeyPair = DidKeys.generateP256()
    fun document(k: KeyPair = key) = DidDocumentBuilder.build(did, DidKeys.multikey(k))
}

class Fixture private constructor(val cfg: AppConfig, val db: Db, val registry: RegistryService, val reader: FakeReader) : AutoCloseable {
    val auth = AuthService(cfg)
    val avance = Entity("avance-issuer", "avance-secret-123", "entidades/avance")
    val lab = Entity("lab-operator", "lab-secret-123", "lab/laboratorio")
    val walletBackend = "wallet-backend"; val walletSecret = "wallet-secret-123"
    val adminSecret = "admin-secret-123"

    private val adminApp = TestApplication { application { adminModule(cfg, registry, auth) } }
    private val publicApp = TestApplication { application { publicModule(cfg, registry) } }
    val admin: HttpClient get() = adminApp.client
    val public: HttpClient get() = publicApp.client

    override fun close() { kotlinx.coroutines.runBlocking { runCatching { adminApp.stop() }; runCatching { publicApp.stop() } }; db.close() }

    // ---------- utilidades ----------
    private fun HttpRequestBuilder.mtls(cn: String?, verify: String = "SUCCESS") {
        if (cn != null) { header("X-SSL-Client-Verify", verify); header("X-SSL-Client-S-DN", "CN=$cn,O=Avance") }
    }

    suspend fun token(e: Entity, cn: String? = e.clientId, secret: String = e.secret): HttpResponse = tokenFor(e.clientId, secret, cn)

    suspend fun tokenFor(clientId: String, secret: String, cn: String? = clientId): HttpResponse =
        admin.post("/admin/v1/oauth/token") {
            mtls(cn)
            setBody(FormDataContent(Parameters.build { append("grant_type", "client_credentials"); append("client_id", clientId); append("client_secret", secret) }))
        }

    suspend fun bearer(e: Entity, cn: String? = e.clientId): String = accessToken(token(e, cn))
    suspend fun adminBearer(): String = accessToken(tokenFor("vdr-admin", adminSecret))

    private suspend fun accessToken(r: HttpResponse): String {
        check(r.status == HttpStatusCode.OK) { "token: ${r.status} ${r.bodyAsText()}" }
        return r.json().jsonObject["access_token"]!!.jsonPrimitive.content
    }

    suspend fun challenge(token: String, did: String, purpose: String, cn: String? = "avance-issuer"): HttpResponse =
        admin.post("/admin/v1/challenges") {
            header(HttpHeaders.Authorization, "Bearer $token"); mtls(cn); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("did", JsonPrimitive(did)); put("purpose", JsonPrimitive(purpose)) }.toString())
        }

    /** Ejecuta el ciclo completo de una operación: desafío -> prueba de posesión -> escritura. */
    suspend fun write(
        e: Entity,
        token: String,
        purpose: Purpose,
        doc: JsonObject?,
        expected: Int,
        idem: String = UUID.randomUUID().toString(),
        signer: KeyPair = e.key,
        cn: String? = e.clientId,
        proofDoc: JsonObject? = null,
        reuseChallenge: JsonObject? = null,
        kid: String = "${e.did}#key-1",
    ): HttpResponse {
        val ch = reuseChallenge ?: challenge(token, e.did, purpose.name, cn).let { r ->
            check(r.status == HttpStatusCode.Created) { "desafío: ${r.status} ${r.bodyAsText()}" }
            r.json().jsonObject
        }
        val docHash = if (purpose == Purpose.DEACTIVATE) registry.versions(e.did).last().hash else CanonicalJson.hash(proofDoc ?: doc!!)
        val payload = buildJsonObject {
            put("aud", ch["audience"]!!); put("challenge", ch["nonce"]!!); put("did", JsonPrimitive(e.did))
            put("docHash", JsonPrimitive(docHash)); put("purpose", JsonPrimitive(purpose.name))
        }
        val proof = Jws.sign(signer.private, kid, payload.toString().toByteArray())
        val body = buildJsonObject {
            put("challengeId", ch["challengeId"]!!)
            doc?.let { put("document", it) }
            put("proof", JsonPrimitive(proof))
        }.toString()
        val build: HttpRequestBuilder.() -> Unit = {
            header(HttpHeaders.Authorization, "Bearer $token"); header(HttpHeaders.IfMatch, expected.toString()); header("Idempotency-Key", idem)
            mtls(cn); contentType(ContentType.Application.Json); setBody(body)
        }
        return if (purpose == Purpose.DEACTIVATE) admin.post("/admin/v1/documents/${e.did}/deactivate", build) else admin.put("/admin/v1/documents/${e.did}", build)
    }

    suspend fun get(path: String, token: String, cn: String? = "vdr-admin"): HttpResponse =
        admin.get(path) { header(HttpHeaders.Authorization, "Bearer $token"); mtls(cn) }

    suspend fun publicDid(e: Entity): HttpResponse = public.get("/" + e.namespace + "/did.json")

    fun count(table: String, where: String = "true"): Int = db.tx { c ->
        c.createStatement().use { st -> st.executeQuery("SELECT count(*) FROM $table WHERE $where").use { it.next(); it.getInt(1) } }
    }

    fun exec(sql: String) = db.tx { c -> c.createStatement().use { it.execute(sql) } }

    fun auditActions(did: String? = null): List<String> = db.tx { c -> RegistryStore().auditFor(c, did).map { it.action } }

    companion object {
        fun create(confirmTimeoutMs: Long = 300): Fixture {
            val url = System.getenv("TEST_DB_URL").orEmpty()
            Assumptions.assumeTrue(url.isNotBlank(), "TEST_DB_URL no definida: se omiten las pruebas de integración con PostgreSQL")
            val cfg = AppConfig(
                vdrEnabled = true, confirmTimeoutMs = confirmTimeoutMs, dbUrl = url, dbUser = "vdr", dbPassword = "vdr",
                jwtSecret = "s".repeat(48), requireMtls = true,
                clients = listOf(
                    ClientConfig("avance-issuer", "avance-secret-123", "Avance (emisor)", listOf("entidades/avance")),
                    ClientConfig("lab-operator", "lab-secret-123", "Laboratorio", listOf("lab/laboratorio")),
                    ClientConfig("vdr-admin", "admin-secret-123", "Administración VDR", emptyList(), admin = true),
                    // Wallet Backend: publica DID de titulares bajo "titulares/<huella>" (namespace comodín de un nivel)
                    ClientConfig("wallet-backend", "wallet-secret-123", "Wallet Backend", listOf("titulares/*")),
                ),
            )
            val db = Db.connect(cfg.dbUrl, cfg.dbUser, cfg.dbPassword)
            db.tx { c -> c.createStatement().use { it.execute("TRUNCATE audit_log, operations, challenges, did_document_versions, did_documents, namespaces, entity_accounts CASCADE") } }
            val reader = FakeReader()
            val registry = RegistryService(cfg, db, RegistryStore(), reader).also { it.bootstrap() }
            reader.registry = registry
            reader.baseUrl = cfg.confirmBaseUrl
            return Fixture(cfg, db, registry, reader)
        }
    }
}

suspend fun HttpResponse.json(): JsonElement = Json.parseToJsonElement(bodyAsText())
suspend fun HttpResponse.obj(): JsonObject = json().jsonObject
suspend fun HttpResponse.errorCode(): String = obj()["error"]!!.jsonPrimitive.content
suspend fun HttpResponse.details(): List<String> = obj()["details"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
