package co.org.avance.ssi.sim

import co.org.avance.ssi.credentials.B64
import co.org.avance.ssi.credentials.IssuerKeyResolver
import co.org.avance.ssi.credentials.Jwk
import co.org.avance.ssi.credentials.KeyMatrix
import co.org.avance.ssi.credentials.SdJwtParts
import co.org.avance.ssi.credentials.SdJwtVcHolder
import co.org.avance.ssi.credentials.SdJwtVcVerifier
import co.org.avance.ssi.didcore.DidConformance
import co.org.avance.ssi.didcore.Jws
import co.org.avance.ssi.resolver.DidWebResolver
import co.org.avance.ssi.resolver.VerificationKeyResolver
import co.org.avance.ssi.wallet.core.GenerationRecord
import co.org.avance.ssi.wallet.core.KeyCustodian
import co.org.avance.ssi.wallet.core.ProtectionLevel
import co.org.avance.ssi.wallet.core.SealedDocumentStore
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files

/**
 * "Recorridos" de laboratorio (ERSo 001-003): los mismos flujos de los escenarios, pero IMPRIMIENDO cada respuesta
 * para que se pueda leer paso a paso. No cambian nada del producto: solo muestran.
 */
private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }
private fun J(e: JsonElement) = pretty.encodeToString(JsonElement.serializer(), e)
private fun h(t: String) = println("\n── $t")
private fun show(t: String, a: Api) { h(t); println("HTTP ${a.status}"); println(runCatching { J(Json.parseToJsonElement(a.raw)) }.getOrDefault(a.raw)) }
private fun part(p: String) = Json.parseToJsonElement(String(B64.decode(p), Charsets.UTF_8))
private fun line(k: String, v: Any?) = println("  ${k.padEnd(34)}: $v")

suspend fun walletTour(c: ScenarioContext) {
    val dev = c.device(ProtectionLevel.TEE)
    val app = c.app(dev)
    h("1. Alta del ciudadano (el backend entrega un identificador opaco y un código de recuperación, una sola vez)")
    val code = app.onboard()
    line("citizenRef", app.citizenRef); line("recoveryCode", code)
    h("2. Qué ofrece el custodio del dispositivo (el contrato NO tiene ninguna operación que devuelva una clave privada)")
    line("custodio", dev.name); line("nivel máximo", dev.maxProtection); line("informe", dev.report())
    KeyCustodian::class.java.methods.sortedBy { it.name }.forEach { println("  ${it.name.padEnd(12)} -> ${it.returnType.simpleName}") }
    val act = app.activate()
    show("3. Activación de la instancia (desafío de un solo uso + attestation + prueba de posesión)", act)
    val id = app.activation!!
    val ficha = c.backend.get("/instances/${id.instanceId}", id.token)
    show("4. Ficha técnica de la instancia", ficha)
    show("5. Registro de activación (auditoría inalterable)", c.backend.get("/instances/${id.instanceId}/audit", id.token))

    h("6. Intentos de engaño sobre el nivel de protección")
    val liar = c.app(c.software()); liar.onboard()
    show("6a. Dispositivo de SOFTWARE que declara TEE", liar.activate(declared = ProtectionLevel.TEE))
    val under = c.app(c.device(ProtectionLevel.TEE)); under.onboard()
    show("6b. Dispositivo TEE que declara SOFTWARE (declarar de menos también se rechaza)", under.activate(declared = ProtectionLevel.SOFTWARE))
    val fake = c.app(c.device(ProtectionLevel.STRONGBOX, a = LabAttestationAuthority.create("Falsa"))); fake.onboard()
    show("6c. Attestation firmada por una raíz desconocida", fake.activate())
    val replay = c.app(c.device(ProtectionLevel.TEE)); replay.onboard()
    show("6d. Attestation vieja (otro desafío)", replay.activate(attestationChallengeOverride = "viejo".toByteArray()))
    val imported = c.app(c.device(ProtectionLevel.TEE, origin = LabAttestationAuthority.ORIGIN_IMPORTED)); imported.onboard()
    show("6e. Clave IMPORTADA (no generada en el hardware)", imported.activate())
    val unlocked = c.app(c.device(ProtectionLevel.TEE, boot = LabAttestationAuthority.BOOT_UNVERIFIED)); unlocked.onboard()
    show("6f. Arranque no verificado (gestor de arranque desbloqueado)", unlocked.activate())
    val second = c.app(c.device(ProtectionLevel.TEE)); second.citizenRef = app.citizenRef
    show("6g. Segunda cartera activa para el mismo ciudadano", second.activate())

    h("7. Recuperación de cuenta (el dispositivo se perdió)")
    show("7a. Código incorrecto", c.backend.post("/recovery/start", buildJsonObject { put("citizenRef", JsonPrimitive(app.citizenRef!!)); put("recoveryCode", JsonPrimitive("AAAA-BBBB-CCCC-DDDD-EEEE")) }).let { Api(it.status, it.json, it.raw) })
    val start = c.backend.post("/recovery/start", buildJsonObject { put("citizenRef", JsonPrimitive(app.citizenRef!!)); put("recoveryCode", JsonPrimitive(code)) })
    show("7b. Código correcto: token de recuperación de un solo uso", start)
    val fresh = c.app(c.device(ProtectionLevel.TEE)); fresh.citizenRef = app.citizenRef
    val rec = fresh.activate(recoveryToken = start.str("recoveryToken"))
    show("7c. Dispositivo nuevo se activa con el token", rec)
    show("7d. La cartera anterior", c.backend.get("/instances/${id.instanceId}", id.token))
    val reuse = c.app(c.device(ProtectionLevel.TEE)); reuse.citizenRef = app.citizenRef
    show("7e. Reutilizar el token", reuse.activate(recoveryToken = start.str("recoveryToken")))
    c.dumpSecrets()
}

