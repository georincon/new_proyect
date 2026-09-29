package co.org.avance.ssi.vdr

import co.org.avance.ssi.didcore.CanonicalJson
import co.org.avance.ssi.didcore.DidDocumentValidator
import co.org.avance.ssi.didcore.Profile
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ERSo 2026-006 — Publicación del DID Document institucional (4 criterios de aceptación). */
class Erso006InstitutionalTest {
    private var f: Fixture? = null
    private fun fixture() = Fixture.create().also { f = it }

    @AfterTest fun cleanup() { f?.close() }

    @Test fun `criterio 1 - el documento institucional se sirve por el canal publico y es resoluble`() = runBlocking {
        val f = fixture()
        val t = f.bearer(f.avance)
        val doc = f.avance.document()
        assertEquals(HttpStatusCode.Created, f.write(f.avance, t, Purpose.CREATE, doc, 0).status)

        val r = f.publicDid(f.avance)
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("application/did+json", r.headers[HttpHeaders.ContentType]!!.substringBefore(";"))
        val bytes = r.bodyAsBytes()
        assertEquals(CanonicalJson.sha256(bytes), CanonicalJson.hash(doc))
        assertEquals(f.avance.did, Json.parseToJsonElement(String(bytes)).jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("\"1\"", r.headers[HttpHeaders.ETag])
    }

    @Test fun `criterio 2 - la escritura esta autenticada, trazada y limitada al namespace de la entidad`() = runBlocking {
        val f = fixture()

        // sin token -> 401 y queda rastro
        val sinToken = f.admin.post("/admin/v1/challenges")
        assertEquals(HttpStatusCode.Unauthorized, sinToken.status)
        // secreto equivocado -> 401
        assertEquals(HttpStatusCode.Unauthorized, f.token(f.avance, secret = "mala").status)
        // credenciales correctas pero sin certificado cliente (mTLS) -> 403
        assertEquals(HttpStatusCode.Forbidden, f.token(f.avance, cn = null).status)
        // certificado de OTRA entidad con las credenciales de avance -> 403
        assertEquals("MTLS_REQUIRED", f.token(f.avance, cn = "lab-operator").errorCode())

        val avanceToken = f.bearer(f.avance)
        val labToken = f.bearer(f.lab)

        // la entidad laboratorio intenta escribir en el namespace de avance -> 403 (aunque tenga token válido)
        val doc = f.avance.document()
        val ch = f.challenge(avanceToken, f.avance.did, "CREATE").obj()
        val intento = f.write(f.avance, labToken, Purpose.CREATE, doc, 0, cn = "lab-operator", reuseChallenge = ch)
        assertEquals(HttpStatusCode.Forbidden, intento.status)
        assertEquals("NAMESPACE_NOT_OWNED", intento.errorCode())
        assertEquals(0, f.count("did_documents"))

        // un token válido de avance NO sirve con el certificado de laboratorio
        val cruzado = f.write(f.avance, avanceToken, Purpose.CREATE, doc, 0, cn = "lab-operator", reuseChallenge = ch)
        assertEquals("MTLS_REQUIRED", cruzado.errorCode())

        // los tres intentos quedaron registrados
        val all = f.auditActions()
        assertContains(all, "AUTH_DENIED")
        assertContains(all, "TOKEN_DENIED")
        assertContains(all, "WRITE_DENIED")
        assertContains(all, "TOKEN_ISSUED")
        assertContains(all, "CHALLENGE_ISSUED")

        // escritura correcta: queda trazada con actor, versión y hash
        assertEquals(HttpStatusCode.Created, f.write(f.avance, avanceToken, Purpose.CREATE, doc, 0).status)
        val write = f.registry.audit(f.avance.did).first { it.action == "WRITE_CREATE" }
        assertEquals("avance-issuer", write.actor)
        assertEquals(1, write.version)
        assertTrue(write.detail.contains(CanonicalJson.hash(doc)))

        // lectura de la traza solo dentro del propio namespace
        assertEquals(HttpStatusCode.Forbidden, f.get("/admin/v1/documents/${f.avance.did}/versions", labToken, "lab-operator").status)
        assertEquals(HttpStatusCode.OK, f.get("/admin/v1/documents/${f.avance.did}/versions", avanceToken, "avance-issuer").status)
    }

    @Test fun `canales separados - el canal publico no escribe y el de escritura no sirve documentos`() = runBlocking {
        val f = fixture()
        val t = f.bearer(f.avance)
        f.write(f.avance, t, Purpose.CREATE, f.avance.document(), 0)

        val path = "/entidades/avance/did.json"
        for (status in listOf(f.public.post(path).status, f.public.put(path).status, f.public.delete(path).status)) {
            assertFalse(status.value in 200..299, "el canal público no debe aceptar escrituras ($status)")
        }
        assertEquals(HttpStatusCode.NotFound, f.admin.get(path).status)
        assertEquals(HttpStatusCode.Unauthorized, f.admin.get("/admin/v1/audit").status)
    }

    @Test fun `criterio 3 - el documento publicado no expone claves privadas ni datos civiles`() = runBlocking {
        val f = fixture()
        val t = f.bearer(f.avance)
        f.write(f.avance, t, Purpose.CREATE, f.avance.document(), 0)
        val text = f.publicDid(f.avance).bodyAsText()
        val doc = Json.parseToJsonElement(text).jsonObject

        listOf("privateKey", "secretKey", "\"d\":", "credentialSubject", "birthDate", "documentNumber").forEach { assertFalse(text.contains(it), "no debe contener $it") }
        assertEquals(emptyList(), DidDocumentValidator.validate(doc, f.avance.did, Profile.PUBLISHER))
        // solo material público: una única clave Multikey en formato de clave pública comprimida
        assertEquals(1, doc["verificationMethod"]!!.jsonArray.size)
        assertTrue(doc["verificationMethod"]!!.jsonArray[0].jsonObject["publicKeyMultibase"]!!.jsonPrimitive.content.startsWith("zDn"))
    }

    @Test fun `criterio 4 - la evidencia de publicacion version hash y URL queda registrada`() = runBlocking {
        val f = fixture()
        val t = f.bearer(f.avance)
        val doc = f.avance.document()
        val resp = f.write(f.avance, t, Purpose.CREATE, doc, 0).obj()
        val opId = resp["operationId"]!!.jsonPrimitive.content

        val op = f.get("/admin/v1/operations/$opId", t, "avance-issuer").obj()
        assertEquals("CONFIRMED", op["status"]!!.jsonPrimitive.content)
        assertEquals(1, op["version"]!!.jsonPrimitive.content.toInt())
        assertEquals(CanonicalJson.hash(doc), op["hash"]!!.jsonPrimitive.content)
        assertEquals("https://civica-desarrollo.avance.org.co/entidades/avance/did.json", op["publicUrl"]!!.jsonPrimitive.content)
        assertTrue(op["confirmedAt"]!!.jsonPrimitive.content.isNotEmpty())
        assertEquals(1, f.count("operations", "status = 'CONFIRMED'"))
        assertContains(f.auditActions(f.avance.did), "PUBLICATION_CONFIRMED")
    }

    @Test fun `una entidad no puede colisionar con el namespace de otra`() = runBlocking {
        val f = fixture()
        val ta = f.bearer(f.avance); val tl = f.bearer(f.lab)
        assertEquals(HttpStatusCode.Created, f.write(f.avance, ta, Purpose.CREATE, f.avance.document(), 0).status)
        assertEquals(HttpStatusCode.Created, f.write(f.lab, tl, Purpose.CREATE, f.lab.document(), 0).status)
        assertEquals(2, f.count("did_documents"))
        assertTrue(f.avance.did != f.lab.did)
    }
}
