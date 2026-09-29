package co.org.avance.ssi.sim

import co.org.avance.ssi.credentials.CredentialProfiles
import co.org.avance.ssi.credentials.IssuerKeyResolver
import co.org.avance.ssi.didcore.CanonicalJson
import co.org.avance.ssi.didcore.DidKeys
import co.org.avance.ssi.didcore.Jws
import co.org.avance.ssi.resolver.DidWebResolver
import co.org.avance.ssi.resolver.ProofVerifier
import co.org.avance.ssi.resolver.VerificationKeyResolver
import co.org.avance.ssi.resolver.VerificationRelationship
import co.org.avance.ssi.resolver.VerificationResult
import co.org.avance.ssi.wallet.core.ProtectionLevel
import co.org.avance.ssi.wallet.core.SoftwareKeyCustodian
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.security.KeyPair
import java.security.interfaces.ECPrivateKey
import java.util.Base64

/** Recolector de comprobaciones: imprime PASS/FAIL como e2e.sh y recuerda cuántas fallaron. */
class Reporter {
    var pass = 0; var fail = 0
    fun check(desc: String, ok: Boolean, detail: String = "") {
        if (ok) { pass++; println("  ✔ PASS  $desc") } else { fail++; println("  ✘ FAIL  $desc${if (detail.isNotBlank()) "  ($detail)" else ""}") }
    }
    fun info(s: String) = println("  · $s")
}

class ScenarioContext(
    val domain: String,
    val base: String,               // canal de credenciales/cartera, p. ej. https://dominio:8444
    val ca: String,
    val authority: LabAttestationAuthority,
    val adminUrl: String? = null,   // canal mTLS, p. ej. https://dominio:8443
    val adminP12: String? = null,
    val p12Pass: String = "changeit",
    val adminToken: String? = null,
    val secretsOut: String? = null,
) {
    val known = mutableListOf<KeyPair>()
    val http: HttpClient = netClient(ca)
    val backend = WalletBackendClient(http, "$base")

    fun device(level: ProtectionLevel, a: LabAttestationAuthority = authority, origin: Int = LabAttestationAuthority.ORIGIN_GENERATED, boot: Int = LabAttestationAuthority.BOOT_VERIFIED) =
        SimulatedHardwareCustodian(a, level, keyPairSource = { DidKeys.generateP256().also { known += it } }, origin = origin, bootState = boot)

    fun software() = SoftwareKeyCustodian(keyPairSource = { DidKeys.generateP256().also { known += it } })

    fun app(c: co.org.avance.ssi.wallet.core.KeyCustodian) = HolderApp(c, backend, domain, Files.createTempDirectory("holder"))

    /** Formas textuales de todas las claves privadas usadas: se escriben a un archivo para que el script busque si ALGUNA llegó a la base de datos. */
    fun dumpSecrets() {
        val out = secretsOut ?: return
        val forms = known.flatMap { kp ->
            val d = (kp.private as ECPrivateKey).s
            val raw = d.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
            listOf(d.toString(), d.toString(16), Base64.getEncoder().encodeToString(kp.private.encoded), Base64.getUrlEncoder().withoutPadding().encodeToString(kp.private.encoded),
                Base64.getEncoder().encodeToString(raw), Base64.getUrlEncoder().withoutPadding().encodeToString(raw)).filter { it.length >= 20 }
        }
        File(out).writeText(forms.joinToString("\n") + "\n")
        println("  · ${known.size} claves privadas de prueba registradas en $out (solo laboratorio) para buscarlas en la base de datos")
    }
}

private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