suspend fun didTour(c: ScenarioContext) {
    val dev = c.device(ProtectionLevel.TEE)
    val dir = Files.createTempDirectory("holder")
    val app = HolderApp(dev, c.backend, c.domain, dir)
    app.onboard(); app.activate()
    val pub = app.createAndPublishDid()
    val id = pub.identity
    h("1. Registro de generación (ERSo 003, criterio 1)")
    println(J(Json.encodeToJsonElement(GenerationRecord.serializer(), id.record)))
    h("2. DID derivado de la clave pública"); println("  ${id.did}")
    h("3. DID Document construido en el dispositivo (criterio 3: relaciones → clave Multikey)"); println(J(id.document))
    h("4. Informe de conformidad (criterio 2)")
    id.conformance.checks.forEach { println("  ${it.id}  ${if (it.passed) "CONFORME " else "NO CONFORME"}  ${it.requirement}\n        ref: ${it.reference}") }
    println("  → conformes: ${id.conformance.checks.count { it.passed }} de ${id.conformance.checks.size}")
    show("5. Publicación por el Wallet Backend en el VDR", pub.publication)
    h("6. Almacenamiento no exportable (criterio 4)")
    val inst = app.activation!!.instanceId
    Files.list(dir).forEach { println("  archivo: ${it.fileName}  (${Files.size(it)} bytes)") }
    val raw = SealedDocumentStore(dev, dir).rawBytes(inst)
    line("primeros 24 bytes (hex)", raw.take(24).joinToString("") { "%02x".format(it) })
    line("¿contiene el DID en claro?", String(raw, Charsets.ISO_8859_1).contains(id.did))
    line("¿contiene 'verificationMethod'?", String(raw, Charsets.ISO_8859_1).contains("verificationMethod"))
    val back = app.storedDocument()
    line("abrir con el custodio de ESTE dispositivo", "DID ${back.first == id.did}")
    val other = c.device(ProtectionLevel.TEE)
    line("abrir con OTRO dispositivo", runCatching { SealedDocumentStore(other, dir).load(inst) }.fold({ "ABRIÓ (¡mal!)" }, { "rechazado: ${it.javaClass.simpleName}" }))
    val moved = Files.createTempDirectory("holder2"); Files.copy(dir.resolve("$inst.wds"), moved.resolve("otra-instancia.wds"))
    line("abrir como OTRA instancia", runCatching { SealedDocumentStore(dev, moved).load("otra-instancia") }.fold({ "ABRIÓ (¡mal!)" }, { "rechazado: ${it.javaClass.simpleName}" }))
    val tampered = raw.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
    Files.write(moved.resolve("$inst.wds"), tampered)
    line("abrir con un byte alterado", runCatching { SealedDocumentStore(dev, moved).load(inst) }.fold({ "ABRIÓ (¡mal!)" }, { "rechazado: ${it.javaClass.simpleName}" }))

    h("7. El informe de conformidad detecta documentos defectuosos")
    fun defect(name: String, d: JsonObject) {
        val r = DidConformance.check(d, id.did, id.record.publicKeyMultibase)
        println("  ${name.padEnd(34)} -> fallan: ${r.failed.joinToString { it.id }.ifEmpty { "ninguna" }}")
    }
    defect("documento original", id.document)
    defect("con clave privada", JsonObject(id.document + ("privateKeyMultibase" to JsonPrimitive("z1234"))))
    defect("con datos civiles", JsonObject(id.document + ("credentialSubject" to buildJsonObject { put("name", JsonPrimitive("Ana")) })))
    defect("con extensión propietaria", JsonObject(id.document + ("miExtension" to JsonPrimitive("x"))))
    defect("sin @context", JsonObject(id.document - "@context"))
    defect("id ajeno", JsonObject(id.document + ("id" to JsonPrimitive("did:web:${c.domain}:titulares:otro"))))
    defect("authentication a clave inexistente", JsonObject(id.document + ("authentication" to JsonArray(listOf(JsonPrimitive("${id.did}#key-9"))))))
    defect("assertionMethod vacío", JsonObject(id.document + ("assertionMethod" to JsonArray(emptyList()))))

    h("8. Resolución pública y verificación con la clave del titular")
    val resolver = DidWebResolver(c.http)
    val res = resolver.resolve(id.did)
    line("resuelto", res.isSuccess); line("metadatos", res.resolutionMetadata)
    line("documento servido = construido", co.org.avance.ssi.didcore.CanonicalJson.canonicalize(res.didDocument!!) == co.org.avance.ssi.didcore.CanonicalJson.canonicalize(id.document))
    h("9. Compuertas del backend")
    val sw = c.app(c.software()); sw.onboard(); sw.activate()
    val attack = co.org.avance.ssi.didcore.DidDocumentBuilder.build("did:web:${c.domain}:entidades:avance", id.record.publicKeyMultibase)
    show("9a. Publicar bajo el espacio de una entidad", c.backend.post("/instances/${sw.activation!!.instanceId}/did", buildJsonObject { put("document", attack) }, sw.activation!!.token))
    val civil = JsonObject(id.document + ("credentialSubject" to JsonObject(mapOf("name" to JsonPrimitive("Ana")))))
    show("9b. Publicar un documento con datos civiles", c.backend.post("/instances/${sw.activation!!.instanceId}/did", buildJsonObject { put("document", civil) }, sw.activation!!.token))
    c.dumpSecrets()
    println("DID=${id.did}")
}

