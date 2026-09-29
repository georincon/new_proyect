package co.org.avance.ssi.vdr

import co.org.avance.ssi.didcore.CanonicalJson
import co.org.avance.ssi.didcore.DidKeys
import co.org.avance.ssi.didcore.DidWeb
import co.org.avance.ssi.didcore.Multikey
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.interfaces.ECPublicKey
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ERSo 2026-005 — Publicación del DID Document bajo did:web con clave P-256 en Multikey (4 criterios). */
class Erso005DidWebTest {
    private var f: Fixture? = null
    private fun fixture() = Fixture.create().also { f = it }

    @AfterTest fun cleanup() { f?.close() }

    @Test fun `paso 1 - el identificador did web sale del dominio configurado`() {
        val cfg = AppConfig()
        assertEquals("civica-desarrollo.avance.org.co", cfg.domain)
        val did = DidWeb.of(cfg.domain, listOf("lab", "laboratorio"))
        assertEquals("did:web:civica-desarrollo.avance.org.co:lab:laboratorio", did)
        assertEquals("https://civica-desarrollo.avance.org.co/lab/laboratorio/did.json", DidWeb.url(did))
    }

    @Test fun `criterio 1 y 2 - se sirve en la URL calculada, con la clave publica correcta y hash igual`() = runBlocking {
        val f = fixture()
        val t = f.bearer(f.lab)
        val doc = f.lab.document()
        val r = f.write(f.lab, t, Purpose.CREATE, doc, 0, cn = "lab-operator")
        assertEquals(HttpStatusCode.Created, r.status)
        assertEquals(DidWeb.url(f.lab.did), r.obj()["publicUrl"]!!.jsonPrimitive.content)

        val served = f.publicDid(f.lab)
        assertEquals(HttpStatusCode.OK, served.status)
        val bytes = served.bodyAsBytes()
        assertEquals(CanonicalJson.hash(doc), CanonicalJson.sha256(bytes), "contenido y hash coinciden con lo construido")

        val published = Json.parseToJsonElement(String(bytes)).jsonObject
        val multikey = published["verificationMethod"]!!.jsonArray[0].jsonObject["publicKeyMultibase"]!!.jsonPrimitive.content
        assertEquals(DidKeys.multikey(f.lab.key), multikey)
        assertEquals((f.lab.key.public as ECPublicKey).w, Multikey.decodeP256(multikey).w, "la clave publicada es exactamente la clave P-256 generada")
    }

    @Test fun `criterio 3 - el documento publicado solo contiene material publico`() = runBlocking {
        val f = fixture()
        f.write(f.lab, f.bearer(f.lab), Purpose.CREATE, f.lab.document(), 0, cn = "lab-operator")
        val text = String(f.publicDid(f.lab).bodyAsBytes())
        listOf("privateKey", "secret", "\"d\":", "credentialSubject").forEach { assertFalse(text.contains(it)) }
        // la clave privada generada nunca aparece en el documento
        val privateB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString((f.lab.key.private as java.security.interfaces.ECPrivateKey).s.toByteArray())
        assertFalse(text.contains(privateB64))
    }

    @Test fun `criterio 4 - un id desajustado se rechaza`() = runBlocking {
        val f = fixture()
        val t = f.bearer(f.lab)
        val desajustado = JsonObject(f.lab.document() + ("id" to JsonPrimitive("did:web:civica-desarrollo.avance.org.co:lab:otro")))
        val r = f.write(f.lab, t, Purpose.CREATE, desajustado, 0, cn = "lab-operator", proofDoc = desajustado)
        assertEquals(HttpStatusCode.UnprocessableEntity, r.status)
        assertTrue(r.details().any { it.startsWith("ID_MISMATCH") })
        assertEquals(0, f.count("did_documents"))
        assertEquals(HttpStatusCode.NotFound, f.publicDid(f.lab).status)
    }

    @Test fun `no se publica por defecto ningun DID de ciudadano - solo namespaces reservados`() = runBlocking {
        val f = fixture()
        val t = f.bearer(f.lab)
        val ciudadano = "did:web:civica-desarrollo.avance.org.co:ciudadanos:ana"
        val r = f.challenge(t, ciudadano, "CREATE", cn = "lab-operator")
        assertEquals(HttpStatusCode.PreconditionFailed, r.status)
        assertTrue(r.details().contains("NAMESPACE_NOT_RESERVED"))
        assertEquals(0, f.count("did_documents"))
    }
}