// ==================================================================================== ERSo 2026-001
suspend fun walletScenario(c: ScenarioContext, r: Reporter) {
    r.info("Dispositivo TEE simulado, attestation firmada por la autoridad de laboratorio; el backend real verifica la cadena")
    val app = c.app(c.device(ProtectionLevel.TEE))
    val code = app.onboard()
    val act = app.activate()
    r.check("C1 la instancia queda registrada y activa (201, ACTIVE)", act.status == 201 && act.str("status") == "ACTIVE", act.raw)
    r.check("C3 nivel declarado = nivel verificado por attestation = TEE", act.obj("protection").let { it.s("declared") == "TEE" && it.s("verified") == "TEE" && it.s("attested") == "true" })
    val id = app.activation!!
    val ficha = c.backend.get("/instances/${id.instanceId}", id.token)
    r.check("C1 la instancia queda asociada al ciudadano", ficha.str("citizenRef") == app.citizenRef)
    val att = ficha.obj("deviceProfile").getValue("attestation").jsonObject
    r.check("C3 ficha técnica: la clave nació dentro del entorno seguro (origin=GENERATED) con arranque verificado", att.s("origin") == "0" && att.s("verifiedBootState") == "0", att.toString())
    r.check("C1 registro de activación en la auditoría", c.backend.get("/instances/${id.instanceId}/audit", id.token).raw.contains("INSTANCE_ACTIVATED"))

    val liar = c.app(c.software()); liar.onboard()
    val over = liar.activate(declared = ProtectionLevel.TEE)
    r.check("C3 un dispositivo de software que declara TEE es rechazado (PROTECTION_LEVEL_OVERSTATED)", over.error == "PROTECTION_LEVEL_OVERSTATED", over.raw)
    val fake = c.app(c.device(ProtectionLevel.STRONGBOX, a = LabAttestationAuthority.create("Falsa"))); fake.onboard()
    r.check("C3 attestation firmada por una raíz desconocida es rechazada (UNTRUSTED_CHAIN)", fake.activate().let { it.error == "ATTESTATION_INVALID" && "UNTRUSTED_CHAIN" in it.details }, "")
    val replay = c.app(c.device(ProtectionLevel.TEE)); replay.onboard()
    r.check("C3 reutilizar una attestation con otro desafío es rechazado (CHALLENGE_MISMATCH)", replay.activate(attestationChallengeOverride = "viejo".toByteArray()).let { it.error == "ATTESTATION_INVALID" && "CHALLENGE_MISMATCH" in it.details })
    val imported = c.app(c.device(ProtectionLevel.TEE, origin = LabAttestationAuthority.ORIGIN_IMPORTED)); imported.onboard()
    r.check("C2 una clave IMPORTADA (no generada en hardware) no puede declararse no exportable", imported.activate().error == "KEY_NOT_GENERATED_IN_HARDWARE")
    val second = c.app(c.device(ProtectionLevel.TEE)); second.citizenRef = app.citizenRef
    r.check("un ciudadano no puede tener dos carteras activas (409)", second.activate().status == 409)

    r.info("Recuperación de cuenta: el dispositivo se perdió")
    val bad = c.backend.post("/recovery/start", buildJsonObject { put("citizenRef", JsonPrimitive(app.citizenRef!!)); put("recoveryCode", JsonPrimitive("AAAA-BBBB-CCCC-DDDD-EEEE")) })
    r.check("C4 código de recuperación incorrecto → 401", bad.status == 401)
    val start = c.backend.post("/recovery/start", buildJsonObject { put("citizenRef", JsonPrimitive(app.citizenRef!!)); put("recoveryCode", JsonPrimitive(code)) })
    r.check("C4 código correcto → token de recuperación de un solo uso", start.status == 200 && start.json?.containsKey("recoveryToken") == true)
    val fresh = c.app(c.device(ProtectionLevel.TEE)); fresh.citizenRef = app.citizenRef
    val rec = fresh.activate(recoveryToken = start.str("recoveryToken"))
    r.check("C4 el dispositivo nuevo se activa con el token (201)", rec.status == 201, rec.raw)
    r.check("C4 la cartera anterior quedó revocada", c.backend.get("/instances/${id.instanceId}", id.token).error == "INSTANCE_REVOKED")
    r.check("C4 se informa que las credenciales deben reemitirse (ligadas a la clave anterior)", rec.obj("recovery").s("reissueRequired") == "true")
    val reuse = c.app(c.device(ProtectionLevel.TEE)); reuse.citizenRef = app.citizenRef
    r.check("C4 el token de recuperación no se puede reutilizar", reuse.activate(recoveryToken = start.str("recoveryToken")).error == "RECOVERY_TOKEN_INVALID")
    c.dumpSecrets()
}

