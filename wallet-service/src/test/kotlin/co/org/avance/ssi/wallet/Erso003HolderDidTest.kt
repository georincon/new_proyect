package co.org.avance.ssi.wallet

import co.org.avance.ssi.didcore.CanonicalJson
import co.org.avance.ssi.didcore.DidDocumentBuilder
import co.org.avance.ssi.didcore.DidWeb
import co.org.avance.ssi.sim.HolderApp
import co.org.avance.ssi.wallet.core.HolderDidService
import co.org.avance.ssi.wallet.core.KeyPolicy
import co.org.avance.ssi.wallet.core.ProtectionLevel
import co.org.avance.ssi.wallet.core.StoreAccessException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.KeyPair
import java.security.interfaces.ECPrivateKey
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ERSo 2026-003 — Creación del DID y DID Document del titular (integración: dispositivo → Wallet Backend → VDR). */
class Erso003HolderDidTest {
    private var f: WalletFixture? = null
    private fun fx() = WalletFixture.create().also { f = it }
    @AfterTest fun cleanup() { f?.close() }
    private val domain = WalletFixture.DOMAIN

    private fun privateForms(kp: KeyPair): List<String> {
        val d = (kp.private as ECPrivateKey).s
        val raw = d.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        return listOf(d.toString(), d.toString(16), Base64.getEncoder().encodeToString(kp.private.encoded), Base64.getUrlEncoder().withoutPadding().encodeToString(raw)).filter { it.length >= 20 }
    }

    private suspend fun softwareApp(f: WalletFixture): HolderApp = f.app(f.softwareDevice()).also { it.onboard(); it.activate() }

    /** POST /did "a mano" con un documento arbitrario (para probar las compuertas del backend). */
    private suspend fun rawBegin(f: WalletFixture, app: HolderApp, doc: JsonObject) =
        f.backend.post("/instances/${app.activation!!.instanceId}/did", buildJsonObject { put("document", doc) }, app.activation!!.token)

