package co.org.avance.ssi.credential

import co.org.avance.ssi.credentials.CredentialException
import co.org.avance.ssi.credentials.Jwk
import co.org.avance.ssi.credentials.KeyMatrix
import co.org.avance.ssi.credentials.SdJwtParts
import co.org.avance.ssi.credentials.SdJwtVcVerifier
import co.org.avance.ssi.didcore.DidKeys
import co.org.avance.ssi.didcore.Jws
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.contentType
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.interfaces.ECPublicKey
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** ERSo 2026-002 — Suite criptográfica y formatos de credencial: dc+sd-jwt y mso_mdoc sobre OpenID4VCI 1.0 / OpenID4VP 1.0. */
class Erso002CredentialsTest {
    private var f: CredentialFixture? = null
    private fun fx() = CredentialFixture().also { f = it }
    @AfterTest fun cleanup() { f?.close() }

    private fun sdRequest(claims: List<String> = listOf("program"), vct: String = VCT_ACADEMIC) = buildJsonObject {
        put("dcql_query", buildJsonObject { put("credentials", JsonArray(listOf(buildJsonObject {
            put("id", JsonPrimitive("academic")); put("format", JsonPrimitive("dc+sd-jwt"))
            put("meta", buildJsonObject { put("vct_values", JsonArray(listOf(JsonPrimitive(vct)))) })
            put("claims", JsonArray(claims.map { c -> buildJsonObject { put("path", JsonArray(listOf(JsonPrimitive(c)))) } }))
        }))) })
    }

    private suspend fun newRequest(f: CredentialFixture, body: JsonObject = sdRequest()): JsonObject =
        Json.parseToJsonElement(f.admin("/admin/verifier/requests", body).bodyAsText()).jsonObject

    private suspend fun result(f: CredentialFixture, requestId: String) =
        Json.parseToJsonElement(f.http.get("/admin/verifier/requests/$requestId") { headers.append("Authorization", "Bearer ${f.cfg.adminToken}") }.bodyAsText()).jsonObject