// ==================================================================================== ERSo 2026-003
suspend fun didScenario(c: ScenarioContext, r: Reporter) {
    val dev = c.device(ProtectionLevel.TEE)
    val app = c.app(dev)
    app.onboard(); check(app.activate().status == 201) { "no se pudo activar" }
    val pub = app.createAndPublishDid()
    val id = pub.identity
    println("  · DID del titular: ${id.did}")
    r.check("C1 el DID y su DID Document se generan en el dispositivo (registro de generación sin material privado)",
        id.record.keyOrigin == "GENERATED" && !id.record.exportable && id.record.protectionLevel == "TEE" && !Json.encodeToString(co.org.avance.ssi.wallet.core.GenerationRecord.serializer(), id.record).contains("private", true))
    r.check("C2 informe de conformidad del DID Document: ${id.conformance.checks.size} comprobaciones, todas conformes", id.conformance.conformant, id.conformance.failed.toString())
    val vm = id.document["verificationMethod"]!!.jsonArray.single().jsonObject
    r.check("C3 authentication y assertionMethod apuntan a la clave Multikey del titular",
        id.document["authentication"]!!.jsonArray.single().jsonPrimitive.content == "${id.did}#key-1" && id.document["assertionMethod"]!!.jsonArray.single().jsonPrimitive.content == "${id.did}#key-1" &&
            vm.s("type") == "Multikey" && vm.s("publicKeyMultibase").startsWith("zDn"))
    r.check("publicación: el backend la entrega al VDR y la confirma leyendo la URL pública (200, PUBLISHED)", pub.publication.status == 200 && pub.publication.str("status") == "PUBLISHED", pub.publication.raw)
    val stored = app.storedDocument()
    r.check("C4 el documento queda sellado en el dispositivo y asociado a la instancia", stored.first == id.did && CanonicalJson.canonicalize(stored.second) == CanonicalJson.canonicalize(id.document))

    val resolver = DidWebResolver(c.http)
    val resolved = resolver.resolve(id.did)
    r.check("paso 6 del diagrama: el DID es resoluble públicamente por HTTPS y sirve exactamente lo construido en el dispositivo",
        resolved.isSuccess && CanonicalJson.canonicalize(resolved.didDocument!!) == CanonicalJson.canonicalize(id.document), resolved.resolutionMetadata.toString())
    val jws = Jws.signWith("${id.did}#key-1", "prueba-de-titular".toByteArray()) { dev.sign(app.didIdentity!!.record.keyAlias, it) }
    val ver = ProofVerifier(resolver).verify(jws, VerificationRelationship.AUTHENTICATION, id.did)
    r.check("el consumidor conforme verifica una firma del titular con la clave resuelta (ERSo 007)", ver is VerificationResult.Valid, ver.toString())
    val bk = c.backend.get("/instances/${app.activation!!.instanceId}/did-backup", app.activation!!.token)
    r.check("respaldo cifrado en el backend: el backend no puede leerlo", bk.status == 200 && !String(Base64.getUrlDecoder().decode(bk.str("ciphertext")), Charsets.ISO_8859_1).contains(id.did))

    // compuertas del backend
    val sw = c.app(c.software()); sw.onboard(); sw.activate()
    val attack = co.org.avance.ssi.didcore.DidDocumentBuilder.build("did:web:${c.domain}:entidades:avance", id.record.publicKeyMultibase)
    val bad = c.backend.post("/instances/${sw.activation!!.instanceId}/did", buildJsonObject { put("document", attack) }, sw.activation!!.token)
    r.check("un titular no puede publicar bajo el namespace de una entidad (DID_OUT_OF_NAMESPACE)", bad.error == "DID_OUT_OF_NAMESPACE")
    val civil = JsonObject(id.document + ("credentialSubject" to JsonObject(mapOf("name" to JsonPrimitive("Ana")))))
    val bad2 = c.backend.post("/instances/${sw.activation!!.instanceId}/did", buildJsonObject { put("document", civil) }, sw.activation!!.token)
    r.check("la validación es requisito previo: un documento con datos civiles no llega al VDR (DID_NOT_CONFORMANT)", bad2.error == "DID_NOT_CONFORMANT")
    val dup = c.backend.post("/instances/${sw.activation!!.instanceId}/did", buildJsonObject { put("document", id.document) }, sw.activation!!.token)
    r.check("un DID ya publicado no se publica dos veces", dup.status in listOf(409, 422))
    c.dumpSecrets()
    println("DID=${id.did}")
}

