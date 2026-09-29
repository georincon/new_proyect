package co.org.avance.ssi.tools

import co.org.avance.ssi.didcore.CanonicalJson
import co.org.avance.ssi.didcore.DidDocumentBuilder
import co.org.avance.ssi.didcore.DidKeys
import co.org.avance.ssi.didcore.DidWeb
import co.org.avance.ssi.didcore.Jws
import co.org.avance.ssi.didcore.Multikey
import co.org.avance.ssi.resolver.DidWebResolver
import co.org.avance.ssi.resolver.ProofVerifier
import co.org.avance.ssi.resolver.VerificationRelationship
import co.org.avance.ssi.resolver.VerificationResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
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
import io.ktor.http.Parameters
import io.ktor.http.contentType
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.system.exitProcess

/**
 * did-tools: la "entidad" desde la línea de comandos. Genera claves P-256, arma el DID Document con Multikey,
 * ejecuta el ciclo autenticado (token -> desafío -> prueba de posesión -> escritura) y consume/verifica como resolutor.
 */
private val pretty = Json { prettyPrint = true }

private fun usage(): Nothing {
    System.err.println(
        """
        Uso: did-tools <comando> [--opcion valor ...]
          keygen      --out clave.json
          did         --domain D --namespace entidades/avance
          build-doc   --did DID --key clave.json [--service-url https://...] [--out doc.json]
          write       --admin-url URL --client-id ID --secret S --p12 f --p12-pass P --ca ca.crt
                      --did DID --purpose CREATE|UPDATE|DEACTIVATE --expected N --key clave.json [--doc doc.json] [--idempotency-key K]
          admin       --admin-url URL --client-id ID --secret S --p12 f --p12-pass P --ca ca.crt --path /admin/v1/... [--method GET|POST] [--body f] [--out f]
          resolve     --did DID [--ca ca.crt]
          sign        --key clave.json --kid DID#key-1 --message texto
          verify      --jws JWS [--purpose authentication|assertionMethod] [--did DID] [--ca ca.crt]
        """.trimIndent(),
    )
    exitProcess(2)
}

fun main(args: Array<String>) {
    if (args.isEmpty()) usage()
    val o = args.drop(1).chunked(2).associate { it[0].removePrefix("--") to it.getOrElse(1) { "" } }
    fun req(k: String) = o[k] ?: run { System.err.println("Falta --$k"); usage() }
    runBlocking {
        when (args[0]) {
            "keygen" -> {
                val pair = DidKeys.generateP256()
                val json = buildJsonObject {
                    put("privateKeyPkcs8", JsonPrimitive(Base64.getEncoder().encodeToString(pair.private.encoded)))
                    put("publicKeyMultibase", JsonPrimitive(DidKeys.multikey(pair)))
                }
                File(req("out")).writeText(pretty.encodeToString(JsonObject.serializer(), json))
                println("Clave P-256 generada. Multikey público: ${DidKeys.multikey(pair)}")
            }
            "did" -> {
                val did = DidWeb.of(req("domain"), req("namespace").split("/"))
                println(did); println(DidWeb.url(did))
            }
            "build-doc" -> {
                val key = loadKey(req("key"))
                val services = o["service-url"]?.let { listOf(Triple("issuer", "OID4VCI", it)) } ?: emptyList()
                val doc = DidDocumentBuilder.build(req("did"), DidKeys.multikey(key), services = services)
                val text = pretty.encodeToString(JsonObject.serializer(), doc)
                o["out"]?.let { File(it).writeText(text) }
                println(text)
            }
            "write" -> write(o, ::req)
            "admin" -> admin(o, ::req)
            "resolve" -> {
                val r = DidWebResolver(http(o["ca"], null, null)).resolve(req("did"))
                println("didDocument: " + (r.didDocument?.let { pretty.encodeToString(JsonObject.serializer(), it) } ?: "null"))
                println("resolutionMetadata: ${r.resolutionMetadata}")
                println("documentMetadata: ${r.documentMetadata}")
                if (!r.isSuccess) exitProcess(1)
            }
            "sign" -> println(Jws.sign(loadKey(req("key")).private, req("kid"), req("message").toByteArray()))
            "verify" -> {
                val purpose = if (o["purpose"] == "authentication") VerificationRelationship.AUTHENTICATION else VerificationRelationship.ASSERTION_METHOD
                when (val v = ProofVerifier(DidWebResolver(http(o["ca"], null, null))).verify(req("jws"), purpose, o["did"])) {
                    is VerificationResult.Valid -> println("VALIDA  did=${v.did} kid=${v.keyId} payload=${String(v.payload)}")
                    is VerificationResult.Invalid -> { println("RECHAZADA  ${v.code}: ${v.message}"); exitProcess(1) }
                }
            }
            else -> usage()
        }
    }
}

