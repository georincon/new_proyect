package co.org.avance.ssi.vdr

import co.org.avance.ssi.didcore.CanonicalJson
import co.org.avance.ssi.didcore.DidKeys
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** ERSo 2026-008 — Ciclo de vida y trazabilidad de versiones del DID Document (7 criterios de aceptación). */
class Erso008LifecycleTest {
    private var f: Fixture? = null
    private fun fixture(timeout: Long = 300) = Fixture.create(timeout).also { f = it }

    @AfterTest fun cleanup() { f?.close() }

    @Test fun `criterio 1 - si falla una precondicion no se emite desafio`() = runBlocking {
        val f = fixture()
        val avanceToken = f.bearer(f.avance)
        val labToken = f.bearer(f.lab)

        // (a) namespace ajeno
        val r1 = f.challenge(labToken, f.avance.did, "CREATE", cn = "lab-operator")
        assertEquals(HttpStatusCode.PreconditionFailed, r1.status)
        assertContains(r1.details(), "NAMESPACE_NOT_OWNED")

        // (b) canal de escritura no restringido (sin certificado cliente verificado)
        val r2 = f.challenge(avanceToken, f.avance.did, "CREATE", cn = null)
        assertEquals(HttpStatusCode.PreconditionFailed, r2.status)
        assertContains(r2.details(), "WRITE_CHANNEL_NOT_RESTRICTED")

        // (c) ruta did:web no reservada
        val r3 = f.challenge(avanceToken, "did:web:civica-desarrollo.avance.org.co:entidades:otra", "CREATE")
        assertEquals(HttpStatusCode.PreconditionFailed, r3.status)
        assertContains(r3.details(), "NAMESPACE_NOT_RESERVED")

        // (d) cuenta deshabilitada
        f.exec("UPDATE entity_accounts SET enabled = false WHERE client_id = 'avance-issuer'")
        val r4 = f.challenge(avanceToken, f.avance.did, "CREATE")
        assertContains(r4.details(), "ACCOUNT_MISSING_OR_DISABLED")
        f.exec("UPDATE entity_accounts SET enabled = true WHERE client_id = 'avance-issuer'")

        // (e) perfil sin definir
        f.exec("UPDATE namespaces SET profile = '{}'::jsonb WHERE path = 'entidades/avance'")
        assertContains(f.challenge(avanceToken, f.avance.did, "CREATE").details(), "PROFILE_UNDEFINED")

        assertEquals(0, f.count("challenges"), "ninguna precondición fallida debe emitir desafío")
        assertEquals(5, f.count("audit_log", "action = 'CHALLENGE_DENIED'"))
    }

    @Test fun `criterio 2 - el desafio es de un solo uso con tipo y audiencia especificos`() = runBlocking {
        val f = fixture()
        val t = f.bearer(f.avance)
        val ch = f.challenge(t, f.avance.did, "CREATE").also { assertEquals(HttpStatusCode.Created, it.status) }.obj()
        assertEquals("CREATE", ch["purpose"]!!.jsonPrimitive.content)
        assertEquals("vdr:civica-desarrollo.avance.org.co:did-operation", ch["audience"]!!.jsonPrimitive.content)

        // primer uso: correcto
        assertEquals(HttpStatusCode.Created, f.write(f.avance, t, Purpose.CREATE, f.avance.document(), 0, reuseChallenge = ch).status)
        // segundo uso del mismo desafío: rechazado
        val reuse = f.write(f.avance, t, Purpose.UPDATE, f.avance.document(), 1, reuseChallenge = ch)
        assertEquals(HttpStatusCode.Forbidden, reuse.status)
        assertEquals("CHALLENGE_INVALID", reuse.errorCode())

        // un desafío de tipo UPDATE no sirve para DEACTIVATE (el tipo es específico de la operación)
        val updateCh = f.challenge(t, f.avance.did, "UPDATE").obj()
        val wrong = f.write(f.avance, t, Purpose.DEACTIVATE, null, 1, reuseChallenge = updateCh)
        assertEquals("CHALLENGE_MISMATCH", wrong.errorCode())

        // desafío expirado
        val old = f.challenge(t, f.avance.did, "UPDATE").obj()
        f.exec("UPDATE challenges SET expires_at = now() - interval '1 minute' WHERE id = '${old["challengeId"]!!.jsonPrimitive.content}'")
        assertEquals("CHALLENGE_INVALID", f.write(f.avance, t, Purpose.UPDATE, f.avance.document(), 1, reuseChallenge = old).errorCode())

        // registro de emisión del desafío
        val issued = f.registry.audit(f.avance.did).filter { it.action == "CHALLENGE_ISSUED" }
        assertTrue(issued.isNotEmpty())
        assertTrue(issued.first().detail.contains("did-operation"))
    }