// ==================================================================================== ERSo 2026-002
suspend fun credentialScenario(c: ScenarioContext, r: Reporter) {
    val adminHttp = netClient(c.ca, c.adminP12, c.p12Pass)
    val admin = c.adminUrl!!; val token = c.adminToken!!
    val issuerId = c.base
    val keys = VerificationKeyResolver(DidWebResolver(c.http))
    val resolver = IssuerKeyResolver { kid -> keys.resolve(kid) }
    val holder = c.software().also { it.generate("holder-1") }
    val wallet = CredentialWallet(c.http, holder, "holder-1", resolver, issuerBase = issuerId)

    suspend fun adminPost(path: String, body: JsonObject) = adminHttp.post("$admin$path") { contentType(ContentType.Application.Json); header("Authorization", "Bearer $token"); setBody(body.toString()) }

    val meta = wallet.issuerMetadata().json!!.jsonObject
    val cfgs = meta["credential_configurations_supported"]!!.jsonObject
    r.check("C1 metadatos OpenID4VCI 1.0: anuncian dc+sd-jwt y mso_mdoc con ES256", cfgs.values.map { it.jsonObject.s("format") }.toSet() == setOf("dc+sd-jwt", "mso_mdoc") &&
        meta.s("credential_issuer") == issuerId && meta.s("credential_endpoint") == "$issuerId/credential" && meta.containsKey("nonce_endpoint"))

    val offerResp = adminPost("/admin/offers", buildJsonObject {
        put("credential_configuration_ids", JsonArray(listOf("AcademicCredential_dc+sd-jwt", "AcademicCredential_mso_mdoc").map(::JsonPrimitive)))
        put("claims", buildJsonObject { put("given_name", JsonPrimitive("Ana")); put("family_name", JsonPrimitive("Pérez")); put("program", JsonPrimitive("Ingeniería de Sistemas")); put("gpa", JsonPrimitive("4.5")) })
        put("tx_code", JsonPrimitive("true"))
    })
    r.check("el portal de administración (canal mTLS + token) crea una oferta OpenID4VCI", offerResp.status.value == 201, offerResp.bodyAsText())
    val offerJson = Json.parseToJsonElement(offerResp.bodyAsText()).jsonObject
    val offer = offerJson["credential_offer"]!!.jsonObject
    val noTok = adminHttp.post("$admin/admin/offers") { contentType(ContentType.Application.Json); setBody("{}") }
    r.check("el portal de administración rechaza llamadas sin token (401)", noTok.status.value == 401)
    val wrongTx = wallet.redeem(offer, "000000".takeIf { it != offerJson.getValue("tx_code").jsonPrimitive.content } ?: "111111")
    r.check("C1 un tx_code incorrecto es rechazado (invalid_grant)", wrongTx?.error == "invalid_grant")
    val (offer2, tx2) = adminPost("/admin/offers", buildJsonObject {
        put("credential_configuration_ids", JsonArray(listOf("AcademicCredential_dc+sd-jwt", "AcademicCredential_mso_mdoc").map(::JsonPrimitive)))
        put("claims", buildJsonObject { put("given_name", JsonPrimitive("Ana")); put("program", JsonPrimitive("Ingeniería de Sistemas")); put("gpa", JsonPrimitive("4.5")) }); put("tx_code", JsonPrimitive("true"))
    }).bodyAsText().let { Json.parseToJsonElement(it).jsonObject }.let { it["credential_offer"]!!.jsonObject to it.s("tx_code") }
    val err = wallet.redeem(offer2, tx2)
    r.check("C1 emisión y recepción en dc+sd-jwt y mso_mdoc (la cartera verificó la firma del emisor resolviendo su DID)", err == null && wallet.store.map { it.format } == listOf("dc+sd-jwt", "mso_mdoc"), err?.raw ?: "")
    r.check("C1 mso_mdoc: MSO verificado, docType y elementos leídos", wallet.store[1].mdoc?.let { it.docType == "org.avance.academic.1" && it.elements["org.avance.academic.1"]!!["program"] == "Ingeniería de Sistemas" } == true)

    val reqResp = adminPost("/admin/verifier/requests", buildJsonObject { put("dcql_query", buildJsonObject { put("credentials", JsonArray(listOf(buildJsonObject {
        put("id", JsonPrimitive("academic")); put("format", JsonPrimitive("dc+sd-jwt"))
        put("meta", buildJsonObject { put("vct_values", JsonArray(listOf(JsonPrimitive("urn:avance:credential:academic:1")))) })
        put("claims", JsonArray(listOf(buildJsonObject { put("path", JsonArray(listOf(JsonPrimitive("program")))) })))
    }))) }) })
    val req = Json.parseToJsonElement(reqResp.bodyAsText()).jsonObject
    val pres = wallet.present(req["authorizationRequest"]!!.jsonObject, wallet.store[0], setOf("program"))
    r.check("C1 presentación OpenID4VP (direct_post): el verificador la acepta", pres.status == 200, pres.raw)
    val res = Json.parseToJsonElement(adminHttp.get("$admin/admin/verifier/requests/${req.s("requestId")}") { header("Authorization", "Bearer $token") }.bodyAsText()).jsonObject
    r.check("C1 divulgación selectiva: el verificador recibió solo el programa (no nombre ni promedio)", res.s("status") == "VERIFIED" && res["claims"]!!.jsonObject.let { "program" in it && "given_name" !in it && "gpa" !in it })
    val replay = wallet.present(req["authorizationRequest"]!!.jsonObject, wallet.store[0], setOf("program"))
    r.check("C1 repetir la misma respuesta se rechaza", replay.status == 400)

    val log = Json.parseToJsonElement(adminHttp.get("$admin/admin/execution-log") { header("Authorization", "Bearer $token") }.bodyAsText()).jsonObject
    val observed = log["observed"]!!.jsonArray.map { it.jsonObject }
    r.check("C2 registro de ejecución: sin diferencias respecto al perfil declarado", log["mismatches"]!!.jsonArray.isEmpty())
    r.check("C2 formatos y algoritmos efectivamente usados = declarados (dc+sd-jwt, mso_mdoc, solo ES256)", observed.map { it.s("format") }.toSet() == setOf("dc+sd-jwt", "mso_mdoc") && observed.all { it.s("alg") == "ES256" })
    println("  · perfiles declarados: " + CredentialProfiles.declared.joinToString { "${it.format}=${it.signatureAlgorithms}" })

    val probes = listOf("/issue", "/credentials", "/present", "/presentation", "/api/vc", "/vc", "/vp", "/oidc4vc", "/verify")
    val codes = probes.map { c.http.get("$issuerId$it").status.value }
    r.check("C3 sin protocolo propio: rutas típicas de un protocolo paralelo devuelven 404 (${probes.size} sondeadas)", codes.all { it == 404 }, codes.toString())
    r.check("C3 sin protocolo propio: los únicos endpoints públicos son los de OpenID4VCI/OpenID4VP", listOf("/.well-known/openid-credential-issuer", "/.well-known/oauth-authorization-server").all { c.http.get("$issuerId$it").status.value == 200 })
    r.check("C3 el portal de administración NO está en el canal público (404 en 8444)", c.http.post("$issuerId/admin/offers").status.value == 404)

    val kidKey = keys.resolve("did:web:${c.domain}:entidades:avance#key-1")
    val holderKey = holder.publicKey("holder-1")
    r.check("C4 la clave del emisor (publicada en su DID Document) y la del titular (cnf) son distintas",
        co.org.avance.ssi.credentials.Jwk.thumbprint(kidKey) != co.org.avance.ssi.credentials.Jwk.thumbprint(holderKey))
    println(co.org.avance.ssi.credentials.KeyMatrix.toMarkdown(co.org.avance.ssi.credentials.KeyMatrix.build(kidKey, holderKey, "did:web:${c.domain}:entidades:avance#key-1")))
    c.dumpSecrets()
}

// ==================================================================================== ERSo 2026-004 (criterio 3) + 2026-003 (sin registro obligatorio)
suspend fun vdrOffScenario(c: ScenarioContext, r: Reporter) {
    val app = c.app(c.device(ProtectionLevel.TEE))
    app.onboard()
    r.check("con el VDR apagado la cartera se activa igual (el camino base no depende del registro)", app.activate().status == 201)
    val pub = app.createAndPublishDid()
    r.check("con el VDR apagado el DID del titular se crea y valida LOCALMENTE (no depende de un registro obligatorio)", pub.identity.conformance.conformant && pub.identity.record.keyOrigin == "GENERATED")
    r.check("la publicación falla de forma controlada (${pub.publication.status} ${pub.publication.error}), no con un error interno", pub.publication.status in listOf(422, 502) && pub.publication.error?.startsWith("VDR_") == true, pub.publication.raw)
    val act = app.activation!!
    r.check("la cartera sigue activa después del fallo de publicación", c.backend.get("/instances/${act.instanceId}", act.token).status == 200)
}