    // ==================== criterio 1: emisión y recepción en ambos formatos ====================
    @Test fun `C1 metadatos del emisor OpenID4VCI 1_0 anuncian los dos formatos y el perfil`() = runBlocking<Unit> {
        val f = fx()
        val (_, w) = f.holder()
        val m = w.issuerMetadata(f.cfg.issuerId).json!!
        assertEquals(f.cfg.issuerId, m["credential_issuer"]!!.jsonPrimitive.content)
        assertEquals("${f.cfg.issuerId}/credential", m["credential_endpoint"]!!.jsonPrimitive.content)
        assertEquals("${f.cfg.issuerId}/nonce", m["nonce_endpoint"]!!.jsonPrimitive.content)
        val cfgs = m["credential_configurations_supported"]!!.jsonObject
        val sd = cfgs[CONFIG_SDJWT]!!.jsonObject; val md = cfgs[CONFIG_MDOC]!!.jsonObject
        assertEquals("dc+sd-jwt", sd["format"]!!.jsonPrimitive.content)
        assertEquals("mso_mdoc", md["format"]!!.jsonPrimitive.content)
        assertEquals(VCT_ACADEMIC, sd["vct"]!!.jsonPrimitive.content)
        assertEquals(DOCTYPE_ACADEMIC, md["doctype"]!!.jsonPrimitive.content)
        assertEquals("ES256", sd["credential_signing_alg_values_supported"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("-7", md["credential_signing_alg_values_supported"]!!.jsonArray.single().jsonPrimitive.content)   // COSE ES256
        assertEquals("ES256", sd["proof_types_supported"]!!.jsonObject["jwt"]!!.jsonObject["proof_signing_alg_values_supported"]!!.jsonArray.single().jsonPrimitive.content)
        val asm = Json.parseToJsonElement(f.http.get("/.well-known/oauth-authorization-server").bodyAsText()).jsonObject
        assertEquals("${f.cfg.issuerId}/token", asm["token_endpoint"]!!.jsonPrimitive.content)
        assertContains(asm["grant_types_supported"]!!.toString(), "pre-authorized_code")
    }

    @Test fun `C1 dc+sd-jwt - emision con oferta y tx_code, recepcion verificada y presentacion con divulgacion selectiva`() = runBlocking<Unit> {
        val f = fx()
        val (_, wallet) = f.holder()
        val (offer, tx) = f.offer(listOf(CONFIG_SDJWT), tx = true)
        assertNotNull(tx)
        assertEquals(null, wallet.redeem(offer, tx))
        val cred = wallet.store.single()
        assertEquals("dc+sd-jwt", cred.format)
        assertTrue(cred.raw.endsWith("~"))

        val req = newRequest(f)
        val ar = req["authorizationRequest"]!!.jsonObject
        assertEquals("redirect_uri:${f.cfg.verifierResponseUri}", ar["client_id"]!!.jsonPrimitive.content)
        assertEquals("direct_post", ar["response_mode"]!!.jsonPrimitive.content)
        val ok = wallet.present(ar, cred, setOf("program"))
        assertEquals(200, ok.status, ok.raw)
        val res = result(f, req["requestId"]!!.jsonPrimitive.content)
        assertEquals("VERIFIED", res["status"]!!.jsonPrimitive.content)
        val claims = res["claims"]!!.jsonObject
        assertEquals("Ingeniería de Sistemas", claims["program"]!!.jsonPrimitive.content)
        assertFalse("given_name" in claims || "gpa" in claims || "family_name" in claims, "el verificador solo recibe lo que pidió y el titular reveló")
        assertEquals(f.cfg.issuerDid, res["issuer"]!!.jsonPrimitive.content)
    }

    @Test fun `C1 mso_mdoc - el titular recibe, verifica y almacena la credencial`() = runBlocking<Unit> {
        val f = fx()
        val (custodian, wallet) = f.holder()
        val (offer, _) = f.offer(listOf(CONFIG_MDOC))
        assertEquals(null, wallet.redeem(offer))
        val c = wallet.store.single()
        assertEquals("mso_mdoc", c.format)
        val v = c.mdoc!!
        assertEquals(DOCTYPE_ACADEMIC, v.docType)
        assertEquals("Ingeniería de Sistemas", v.elements[DOCTYPE_ACADEMIC]!!["program"])
        assertEquals(f.cfg.issuerKid, v.issuerKid)
        // la clave del MSO es la del titular (deviceKeyInfo)
        assertEquals(Jwk.thumbprint(v.deviceKey), Jwk.thumbprint(custodian.publicKey("holder-1")))
    }

    @Test fun `C1 una oferta puede pedir los dos formatos y se emiten ambos`() = runBlocking<Unit> {
        val f = fx()
        val (_, wallet) = f.holder()
        val (offer, _) = f.offer(listOf(CONFIG_SDJWT, CONFIG_MDOC))
        assertEquals(null, wallet.redeem(offer))
        assertEquals(listOf("dc+sd-jwt", "mso_mdoc"), wallet.store.map { it.format })
    }

    // ---- seguridad del flujo de emisión ----
    @Test fun `C1 el codigo pre-autorizado es de un solo uso, el tx_code protege y 3 fallos queman la oferta`() = runBlocking<Unit> {
        val f = fx()
        val (_, w) = f.holder()
        val (offer, tx) = f.offer(listOf(CONFIG_SDJWT), tx = true)
        val bad = w.redeem(offer, "000000".takeIf { it != tx } ?: "111111")
        assertEquals("invalid_grant", bad!!.error)
        assertEquals("invalid_grant", w.redeem(offer, null)!!.error)
        assertEquals("invalid_grant", w.redeem(offer, "999999".takeIf { it != tx } ?: "888888")!!.error)
        assertEquals("invalid_grant", w.redeem(offer, tx)!!.error, "tras 3 fallos la oferta queda quemada aunque ahora se use el tx_code correcto")
        val (offer2, tx2) = f.offer(listOf(CONFIG_SDJWT), tx = true)
        assertEquals(null, w.redeem(offer2, tx2))
        assertEquals("invalid_grant", w.redeem(offer2, tx2)!!.error, "reutilizar el código ya canjeado")
    }

    @Test fun `C1 la oferta vence`() = runBlocking<Unit> {
        val f = fx()
        val (_, w) = f.holder()
        val (offer, _) = f.offer(listOf(CONFIG_SDJWT))
        f.clock.advance(f.cfg.offerTtlSeconds + 1)
        assertEquals("invalid_grant", w.redeem(offer)!!.error)
    }

    @Test fun `C1 la prueba de posesion es obligatoria y de un solo uso - nonce, audiencia, firma y frescura`() = runBlocking<Unit> {
        val f = fx()
        val (custodian, w) = f.holder()
        // (a) nonce reutilizado
        val (o1, _) = f.offer(listOf(CONFIG_SDJWT))
        var seen: String? = null
        assertEquals(null, w.redeem(o1, proofOverride = { iss, n -> seen = n; w.proofJwt(iss, n) }))
        val (o2, _) = f.offer(listOf(CONFIG_SDJWT))
        val replay = w.redeem(o2, proofOverride = { iss, _ -> w.proofJwt(iss, seen!!) })
        assertEquals("invalid_nonce", replay!!.error)
        // (b) audiencia equivocada
        val (o3, _) = f.offer(listOf(CONFIG_SDJWT))
        assertEquals("invalid_proof", w.redeem(o3, proofOverride = { iss, n -> w.proofJwt(iss, n, aud = "https://otro-emisor.example") })!!.error)
        // (c) prueba vieja
        val (o4, _) = f.offer(listOf(CONFIG_SDJWT))
        assertEquals("invalid_proof", w.redeem(o4, proofOverride = { iss, n -> w.proofJwt(iss, n, iat = f.clock.instant().epochSecond - 10_000) })!!.error)
        // (d) firmada con OTRA clave que la declarada en jwk (suplantación de la clave de cnf)
        val thief = DidKeys.generateP256()
        val (o5, _) = f.offer(listOf(CONFIG_SDJWT))
        val forged = w.redeem(o5, proofOverride = { iss, n ->
            val payload = buildJsonObject { put("aud", JsonPrimitive(iss)); put("iat", JsonPrimitive(f.clock.instant().epochSecond)); put("nonce", JsonPrimitive(n)) }
            Jws.signWith(null, payload.toString().toByteArray(), "openid4vci-proof+jwt", mapOf("jwk" to Jwk.fromPublic(custodian.publicKey("holder-1")))) { java.security.Signature.getInstance("SHA256withECDSAinP1363Format").apply { initSign(thief.private); update(it) }.sign() }
        })
        assertEquals("invalid_proof", forged!!.error)
        // (e) typ incorrecto
        val (o6, _) = f.offer(listOf(CONFIG_SDJWT))
        assertEquals("invalid_proof", w.redeem(o6, proofOverride = { iss, n ->
            val payload = buildJsonObject { put("aud", JsonPrimitive(iss)); put("iat", JsonPrimitive(f.clock.instant().epochSecond)); put("nonce", JsonPrimitive(n)) }
            Jws.signWith(null, payload.toString().toByteArray(), "JWT", mapOf("jwk" to Jwk.fromPublic(custodian.publicKey("holder-1")))) { custodian.sign("holder-1", it) }
        })!!.error)
        // (f) nonce inventado
        val (o7, _) = f.offer(listOf(CONFIG_SDJWT))
        assertEquals("invalid_nonce", w.redeem(o7, proofOverride = { iss, _ -> w.proofJwt(iss, "inventado") })!!.error)
    }

    @Test fun `C1 el endpoint de credencial exige token y respeta lo autorizado por la oferta`() = runBlocking<Unit> {
        val f = fx()
        val (_, w) = f.holder()
        assertEquals(401, f.http.post("/credential").status.value)
        val (offer, _) = f.offer(listOf(CONFIG_SDJWT))   // solo autoriza SD-JWT
        // pedir mdoc con un token de SD-JWT
        val tok = Json.parseToJsonElement(f.http.post("/token") {
            setBody(io.ktor.client.request.forms.FormDataContent(io.ktor.http.Parameters.build {
                append("grant_type", PRE_AUTHORIZED_GRANT); append("pre-authorized_code", offer["grants"]!!.jsonObject[PRE_AUTHORIZED_GRANT]!!.jsonObject["pre-authorized_code"]!!.jsonPrimitive.content)
            }))
        }.bodyAsText()).jsonObject["access_token"]!!.jsonPrimitive.content
        val nonce = Json.parseToJsonElement(f.http.post("/nonce").bodyAsText()).jsonObject["c_nonce"]!!.jsonPrimitive.content
        val body = buildJsonObject { put("credential_configuration_id", JsonPrimitive(CONFIG_MDOC)); put("proofs", buildJsonObject { put("jwt", JsonArray(listOf(JsonPrimitive(w.proofJwt(f.cfg.issuerId, nonce))))) }) }
        val r = f.http.post("/credential") { headers.append("Authorization", "Bearer $tok"); contentType(io.ktor.http.ContentType.Application.Json); setBody(body.toString()) }
        assertEquals(400, r.status.value)
        assertContains(r.bodyAsText(), "invalid_credential_request")
        // sin proofs
        val r2 = f.http.post("/credential") { headers.append("Authorization", "Bearer $tok"); contentType(io.ktor.http.ContentType.Application.Json); setBody("""{"credential_configuration_id":"$CONFIG_SDJWT"}""") }
        assertEquals(400, r2.status.value)
        // token vencido
        f.clock.advance(f.cfg.accessTokenTtlSeconds + 1)
        val r3 = f.http.post("/credential") { headers.append("Authorization", "Bearer $tok"); contentType(io.ktor.http.ContentType.Application.Json); setBody(body.toString()) }
        assertEquals(401, r3.status.value)
    }

    @Test fun `C1 el portal de administracion exige su token`() = runBlocking<Unit> {
        val f = fx()
        val body = buildJsonObject { put("credential_configuration_ids", JsonArray(listOf(JsonPrimitive(CONFIG_SDJWT)))); put("claims", buildJsonObject { put("a", JsonPrimitive("b")) }) }
        assertEquals(HttpStatusCode.Unauthorized, f.admin("/admin/offers", body, token = null).status)
        assertEquals(HttpStatusCode.Unauthorized, f.admin("/admin/offers", body, token = "otro-token").status)
        assertEquals(HttpStatusCode.Unauthorized, f.admin("/admin/verifier/requests", sdRequest(), token = null).status)
    }

    // ---- verificador OpenID4VP ----
    @Test fun `C1 el verificador rechaza repeticion, nonce o audiencia ajenos, claims faltantes y formatos no soportados`() = runBlocking<Unit> {
        val f = fx()
        val (_, w) = f.holder()
        w.redeem(f.offer(listOf(CONFIG_SDJWT)).first)
        val cred = w.store.single()
        // repetir una respuesta válida
        val r1 = newRequest(f); val a1 = r1["authorizationRequest"]!!.jsonObject
        assertEquals(200, w.present(a1, cred, setOf("program")).status)
        val again = w.present(a1, cred, setOf("program"))
        assertEquals(400, again.status); assertContains(again.description!!, "ya fue respondida")
        // presentación construida para OTRA solicitud (nonce ajeno)
        val r2 = newRequest(f); val a2 = r2["authorizationRequest"]!!.jsonObject
        val wrongNonce = w.present(a2, cred, setOf("program"), nonceOverride = "nonce-de-otra-solicitud")
        assertEquals(400, wrongNonce.status); assertContains(wrongNonce.description!!, "NONCE_MISMATCH")
        // audiencia de otro verificador (reenvío a un verificador malicioso)
        val r3 = newRequest(f); val a3 = r3["authorizationRequest"]!!.jsonObject
        assertContains(w.present(a3, cred, setOf("program"), audOverride = "redirect_uri:https://malicioso.example/r").description!!, "AUDIENCE_MISMATCH")
        // el titular no reveló lo pedido
        val r4 = newRequest(f, sdRequest(claims = listOf("program", "gpa"))); val a4 = r4["authorizationRequest"]!!.jsonObject
        assertContains(w.present(a4, cred, setOf("program")).description!!, "MISSING_CLAIMS")
        // vct no aceptado
        val r5 = newRequest(f, sdRequest(vct = "urn:otro:tipo")); val a5 = r5["authorizationRequest"]!!.jsonObject
        assertContains(w.present(a5, cred, setOf("program")).description!!, "VCT_NOT_ACCEPTED")
        // estado desconocido
        assertEquals(400, f.http.post("/verifier/response") { setBody(io.ktor.client.request.forms.FormDataContent(io.ktor.http.Parameters.build { append("vp_token", "{}"); append("state", "no-existe") })) }.status.value)
        // mdoc en la consulta: se rechaza explícitamente (límite declarado)
        val mdocQ = buildJsonObject { put("dcql_query", buildJsonObject { put("credentials", JsonArray(listOf(buildJsonObject { put("id", JsonPrimitive("m")); put("format", JsonPrimitive("mso_mdoc")) }))) }) }
        val rm = f.admin("/admin/verifier/requests", mdocQ)
        assertEquals(400, rm.status.value); assertContains(rm.bodyAsText(), "mdoc")
        // los resultados quedan registrados
        assertEquals("REJECTED", result(f, r2["requestId"]!!.jsonPrimitive.content)["status"]!!.jsonPrimitive.content)
    }

    @Test fun `C1 una credencial alterada o de otro emisor se rechaza al presentarla`() = runBlocking<Unit> {
        val f = fx()
        val (_, w) = f.holder()
        w.redeem(f.offer(listOf(CONFIG_SDJWT)).first)
        val cred = w.store.single()
        val parts = SdJwtParts.parse(cred.raw)
        // se cambia un carácter de la firma del emisor
        val jwt = parts.jwt.dropLast(3) + (if (parts.jwt.takeLast(3) == "AAA") "BBB" else "AAA")
        val tampered = co.org.avance.ssi.sim.StoredCredential(cred.configId, cred.format, jwt + "~" + parts.disclosures.joinToString("") { "$it~" }, null)
        val ar = newRequest(f)["authorizationRequest"]!!.jsonObject
        assertContains(w.present(ar, tampered, setOf("program")).description!!, "INVALID_ISSUER_SIGNATURE")
        // el DID del emisor deja de ser resoluble (p. ej. desactivado): el verificador no puede confiar
        val ar2 = newRequest(f)["authorizationRequest"]!!.jsonObject
        f.didAvailable = false
        assertContains(w.present(ar2, cred, setOf("program")).description!!, "ISSUER_KEY_UNRESOLVED")
    }

    // ==================== criterio 2: lo declarado coincide con lo efectivamente usado ====================
    @Test fun `C2 el registro de ejecucion coincide con el perfil declarado y con los metadatos anunciados`() = runBlocking<Unit> {
        val f = fx()
        val (_, w) = f.holder()
        w.redeem(f.offer(listOf(CONFIG_SDJWT, CONFIG_MDOC)).first)
        val ar = newRequest(f)["authorizationRequest"]!!.jsonObject
        w.present(ar, w.store.first { it.format == "dc+sd-jwt" }, setOf("program"))
        val log = Json.parseToJsonElement(f.http.get("/admin/execution-log") { headers.append("Authorization", "Bearer ${f.cfg.adminToken}") }.bodyAsText()).jsonObject
        assertEquals(emptyList(), log["mismatches"]!!.jsonArray.toList(), "todo lo usado debe estar declarado")
        val observed = log["observed"]!!.jsonArray.map { it.jsonObject }
        // se observaron los dos formatos y la prueba del titular, todos con ES256
        val formats = observed.map { it["format"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("dc+sd-jwt", "mso_mdoc"), formats)
        assertTrue(observed.all { it["alg"]!!.jsonPrimitive.content == "ES256" })
        assertTrue(observed.any { it["role"]!!.jsonPrimitive.content.contains("emitido") && it["format"]!!.jsonPrimitive.content == "dc+sd-jwt" && it["typ"]!!.jsonPrimitive.content == "dc+sd-jwt" })
        assertTrue(observed.any { it["role"]!!.jsonPrimitive.content.contains("kb-jwt") && it["typ"]!!.jsonPrimitive.content == "kb+jwt" })
        assertTrue(observed.filter { it["format"]!!.jsonPrimitive.content == "dc+sd-jwt" && it["digest"] !is kotlinx.serialization.json.JsonNull }.all { it["digest"]!!.jsonPrimitive.content == "sha-256" })
        // lo anunciado en los metadatos == lo observado
        val meta = f.issuer.metadata()["credential_configurations_supported"]!!.jsonObject
        assertEquals("ES256", meta[CONFIG_SDJWT]!!.jsonObject["credential_signing_alg_values_supported"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(setOf("ES256"), observed.map { it["alg"]!!.jsonPrimitive.content }.toSet())
    }

    // ==================== criterio 3: ningún protocolo propio paralelo ====================
    @Test fun `C3 inventario de endpoints - solo OpenID4VCI, OpenID4VP, administracion y operacion`() = runBlocking<Unit> {
        val f = fx()
        val kinds = f.catalog.entries.groupBy { it.kind }
        assertEquals(setOf("OID4VCI", "OID4VP", "ADMIN", "OPS"), kinds.keys)
        // exactamente los endpoints normativos
        assertEquals(setOf("/.well-known/openid-credential-issuer", "/.well-known/oauth-authorization-server", "/token", "/nonce", "/credential"), kinds["OID4VCI"]!!.map { it.path }.toSet())
        assertEquals(setOf("/verifier/response"), kinds["OID4VP"]!!.map { it.path }.toSet())
        // todo endpoint estándar cita su norma; los administrativos lo dicen expresamente
        assertTrue(kinds["OID4VCI"]!!.all { "OpenID4VCI" in it.standard || "RFC" in it.standard })
        assertTrue(kinds["ADMIN"]!!.all { "no estandarizado" in it.standard })
        // caja negra: rutas típicas de un protocolo propio no existen
        listOf("/issue", "/credentials", "/present", "/presentation", "/api/vc", "/vc", "/vp", "/oidc4vc", "/wallet/credentials", "/verify").forEach { p ->
            assertEquals(404, f.http.get(p).status.value, "GET $p")
            assertEquals(404, f.http.post(p).status.value, "POST $p")
        }
        val forbiddenSegments = setOf("issue", "present", "presentation", "credentials", "vc", "vp", "custom", "verify", "api")
        assertTrue(f.catalog.entries.none { e -> e.path.split("/").any { it in forbiddenSegments } }, "segmentos propios de un protocolo paralelo")
    }

    // ==================== criterio 4: la firma del emisor está separada de la prueba del titular ====================
    @Test fun `C4 matriz de claves por rol construida con las claves reales de la ejecucion`() = runBlocking<Unit> {
        val f = fx()
        val (custodian, w) = f.holder()
        w.redeem(f.offer(listOf(CONFIG_SDJWT)).first)
        val issuerKey = f.issuerPair.public as ECPublicKey
        val holderKey = custodian.publicKey("holder-1")
        val rows = KeyMatrix.build(issuerKey, holderKey, f.cfg.issuerKid)
        assertNotEquals(rows[0].thumbprint, rows[1].thumbprint)
        // la credencial declara la clave del titular en cnf y NO la del emisor
        val payload = Json.parseToJsonElement(String(Jws.parse(SdJwtParts.parse(w.store.single().raw).jwt).payload)).jsonObject
        val cnf = Jwk.toPublic(payload["cnf"]!!.jsonObject["jwk"]!!.jsonObject)
        assertEquals(rows[1].thumbprint, Jwk.thumbprint(cnf))
        assertNotEquals(rows[0].thumbprint, Jwk.thumbprint(cnf))
        // la clave del emisor es la publicada en su DID Document
        assertEquals(rows[0].thumbprint, Jwk.thumbprint(f.resolver.resolve(f.cfg.issuerKid)))
        // el emisor jamás ve la clave privada del titular: solo llega la JWK pública en la prueba
        assertFalse(Jwk.fromPublic(holderKey).containsKey("d"))
        println(KeyMatrix.toMarkdown(rows))
    }

    @Test fun `C4 verificaciones independientes - la del emisor no usa la clave del titular y la del titular no usa la del emisor`() = runBlocking<Unit> {
        val f = fx()
        val (custodian, w) = f.holder()
        w.redeem(f.offer(listOf(CONFIG_SDJWT)).first)
        val sd = w.store.single().raw
        val parts = SdJwtParts.parse(sd)
        val jws = Jws.parse(parts.jwt)
        val payload = Json.parseToJsonElement(String(jws.payload)).jsonObject
        val holderPub = Jwk.toPublic(payload["cnf"]!!.jsonObject["jwk"]!!.jsonObject)
        // (1) firma del emisor: se valida SOLO con la clave del emisor; la del titular NO sirve
        assertTrue(Jws.verify(jws, f.issuerPair.public as ECPublicKey))
        assertFalse(Jws.verify(jws, holderPub), "la clave del titular no puede validar la firma del emisor")
        // (2) prueba del titular (KB-JWT): se valida SOLO con la clave de cnf; la del emisor NO sirve
        val pres = co.org.avance.ssi.credentials.SdJwtVcHolder.present(sd, setOf("program"), "aud", "n", f.clock) { custodian.sign("holder-1", it) }
        val kb = Jws.parse(SdJwtParts.parse(pres).keyBinding!!)
        assertTrue(Jws.verify(kb, holderPub))
        assertFalse(Jws.verify(kb, f.issuerPair.public as ECPublicKey), "la clave del emisor no puede validar la prueba del titular")
        // (3) el emisor no puede fabricar la prueba del titular (aunque tenga su propia clave privada)
        val issuerSigner: (ByteArray) -> ByteArray = { d -> java.security.Signature.getInstance("SHA256withECDSAinP1363Format").apply { initSign(f.issuerPair.private); update(d) }.sign() }
        val fabricated = co.org.avance.ssi.credentials.SdJwtVcHolder.present(sd, setOf("program"), "aud", "n", f.clock, issuerSigner)
        val v = SdJwtVcVerifier(f.resolver, f.clock)
        assertEquals("INVALID_HOLDER_PROOF", assertFailsWith<CredentialException> { v.verifyPresentation(fabricated, "aud", "n") }.code)
        // (4) y la prueba del titular sola no basta: sin emisor válido falla
        assertTrue(v.verifyPresentation(pres, "aud", "n").holderBound)
    }

    @Test fun `guarda - todo metodo de prueba devuelve void para que JUnit lo ejecute`() {
        val bad = Erso002CredentialsTest::class.java.declaredMethods.filter { it.isAnnotationPresent(Test::class.java) && it.returnType != Void.TYPE }.map { it.name }
        assertTrue(bad.isEmpty(), "Estas pruebas NO se ejecutarían: $bad")
    }
}