suspend fun credentialsTour(c: ScenarioContext) {
    val adminHttp = netClient(c.ca, c.adminP12, c.p12Pass)
    val admin = c.adminUrl!!; val token = c.adminToken!!
    val keys = VerificationKeyResolver(DidWebResolver(c.http))
    val resolver = IssuerKeyResolver { kid -> keys.resolve(kid) }
    val holder = c.software().also { it.generate("holder-1") }
    val wallet = CredentialWallet(c.http, holder, "holder-1", resolver, issuerBase = c.base)
    suspend fun adminPost(path: String, body: JsonObject) = adminHttp.post("$admin$path") { contentType(ContentType.Application.Json); header("Authorization", "Bearer $token"); setBody(body.toString()) }

    h("1. Metadatos del emisor (OpenID4VCI 1.0)"); println(J(wallet.issuerMetadata().json!!))
    val offerResp = adminPost("/admin/offers", buildJsonObject {
        put("credential_configuration_ids", JsonArray(listOf("AcademicCredential_dc+sd-jwt", "AcademicCredential_mso_mdoc").map(::JsonPrimitive)))
        put("claims", buildJsonObject { put("given_name", JsonPrimitive("Ana")); put("family_name", JsonPrimitive("Pérez")); put("program", JsonPrimitive("Ingeniería de Sistemas")); put("gpa", JsonPrimitive("4.5")) })
        put("tx_code", JsonPrimitive("true"))
    })
    val offerJson = Json.parseToJsonElement(offerResp.bodyAsText()).jsonObject
    h("2. Oferta creada en el portal del emisor (HTTP ${offerResp.status.value})"); println(J(offerJson))
    val err = wallet.redeem(offerJson["credential_offer"]!!.jsonObject, offerJson["tx_code"]!!.jsonPrimitive.content)
    line("canje de la oferta (null = sin errores)", err?.raw)

    val sd = wallet.store[0]
    h("3. Credencial dc+sd-jwt recibida y verificada por la cartera")
    val parts = SdJwtParts.parse(sd.raw)
    val jwt = parts.jwt.split(".")
    println("  Estructura: JWT~divulgación~divulgación~…~   (${parts.disclosures.size} divulgaciones, sin KB-JWT)")
    println("  Cabecera del JWT:"); println(J(part(jwt[0])))
    val payload = part(jwt[1]).jsonObject
    println("  Contenido del JWT:"); println(J(payload))
    println("  Divulgaciones (cada una = [sal, nombre, valor]) y su digest en `_sd`:")
    val sdSet = payload["_sd"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
    parts.disclosures.forEach { d -> println("    ${String(B64.decode(d), Charsets.UTF_8)}   digest=${B64.sha256Text(d).take(16)}…  ¿en _sd?=${B64.sha256Text(d) in sdSet}") }
    val cnf = payload["cnf"]!!.jsonObject["jwk"]!!.jsonObject
    line("huella de cnf.jwk (clave del titular)", Jwk.thumbprint(Jwk.toPublic(cnf)))
    line("huella de la clave del titular en el custodio", Jwk.thumbprint(holder.publicKey("holder-1")))

    val md = wallet.store[1]
    h("4. Credencial mso_mdoc recibida y verificada")
    line("docType", md.mdoc!!.docType); line("issuerKid", md.mdoc.issuerKid); line("elementos", md.mdoc.elements)
    line("huella de deviceKey", Jwk.thumbprint(md.mdoc.deviceKey))
    line("tamaño IssuerSigned (bytes CBOR)", B64.decode(md.raw).size)

    h("5. Manipulaciones: la cartera NO confía en lo recibido")
    val verifier = SdJwtVcVerifier(resolver)
    val mod = JsonObject(payload + ("vct" to JsonPrimitive("urn:avance:credential:falsa:1")))
    val altered = sd.raw.replaceFirst(jwt[1], B64.encode(mod.toString().toByteArray()))
    line("(a) cambiar el `vct` del contenido firmado", runCatching { verifier.verifyIssuance(altered) }.fold({ "ACEPTADA (¡mal!)" }, { "rechazada: ${it.message}" }))
    val gpaOld = parts.disclosures.first { String(B64.decode(it), Charsets.UTF_8).contains("\"gpa\"") }
    val gpaNew = B64.encode("[\"aT18JhUXHbL8Z9LGEJbAew\",\"gpa\",\"5.0\"]".toByteArray())
    line("(b) cambiar una divulgación (gpa 4.5 → 5.0)", runCatching { verifier.verifyIssuance(sd.raw.replace(gpaOld, gpaNew)) }.fold({ "ACEPTADA (¡mal!)" }, { "rechazada: ${it.message}" }))
    val noTx = adminPost("/admin/offers", buildJsonObject {
        put("credential_configuration_ids", JsonArray(listOf(JsonPrimitive("AcademicCredential_dc+sd-jwt")))); put("claims", buildJsonObject { put("program", JsonPrimitive("X")) }); put("tx_code", JsonPrimitive("true")) })
    val o2 = Json.parseToJsonElement(noTx.bodyAsText()).jsonObject
    val bad = wallet.redeem(o2["credential_offer"]!!.jsonObject, "000000")
    line("tx_code incorrecto", "${bad?.status} ${bad?.error}")
    val flag = wallet.redeem(o2["credential_offer"]!!.jsonObject, o2["tx_code"]!!.jsonPrimitive.content)
    line("oferta con el tx_code correcto después del fallo", flag?.raw ?: "emitida")

    h("6. Solicitud de presentación (OpenID4VP 1.0, DCQL) y presentación con divulgación selectiva")
    val reqResp = adminPost("/admin/verifier/requests", buildJsonObject { put("dcql_query", buildJsonObject { put("credentials", JsonArray(listOf(buildJsonObject {
        put("id", JsonPrimitive("academic")); put("format", JsonPrimitive("dc+sd-jwt"))
        put("meta", buildJsonObject { put("vct_values", JsonArray(listOf(JsonPrimitive("urn:avance:credential:academic:1")))) })
        put("claims", JsonArray(listOf(buildJsonObject { put("path", JsonArray(listOf(JsonPrimitive("program")))) })))
    }))) }) })
    val req = Json.parseToJsonElement(reqResp.bodyAsText()).jsonObject
    println(J(req["authorizationRequest"]!!))
    val ar = req["authorizationRequest"]!!.jsonObject
    val myPres = SdJwtVcHolder.present(sd.raw, setOf("program"), ar["client_id"]!!.jsonPrimitive.content, ar["nonce"]!!.jsonPrimitive.content) { holder.sign("holder-1", it) }
    val pp = SdJwtParts.parse(myPres)
    line("divulgaciones presentadas", pp.disclosures.map { String(B64.decode(it), Charsets.UTF_8) })
    val kb = pp.keyBinding!!.split(".")
    println("  Cabecera del KB-JWT:"); println(J(part(kb[0])))
    println("  Contenido del KB-JWT:"); println(J(part(kb[1])))
    line("sd_hash recalculado", B64.sha256Text(pp.signedPortion))
    val pres = wallet.present(ar, sd, setOf("program"))
    line("el verificador respondió", "HTTP ${pres.status} ${pres.raw}")
    val res = adminHttp.get("$admin/admin/verifier/requests/${req["requestId"]!!.jsonPrimitive.content}") { header("Authorization", "Bearer $token") }
    h("7. Resultado en el verificador"); println(J(Json.parseToJsonElement(res.bodyAsText())))
    val replay = wallet.present(ar, sd, setOf("program"))
    line("repetir la misma respuesta", "HTTP ${replay.status} ${replay.raw}")

    h("8. Registro de ejecución (perfil declarado vs. usado)")
    val log = adminHttp.get("$admin/admin/execution-log") { header("Authorization", "Bearer $token") }
    println(J(Json.parseToJsonElement(log.bodyAsText())))

    h("9. Matriz de claves por rol")
    val issuerKey = keys.resolve("did:web:${c.domain}:entidades:avance#key-1")
    println(KeyMatrix.toMarkdown(KeyMatrix.build(issuerKey, holder.publicKey("holder-1"), "did:web:${c.domain}:entidades:avance#key-1")))
    h("10. Verificaciones independientes")
    val issuerJws = Jws.parse(parts.jwt)
    line("firma de la credencial con la clave del EMISOR", Jws.verify(issuerJws, issuerKey))
    line("firma de la credencial con la clave del TITULAR", Jws.verify(issuerJws, holder.publicKey("holder-1")))
    val kbJws = Jws.parse(pp.keyBinding!!)
    line("firma del KB-JWT con la clave del TITULAR", Jws.verify(kbJws, holder.publicKey("holder-1")))
    line("firma del KB-JWT con la clave del EMISOR", Jws.verify(kbJws, issuerKey))

    h("11. Sondeo de rutas de un posible protocolo propio")
    listOf("/issue", "/credentials", "/present", "/presentation", "/api/vc", "/vc", "/vp", "/oidc4vc", "/verify").forEach { println("  GET ${it.padEnd(16)} -> ${c.http.get("${c.base}$it").status.value}") }
    println("  POST /admin/offers (canal público 8444) -> ${c.http.post("${c.base}/admin/offers").status.value}")
    c.dumpSecrets()
}