    // ==================== flujo completo ====================
    @Test fun `flujo completo - el DID nace en el dispositivo, el backend lo publica en el VDR y queda resoluble`() = runBlocking<Unit> {
        val f = fx()
        val known = mutableListOf<KeyPair>()
        val app = f.app(f.attestingDevice(ProtectionLevel.TEE, capture = { known += it }))
        app.onboard(); assertEquals(201, app.activate().status)
        val pub = app.createAndPublishDid()
        assertEquals(200, pub.publication.status, pub.publication.raw)
        assertEquals("PUBLISHED", pub.publication.str("status"))
        assertEquals("CONFIRMED", pub.publication.str("vdrStatus"))

        val id = pub.identity
        // paso 6 del diagrama: el DID es resoluble públicamente y sirve EXACTAMENTE lo que el dispositivo construyó
        val served = f.registry.publicDocument(id.did)
        assertTrue(served is co.org.avance.ssi.vdr.PublicDoc.Found)
        assertEquals(CanonicalJson.canonicalize(id.document), String((served as co.org.avance.ssi.vdr.PublicDoc.Found).bytes))
        assertEquals(1, f.vdrCount("SELECT count(*) FROM namespaces WHERE path = 'titulares/${id.record.thumbprint}' AND owner_client_id = 'wallet-backend'"))
        assertEquals(1, f.vdrCount("SELECT count(*) FROM audit_log WHERE action = 'PUBLICATION_CONFIRMED' AND did = '${id.did}'"))
        assertContains(f.backend.get("/instances/${app.activation!!.instanceId}/audit", app.activation!!.token).raw, "DID_PUBLISHED")

        // C4: almacenado sellado en el dispositivo y asociado a la instancia
        val (storedDid, storedDoc) = app.storedDocument()
        assertEquals(id.did, storedDid); assertEquals(id.document, storedDoc)

        // respaldo cifrado: el backend guarda bytes opacos, el titular los abre
        val bk = f.backend.get("/instances/${app.activation!!.instanceId}/did-backup", app.activation!!.token)
        assertEquals(200, bk.status)
        val blob = Base64.getUrlDecoder().decode(bk.str("ciphertext"))
        assertFalse(String(blob, Charsets.ISO_8859_1).contains(id.did), "el backend no debe poder leer el respaldo")
        val plain = app.custodian.unseal(blob, "did-backup:${app.activation!!.instanceId}".toByteArray())
        assertEquals(CanonicalJson.canonicalize(id.document), CanonicalJson.canonicalize(kotlinx.serialization.json.Json.parseToJsonElement(String(plain)) as JsonObject))

        // C1: ninguna clave privada (de las dos claves del dispositivo) llegó al backend ni al VDR
        assertEquals(2, known.size)
        known.forEach { kp -> privateForms(kp).forEach {
            assertFalse(f.service.containsText(it), "clave privada en tablas del backend")
            assertEquals(0, f.vdrCount("SELECT count(*) FROM did_document_versions WHERE document LIKE '%${it.replace("'", "''")}%'"), "clave privada en el VDR")
        } }
    }

    @Test fun `una cartera SOFTWARE tambien publica su DID (la clave declara SOFTWARE, sin fingir hardware)`() = runBlocking<Unit> {
        val f = fx()
        val app = softwareApp(f)
        val pub = app.createAndPublishDid()
        assertEquals(200, pub.publication.status, pub.publication.raw)
        assertEquals("SOFTWARE", pub.identity.record.protectionLevel)
        assertFalse(pub.identity.record.exportable)
    }

    // ==================== la validación es requisito previo a cualquier publicación ====================
    @Test fun `el backend rechaza DID fuera del namespace de titulares, no derivados de la clave y no conformes`() = runBlocking<Unit> {
        val f = fx()
        val app = softwareApp(f)
        val dev = f.softwareDevice()
        val id = HolderDidService(dev, domain).create()
        // (1) otro namespace (intento de suplantar a una entidad)
        val entidad = DidDocumentBuilder.build("did:web:$domain:entidades:avance", id.record.publicKeyMultibase)
        assertEquals("DID_OUT_OF_NAMESPACE", rawBegin(f, app, entidad).error)
        // (2) dominio ajeno
        assertEquals("DID_OUT_OF_NAMESPACE", rawBegin(f, app, DidDocumentBuilder.build("did:web:otro.example:titulares:${id.record.thumbprint}", id.record.publicKeyMultibase)).error)
        // (3) identificador que no se deriva de la clave
        val wrongId = DidDocumentBuilder.build("did:web:$domain:titulares:${"A".repeat(43)}", id.record.publicKeyMultibase)
        assertEquals("DID_NOT_DERIVED_FROM_KEY", rawBegin(f, app, wrongId).error)
        // (4) no conforme: datos civiles / extensión propietaria / clave privada
        val civil = JsonObject(id.document + ("credentialSubject" to JsonObject(mapOf("name" to JsonPrimitive("Ana")))))
        val r = rawBegin(f, app, civil)
        assertEquals("DID_NOT_CONFORMANT", r.error); assertTrue(r.details.any { it.startsWith("C09") } && r.details.any { it.startsWith("C10") }, r.details.toString())
        val priv = JsonObject(id.document + ("verificationMethod" to JsonArray(listOf(JsonObject(((id.document["verificationMethod"] as JsonArray)[0] as JsonObject) + ("privateKeyMultibase" to JsonPrimitive("z1")))))))
        assertTrue(rawBegin(f, app, priv).details.any { it.startsWith("C08") })
        // nada de esto llegó al VDR
        assertEquals(0, f.vdrCount("SELECT count(*) FROM did_documents"))
        assertEquals(0, f.count("SELECT count(*) FROM wallet_did_publications"))
    }

    @Test fun `una cartera de hardware exige que la clave del DID demuestre el mismo nivel`() = runBlocking<Unit> {
        val f = fx()
        val app = f.app(f.attestingDevice(ProtectionLevel.TEE)); app.onboard(); app.activate()
        // (1) sin attestation de la clave del DID
        val id = HolderDidService(f.softwareDevice(), domain).create()
        val r1 = rawBegin(f, app, id.document)
        assertEquals("KEY_ATTESTATION_REQUIRED", r1.error)
        // (2) attestation de nivel SOFTWARE para la clave del DID en una cartera TEE
        val weak = f.attestingDevice(ProtectionLevel.SOFTWARE)
        val ch = f.backend.post("/challenges", buildJsonObject { put("purpose", JsonPrimitive("DID_KEY")); put("instanceId", JsonPrimitive(app.activation!!.instanceId)) }, app.activation!!.token)
        val weakId = HolderDidService(weak, domain).create(attestationChallenge = ch.str("nonce").toByteArray())
        val chain = weak.attestation("holder-1")!!.chainDer.map { Base64.getEncoder().encodeToString(it) }
        val r2 = f.backend.post("/instances/${app.activation!!.instanceId}/did", buildJsonObject {
            put("document", weakId.document); put("keyAttestation", buildJsonObject { put("challengeId", ch.json!!["challengeId"]!!); put("chain", JsonArray(chain.map(::JsonPrimitive))) })
        }, app.activation!!.token)
        assertEquals("DID_KEY_BELOW_INSTANCE_LEVEL", r2.error)
    }

    // ==================== el VDR sigue exigiendo posesión de la clave del DID ====================
    @Test fun `firmar el desafio con otra clave hace fallar la publicacion y no deja rastro publico`() = runBlocking<Unit> {
        val f = fx()
        val app = softwareApp(f)
        val owner = f.softwareDevice(); val thief = f.softwareDevice()
        val svc = HolderDidService(owner, domain); val id = svc.create()
        thief.generate("ladron"); val thiefSvc = HolderDidService(thief, domain)
        val begin = rawBegin(f, app, id.document)
        assertEquals(201, begin.status, begin.raw)
        val forged = co.org.avance.ssi.didcore.Jws.signWith("${id.did}#key-1", begin.obj("signing").toString().toByteArray()) { thief.sign("ladron", it) }
        val done = f.backend.post("/instances/${app.activation!!.instanceId}/did/${begin.str("publicationId")}/proof", buildJsonObject { put("proof", JsonPrimitive(forged)) }, app.activation!!.token)
        assertEquals(422, done.status); assertEquals("VDR_INVALID_PROOF", done.error)
        assertEquals(0, f.vdrCount("SELECT count(*) FROM did_documents"))
        assertEquals(1, f.count("SELECT count(*) FROM wallet_did_publications WHERE status = 'FAILED'"))
        // el dueño legítimo puede reintentar con una publicación nueva
        val again = rawBegin(f, app, id.document)
        assertEquals(201, again.status)
        val ok = f.backend.post("/instances/${app.activation!!.instanceId}/did/${again.str("publicationId")}/proof", buildJsonObject { put("proof", JsonPrimitive(svc.sign(id, again.obj("signing").toString().toByteArray()))) }, app.activation!!.token)
        assertEquals(200, ok.status, ok.raw)
        assertTrue(thiefSvc !== svc)
    }

    @Test fun `publicar dos veces el mismo DID se rechaza y repetir la prueba es idempotente`() = runBlocking<Unit> {
        val f = fx()
        val app = softwareApp(f)
        val svc = HolderDidService(f.softwareDevice(), domain); val id = svc.create()
        val begin = rawBegin(f, app, id.document)
        val proof = svc.sign(id, begin.obj("signing").toString().toByteArray())
        val url = "/instances/${app.activation!!.instanceId}/did/${begin.str("publicationId")}/proof"
        val first = f.backend.post(url, buildJsonObject { put("proof", JsonPrimitive(proof)) }, app.activation!!.token)
        assertEquals(200, first.status)
        val second = f.backend.post(url, buildJsonObject { put("proof", JsonPrimitive(proof)) }, app.activation!!.token)
        assertEquals(200, second.status); assertEquals(first.str("version"), second.str("version"))
        assertEquals(1, f.vdrCount("SELECT count(*) FROM did_document_versions"))
        assertEquals("DID_ALREADY_PUBLISHED", rawBegin(f, app, id.document).error)
        assertEquals(409, rawBegin(f, app, id.document).status)
    }

    // ==================== aislamiento entre instancias ====================
    @Test fun `una instancia no puede completar la publicacion de otra ni usar un token revocado`() = runBlocking<Unit> {
        val f = fx()
        val a = softwareApp(f); val b = softwareApp(f)
        val svc = HolderDidService(f.softwareDevice(), domain); val id = svc.create()
        val begin = rawBegin(f, a, id.document)
        val proof = svc.sign(id, begin.obj("signing").toString().toByteArray())
        val steal = f.backend.post("/instances/${b.activation!!.instanceId}/did/${begin.str("publicationId")}/proof", buildJsonObject { put("proof", JsonPrimitive(proof)) }, b.activation!!.token)
        assertEquals(404, steal.status)
        // token de A contra el id de B
        assertEquals(401, f.backend.post("/instances/${b.activation!!.instanceId}/did", buildJsonObject { put("document", id.document) }, a.activation!!.token).status)
        // revocada: no puede publicar
        f.backend.post("/instances/${a.activation!!.instanceId}/revoke", JsonObject(emptyMap()), a.activation!!.token)
        val r = rawBegin(f, a, id.document)
        assertEquals(403, r.status); assertEquals("INSTANCE_REVOKED", r.error)
    }

    @Test fun `el respaldo cifrado tiene limites y solo lo lee su dueno`() = runBlocking<Unit> {
        val f = fx()
        val a = softwareApp(f); val b = softwareApp(f)
        val tokenA = a.activation!!.token; val idA = a.activation!!.instanceId
        assertEquals(404, f.backend.get("/instances/$idA/did-backup", tokenA).status)
        assertEquals(200, f.backend.put("/instances/$idA/did-backup", buildJsonObject { put("ciphertext", JsonPrimitive("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")) }, tokenA).status)
        assertEquals(401, f.backend.get("/instances/$idA/did-backup", b.activation!!.token).status)
        assertEquals(413, f.backend.put("/instances/$idA/did-backup", buildJsonObject { put("ciphertext", JsonPrimitive("A".repeat(100_000))) }, tokenA).status)
        assertEquals(400, f.backend.put("/instances/$idA/did-backup", buildJsonObject { put("ciphertext", JsonPrimitive("!!no-es-base64url!!")) }, tokenA).status)
    }

    @Test fun `el DID se deriva de la clave - mismo formato que el documento y sin datos civiles`() = runBlocking<Unit> {
        val f = fx()
        val app = softwareApp(f)
        val pub = app.createAndPublishDid()
        val parsed = DidWeb.parse(pub.identity.did)
        assertEquals(listOf("titulares", pub.identity.record.thumbprint), parsed.path)
        assertEquals("https://$domain/titulares/${pub.identity.record.thumbprint}/did.json", DidWeb.url(pub.identity.did))
        assertEquals(HttpStatusCode.OK.value, pub.publication.status)
        assertFailsWith<StoreAccessException> { f.app(f.softwareDevice()).also { it.activation = app.activation }.storedDocument() }
        assertEquals(ProtectionLevel.SOFTWARE.name, pub.identity.record.protectionLevel)
        assertTrue(KeyPolicy().minimumLevel == ProtectionLevel.SOFTWARE)
    }

    @Test fun `guarda - todo metodo de prueba devuelve void para que JUnit lo ejecute`() {
        val bad = Erso003HolderDidTest::class.java.declaredMethods.filter { it.isAnnotationPresent(Test::class.java) && it.returnType != Void.TYPE }.map { it.name }
        assertTrue(bad.isEmpty(), "Estas pruebas NO se ejecutarían: $bad")
    }
}