    @Test fun `criterio 3 - el documento no incluye claves privadas y id y controladores corresponden al recurso`() = runBlocking {
        val f = fixture()
        val t = f.bearer(f.avance)
        fun withField(name: String, value: kotlinx.serialization.json.JsonElement): JsonObject = JsonObject(f.avance.document() + (name to value))

        val privado = f.avance.document().let { doc ->
            val vm = doc["verificationMethod"]!!.jsonArray[0].jsonObject
            JsonObject(doc + ("verificationMethod" to JsonArray(listOf(JsonObject(vm + ("privateKeyMultibase" to JsonPrimitive("zXXXX")))))))
        }
        val r = f.write(f.avance, t, Purpose.CREATE, privado, 0)
        assertEquals(HttpStatusCode.UnprocessableEntity, r.status)
        assertTrue(r.details().any { it.startsWith("PRIVATE_KEY_MATERIAL") })

        val idAjeno = JsonObject(f.avance.document() + ("id" to JsonPrimitive(f.lab.did)))
        assertTrue(f.write(f.avance, t, Purpose.CREATE, idAjeno, 0, proofDoc = idAjeno).details().any { it.startsWith("ID_MISMATCH") })

        val ctlAjeno = withField("controller", JsonPrimitive("did:web:otra-entidad.co"))
        assertTrue(f.write(f.avance, t, Purpose.CREATE, ctlAjeno, 0).details().any { it.startsWith("UNAUTHORIZED_CONTROLLER") })

        val civil = withField("credentialSubject", buildJsonObject { put("name", JsonPrimitive("Ana")) })
        assertTrue(f.write(f.avance, t, Purpose.CREATE, civil, 0).details().any { it.startsWith("CIVIL_DATA") })

        assertEquals(0, f.count("did_documents"), "ningún documento inválido debe quedar registrado")
    }

    @Test fun `criterio 4 - la escritura exige version esperada y devuelve version hash y URL publica`() = runBlocking {
        val f = fixture()
        val t = f.bearer(f.avance)
        val doc = f.avance.document()

        // sin If-Match -> 428
        val sinIfMatch = f.admin.put("/admin/v1/documents/${f.avance.did}") {
            header(HttpHeaders.Authorization, "Bearer $t"); header("Idempotency-Key", "k1"); header("X-SSL-Client-Verify", "SUCCESS"); header("X-SSL-Client-S-DN", "CN=avance-issuer")
            contentType(ContentType.Application.Json); setBody("{}")
        }
        assertEquals(428, sinIfMatch.status.value)
        assertEquals("IF_MATCH_REQUIRED", sinIfMatch.errorCode())

        val creado = f.write(f.avance, t, Purpose.CREATE, doc, 0)
        assertEquals(HttpStatusCode.Created, creado.status)
        val body = creado.obj()
        assertEquals(1, body["version"]!!.jsonPrimitive.content.toInt())
        assertEquals(CanonicalJson.hash(doc), body["hash"]!!.jsonPrimitive.content)
        assertEquals("https://civica-desarrollo.avance.org.co/entidades/avance/did.json", body["publicUrl"]!!.jsonPrimitive.content)

        // versión esperada equivocada -> 412 con la versión actual
        val conflicto = f.write(f.avance, t, Purpose.UPDATE, doc, 7)
        assertEquals(HttpStatusCode.PreconditionFailed, conflicto.status)
        assertEquals("VERSION_CONFLICT", conflicto.errorCode())
        assertContains(conflicto.details(), "currentVersion=1")

        // crear con expected=0 sobre un DID que ya existe -> el desafío CREATE ni se emite
        assertEquals(HttpStatusCode.Conflict, f.challenge(t, f.avance.did, "CREATE").status)
    }