private suspend fun write(o: Map<String, String>, req: (String) -> String) {
    val http = http(o["ca"], o["p12"], o["p12-pass"])
    val base = req("admin-url").trimEnd('/')
    val did = req("did")
    val purpose = req("purpose")
    val key = loadKey(req("key"))
    val token = token(http, base, req("client-id"), req("secret"))
    println("[1/4] token ........ OK (client_credentials + mTLS)")

    val ch = http.post("$base/admin/v1/challenges") {
        header(HttpHeaders.Authorization, "Bearer $token"); contentType(ContentType.Application.Json)
        setBody(buildJsonObject { put("did", JsonPrimitive(did)); put("purpose", JsonPrimitive(purpose)) }.toString())
    }
    if (ch.status.value != 201) fail("[2/4] desafío", ch)
    val chJson = Json.parseToJsonElement(ch.bodyAsText()).jsonObject
    println("[2/4] desafío ...... 201 id=${chJson["challengeId"]!!.jsonPrimitive.content} audience=${chJson["audience"]!!.jsonPrimitive.content} purpose=$purpose")

    val doc = o["doc"]?.let { Json.parseToJsonElement(File(it).readText()).jsonObject }
    val docHash = doc?.let(CanonicalJson::hash) ?: run {
        val versions = http.get("$base/admin/v1/documents/$did/versions") { header(HttpHeaders.Authorization, "Bearer $token") }
        Json.parseToJsonElement(versions.bodyAsText()).let { (it as kotlinx.serialization.json.JsonArray).last().jsonObject["hash"]!!.jsonPrimitive.content }
    }
    val payload = buildJsonObject {
        put("aud", chJson["audience"]!!); put("challenge", chJson["nonce"]!!); put("did", JsonPrimitive(did))
        put("docHash", JsonPrimitive(docHash)); put("purpose", JsonPrimitive(purpose))
    }
    val proof = Jws.sign(key.private, "$did#key-1", payload.toString().toByteArray())
    println("[3/4] prueba ....... ES256 kid=$did#key-1 docHash=$docHash")

    val body = buildJsonObject {
        put("challengeId", chJson["challengeId"]!!); doc?.let { put("document", it) }; put("proof", JsonPrimitive(proof))
    }.toString()
    val idem = o["idempotency-key"] ?: UUID.randomUUID().toString()
    val resp: HttpResponse = if (purpose == "DEACTIVATE") {
        http.post("$base/admin/v1/documents/$did/deactivate") { auth(token, o["expected"]!!, idem); setBody(body) }
    } else {
        http.put("$base/admin/v1/documents/$did") { auth(token, o["expected"]!!, idem); setBody(body) }
    }
    println("[4/4] escritura .... ${resp.status.value} If-Match=${o["expected"]} Idempotency-Key=$idem")
    println(pretty.encodeToString(JsonObject.serializer(), Json.parseToJsonElement(resp.bodyAsText()).jsonObject))
    if (resp.status.value !in 200..202) exitProcess(1)
}

private fun io.ktor.client.request.HttpRequestBuilder.auth(token: String, expected: String, idem: String) {
    header(HttpHeaders.Authorization, "Bearer $token"); header(HttpHeaders.IfMatch, expected); header("Idempotency-Key", idem)
    contentType(ContentType.Application.Json)
}

private suspend fun admin(o: Map<String, String>, req: (String) -> String) {
    val http = http(o["ca"], o["p12"], o["p12-pass"])
    val base = req("admin-url").trimEnd('/')
    val token = token(http, base, req("client-id"), req("secret"))
    val path = req("path")
    val resp = if ((o["method"] ?: "GET") == "POST") {
        http.post("$base$path") {
            header(HttpHeaders.Authorization, "Bearer $token"); contentType(ContentType.Application.Json)
            o["body"]?.let { setBody(File(it).readText()) }
        }
    } else http.get("$base$path") { header(HttpHeaders.Authorization, "Bearer $token") }
    val text = resp.bodyAsText()
    o["out"]?.let { File(it).writeText(text); println("HTTP ${resp.status.value} -> guardado en $it (${text.length} bytes)") }
        ?: println("HTTP ${resp.status.value}\n" + runCatching { pretty.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), Json.parseToJsonElement(text)) }.getOrDefault(text))
    if (resp.status.value !in 200..299) exitProcess(1)
}

private suspend fun token(http: HttpClient, base: String, id: String, secret: String): String {
    val r = http.post("$base/admin/v1/oauth/token") {
        setBody(FormDataContent(Parameters.build { append("grant_type", "client_credentials"); append("client_id", id); append("client_secret", secret) }))
    }
    if (r.status.value != 200) fail("token", r)
    return Json.parseToJsonElement(r.bodyAsText()).jsonObject["access_token"]!!.jsonPrimitive.content
}

private suspend fun fail(step: String, r: HttpResponse): Nothing {
    println("$step FALLÓ: HTTP ${r.status.value} ${r.bodyAsText()}")
    exitProcess(1)
}

private fun loadKey(path: String): KeyPair {
    val j = Json.parseToJsonElement(File(path).readText()).jsonObject
    val priv = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(j["privateKeyPkcs8"]!!.jsonPrimitive.content)))
    return KeyPair(Multikey.decodeP256(j["publicKeyMultibase"]!!.jsonPrimitive.content), priv)
}

/**
 * Cliente HTTP con confianza en la CA de laboratorio y, opcionalmente, certificado cliente (mTLS).
 * Usa el motor Java (TLS nativo del JDK): el motor CIO de Ktor no presenta certificados cliente EC.
 */
private fun http(caPath: String?, p12: String?, p12Pass: String?): HttpClient {
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
    return HttpClient(Java) {
        expectSuccess = false
        followRedirects = false
        engine { config { sslContext(ctx) } }
    }
}