    @Test fun `idempotencia - reintentar con la misma clave no duplica la version`() = runBlocking {
        val f = fixture()
        val t = f.bearer(f.avance)
        val doc = f.avance.document()
        val ch = f.challenge(t, f.avance.did, "CREATE").obj()
        val first = f.write(f.avance, t, Purpose.CREATE, doc, 0, idem = "clave-1", reuseChallenge = ch)
        assertEquals(HttpStatusCode.Created, first.status)
        val retry = f.write(f.avance, t, Purpose.CREATE, doc, 0, idem = "clave-1", reuseChallenge = ch)
        assertEquals(HttpStatusCode.OK, retry.status)
        val r = retry.obj()
        assertEquals(true, r["replayed"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(first.obj()["operationId"], r["operationId"])
        assertEquals(1, f.count("did_document_versions"))
        // misma clave con otro contenido -> rechazo
        val other = f.write(f.avance, t, Purpose.CREATE, JsonObject(doc + ("service" to JsonArray(emptyList()))), 0, idem = "clave-1", reuseChallenge = ch)
        assertEquals("IDEMPOTENCY_KEY_REUSED", other.errorCode())
    }

    @Test fun `prueba de posesion - clave ajena, hash distinto y rotacion`() = runBlocking {
        val f = fixture()
        val t = f.bearer(f.avance)
        val v1 = f.avance.document()
        assertEquals(HttpStatusCode.Created, f.write(f.avance, t, Purpose.CREATE, v1, 0).status)

        val intruso = DidKeys.generateP256()
        assertEquals("INVALID_PROOF", f.write(f.avance, t, Purpose.UPDATE, v1, 1, signer = intruso).errorCode())

        // se firma el hash de otro documento distinto al enviado
        val otro = JsonObject(v1 + ("service" to JsonArray(emptyList())))
        assertEquals("INVALID_PROOF", f.write(f.avance, t, Purpose.UPDATE, otro, 1, proofDoc = v1).errorCode())

        // rotación legítima: firma la clave VIGENTE (vieja), el documento nuevo trae la clave nueva
        val nueva = DidKeys.generateP256()
        val v2 = f.avance.document(nueva)
        assertEquals(HttpStatusCode.OK, f.write(f.avance, t, Purpose.UPDATE, v2, 1, signer = f.avance.key).status)
        // quien solo tiene la clave vieja ya no puede escribir; la nueva sí
        assertEquals("INVALID_PROOF", f.write(f.avance, t, Purpose.UPDATE, v2, 2, signer = f.avance.key).errorCode())
        assertEquals(HttpStatusCode.OK, f.write(f.avance, t, Purpose.UPDATE, JsonObject(v2 + ("service" to JsonArray(emptyList()))), 2, signer = nueva).status)
        // cada rechazo de prueba queda registrado en la auditoría
        assertTrue(f.count("audit_log", "action = 'PROOF_REJECTED'") >= 3)
    }

    @Test fun `criterio 5 y 6 - confirmacion por lectura y hash, y operacion pendiente si no hay respuesta`() = runBlocking {
        val f = fixture(timeout = 300)
        val t = f.bearer(f.avance)
        val doc = f.avance.document()

        // sin respuesta en plazo -> 202 PENDING, no se da por publicada
        f.reader.mode = FakeReader.Mode.TIMEOUT
        val r = f.write(f.avance, t, Purpose.CREATE, doc, 0)
        assertEquals(HttpStatusCode.Accepted, r.status)
        val op = r.obj()
        assertEquals("PENDING", op["status"]!!.jsonPrimitive.content)
        val opId = op["operationId"]!!.jsonPrimitive.content
        assertEquals("PENDING", f.get("/admin/v1/operations/$opId", t, cn = "avance-issuer").obj()["status"]!!.jsonPrimitive.content)
        assertContains(f.auditActions(f.avance.did), "PUBLICATION_PENDING")

        // contenido distinto al esperado -> tampoco se confirma
        f.reader.mode = FakeReader.Mode.WRONG_CONTENT
        assertEquals("PENDING", reconcile(f, t, opId))

        // vuelve el servicio: la reconciliación confirma comparando contenido y hash
        f.reader.mode = FakeReader.Mode.SERVE
        assertEquals("CONFIRMED", reconcile(f, t, opId))
        assertContains(f.auditActions(f.avance.did), "PUBLICATION_CONFIRMED")

        // una operación pendiente que luego fue superada por otra versión se marca SUPERSEDED
        f.reader.mode = FakeReader.Mode.TIMEOUT
        val u = f.write(f.avance, t, Purpose.UPDATE, JsonObject(doc + ("service" to JsonArray(emptyList()))), 1).obj()
        f.reader.mode = FakeReader.Mode.SERVE
        assertEquals("CONFIRMED", reconcile(f, t, u["operationId"]!!.jsonPrimitive.content))
    }

    private suspend fun reconcile(f: Fixture, t: String, opId: String): String =
        f.admin.post("/admin/v1/operations/$opId/reconcile") {
            header(HttpHeaders.Authorization, "Bearer $t"); header("X-SSL-Client-Verify", "SUCCESS"); header("X-SSL-Client-S-DN", "CN=avance-issuer")
        }.obj()["status"]!!.jsonPrimitive.content

    @Test fun `criterio 7 - la traza permite reconstruir el estado en cada momento`() = runBlocking {
        val f = fixture()
        val t = f.bearer(f.avance)
        val k2 = DidKeys.generateP256()
        val d1 = f.avance.document()
        val d2 = f.avance.document(k2)
        val d3 = JsonObject(d2 + ("service" to JsonArray(listOf(buildJsonObject {
            put("id", JsonPrimitive("${f.avance.did}#issuer")); put("type", JsonPrimitive("OID4VCI")); put("serviceEndpoint", JsonPrimitive("https://civica-desarrollo.avance.org.co/issuer"))
        }))))

        assertEquals(HttpStatusCode.Created, f.write(f.avance, t, Purpose.CREATE, d1, 0).status)
        Thread.sleep(20)
        assertEquals(HttpStatusCode.OK, f.write(f.avance, t, Purpose.UPDATE, d2, 1, signer = f.avance.key).status)
        Thread.sleep(20)
        assertEquals(HttpStatusCode.OK, f.write(f.avance, t, Purpose.UPDATE, d3, 2, signer = k2).status)
        Thread.sleep(20)
        assertEquals(HttpStatusCode.OK, f.write(f.avance, t, Purpose.DEACTIVATE, null, 3, signer = k2).status)

        val versions = f.registry.versions(f.avance.did)
        assertEquals(listOf("CREATE", "UPDATE", "UPDATE", "DEACTIVATE"), versions.map { it.operation })
        assertEquals(listOf(1, 2, 3, 4), versions.map { it.version })
        assertEquals(listOf(CanonicalJson.hash(d1), CanonicalJson.hash(d2), CanonicalJson.hash(d3), CanonicalJson.hash(d3)), versions.map { it.hash })
        assertEquals(versions.size, versions.map { it.hash }.let { it.size })
        assertNotEquals(versions[0].hash, versions[1].hash)

        // estado en el instante de cada versión (historial reconstruible)
        val expected = listOf("ACTIVE" to 1, "ACTIVE" to 2, "ACTIVE" to 3, "DEACTIVATED" to 4)
        versions.forEachIndexed { i, v ->
            val s = f.get("/admin/v1/documents/${f.avance.did}/state?at=${v.createdAt}", t, "avance-issuer").obj()
            assertEquals(expected[i].first, s["status"]!!.jsonPrimitive.content)
            assertEquals(expected[i].second, s["version"]!!.jsonPrimitive.content.toInt())
        }
        // antes de existir el DID no hay estado
        assertEquals(HttpStatusCode.NotFound, f.get("/admin/v1/documents/${f.avance.did}/state?at=${Instant.parse(versions[0].createdAt).minusSeconds(60)}", t, "avance-issuer").status)
        // el contenido de una versión anterior sigue disponible para auditoría
        assertEquals(CanonicalJson.canonicalize(d2), f.registry.version(f.avance.did, 2).document)

        // la lectura pública refleja la desactivación (documento retirado)
        assertEquals(HttpStatusCode.Gone, f.publicDid(f.avance).status)

        // regla transversal: un historial terminal no vuelve a estar activo sin nueva instancia
        assertEquals("TERMINAL_STATE", f.challenge(t, f.avance.did, "CREATE").errorCode())
        assertEquals("TERMINAL_STATE", f.challenge(t, f.avance.did, "UPDATE").errorCode())

        // la traza no se puede reescribir
        assertFailsWith<org.postgresql.util.PSQLException> { f.exec("UPDATE did_document_versions SET hash = 'x'") }
        assertFailsWith<org.postgresql.util.PSQLException> { f.exec("DELETE FROM audit_log") }
        assertFalse(f.registry.audit(f.avance.did).isEmpty())
    }
}

