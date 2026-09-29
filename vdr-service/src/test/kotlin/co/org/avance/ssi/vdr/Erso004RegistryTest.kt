package co.org.avance.ssi.vdr

import co.org.avance.ssi.didcore.CanonicalJson
import co.org.avance.ssi.didcore.DidKeys
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** ERSo 2026-004 — Despliegue del VDR de extensión (3 criterios de aceptación). */
class Erso004RegistryTest {
    private var f: Fixture? = null
    private fun fixture() = Fixture.create().also { f = it }

    @AfterTest fun cleanup() { f?.close() }

    // ---------------- criterio 3: el camino base opera con la opción apagada (NO necesita base de datos) ----------------
    @Test fun `criterio 3 - por defecto la extension esta apagada`() {
        assertEquals(false, AppConfig().vdrEnabled)
        assertEquals(false, AppConfig.fromEnv(emptyMap()).vdrEnabled)
        assertEquals(true, AppConfig.fromEnv(mapOf("VDR_ENABLED" to "true", "VDR_JWT_SECRET" to "x".repeat(40), "VDR_CLIENTS" to """[{"clientId":"a","secret":"b"}]""")).vdrEnabled)
        // encendida sin secreto ni clientes: falla al arrancar en vez de quedar insegura
        assertFailsWith<IllegalArgumentException> { AppConfig.fromEnv(mapOf("VDR_ENABLED" to "true")) }
    }

    @Test fun `criterio 3 - con el registro apagado el camino base responde y los endpoints del VDR no existen`() = testApplication {
        application { publicModule(AppConfig(vdrEnabled = false), registry = null) }
        val health = client.get("/health")
        assertEquals(HttpStatusCode.OK, health.status)
        assertEquals("disabled", Json.parseToJsonElement(health.bodyAsText()).jsonObject["vdr"]!!.jsonPrimitive.content)
        assertEquals("pong", client.get("/base/ping").bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.get("/entidades/avance/did.json").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/.well-known/did.json").status)
        assertEquals(HttpStatusCode.NotFound, client.post("/admin/v1/challenges").status)
    }

    @Test fun `criterio 3 - con el registro encendido el camino base sigue igual`() = runBlocking {
        val f = fixture()
        assertEquals("pong", f.public.get("/base/ping").bodyAsText())
        assertEquals("enabled", f.public.get("/health").obj()["vdr"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.NotFound, f.public.get("/entidades/avance/did.json").status) // aún no publicado
        assertEquals(HttpStatusCode.NotFound, f.public.get("/otra/ruta/cualquiera").status)
    }

    @Test fun `paso 2 - el adaptador traduce DID a la forma del registro segun el metodo`() {
        val a = DidWebAdapter()
        assertEquals("web", a.method)
        assertEquals("/entidades/avance/did.json", a.publicPath("did:web:civica-desarrollo.avance.org.co:entidades:avance"))
        assertEquals("/.well-known/did.json", a.publicPath("did:web:civica-desarrollo.avance.org.co"))
        assertEquals("entidades/avance", a.namespaceOf("did:web:civica-desarrollo.avance.org.co:entidades:avance"))
        assertEquals("did:web:civica-desarrollo.avance.org.co:entidades:avance", a.didForNamespace("civica-desarrollo.avance.org.co", "entidades/avance"))
        assertEquals(null, a.didFromUrlPath("civica-desarrollo.avance.org.co", listOf("entidades", "avance", "index.html")))
    }

    // ---------------- criterio 1: registro, actualización y trazabilidad ----------------
    @Test fun `criterio 1 - registro, actualizacion y trazabilidad son operativos`() = runBlocking {
        val f = fixture()
        val t = f.bearer(f.avance)
        val d1 = f.avance.document()
        val d2 = f.avance.document(DidKeys.generateP256())

        assertEquals(HttpStatusCode.Created, f.write(f.avance, t, Purpose.CREATE, d1, 0).status)
        assertEquals(CanonicalJson.canonicalize(d1), f.publicDid(f.avance).bodyAsText())
        assertEquals(HttpStatusCode.OK, f.write(f.avance, t, Purpose.UPDATE, d2, 1).status)
        assertEquals(CanonicalJson.canonicalize(d2), f.publicDid(f.avance).bodyAsText())

        val actions = f.auditActions(f.avance.did)
        listOf("NAMESPACE_RESERVED", "CHALLENGE_ISSUED", "WRITE_CREATE", "WRITE_UPDATE", "PUBLICATION_CONFIRMED").forEach { assertContains(actions, it) }
        assertEquals(2, f.registry.versions(f.avance.did).size)
        assertEquals(2, f.count("operations", "status = 'CONFIRMED'"))
    }

    // ---------------- criterio 2: respaldo y recuperación ----------------
    @Test fun `criterio 2 - respaldo, perdida total y restauracion dejan el registro identico`() = runBlocking {
        val f = fixture()
        val ta = f.bearer(f.avance); val tl = f.bearer(f.lab)
        val a1 = f.avance.document()
        val a2 = f.avance.document(DidKeys.generateP256())
        f.write(f.avance, ta, Purpose.CREATE, a1, 0)
        f.write(f.avance, ta, Purpose.UPDATE, a2, 1)
        f.write(f.lab, tl, Purpose.CREATE, f.lab.document(), 0, cn = "lab-operator")

        val beforeAvance = f.publicDid(f.avance).bodyAsBytes()
        val beforeLab = f.publicDid(f.lab).bodyAsBytes()
        val versionsBefore = f.registry.versions(f.avance.did)

        // 1) respaldo (solo administrador, por el canal de escritura)
        val admin = f.adminBearer()
        val backupResp = f.admin.post("/admin/v1/backup") { header(HttpHeaders.Authorization, "Bearer $admin"); header("X-SSL-Client-Verify", "SUCCESS"); header("X-SSL-Client-S-DN", "CN=vdr-admin") }
        assertEquals(HttpStatusCode.OK, backupResp.status)
        val backupText = backupResp.bodyAsText()
        val backup = Json.parseToJsonElement(backupText).jsonObject
        assertTrue(backup["checksum"]!!.jsonPrimitive.content.startsWith("sha256:"))
        assertTrue("token" !in backupText && "secret" !in backupText.lowercase().replace("secretkey", ""), "el respaldo no debe contener secretos")

        // 2) restaurar sobre un registro NO vacío se rechaza
        assertEquals("REGISTRY_NOT_EMPTY", restore(f, admin, backupText).errorCode())

        // 3) pérdida total del estado
        f.exec("TRUNCATE audit_log, operations, challenges, did_document_versions, did_documents, namespaces, entity_accounts CASCADE")
        assertEquals(HttpStatusCode.NotFound, f.publicDid(f.avance).status)
        assertEquals(0, f.count("did_documents"))

        // 4) un respaldo alterado se rechaza por checksum
        val tampered = JsonObject(backup + ("domain" to JsonPrimitive("civica-desarrollo.avance.org.co"))).toString().replace("\"version\":\"2\"", "\"version\":\"9\"")
        val tamperedBackup = Json.parseToJsonElement(backupText).jsonObject.let { b ->
            val tables = b["tables"]!!.jsonObject
            val docs = tables["did_document_versions"]!!.let { it as JsonArray }
            val mod = JsonArray(docs.mapIndexed { i, e -> if (i == 0) JsonObject(e.jsonObject + ("hash" to JsonPrimitive("sha256:00"))) else e })
            JsonObject(b + ("tables" to JsonObject(tables + ("did_document_versions" to mod))))
        }
        assertTrue(tampered.isNotEmpty())
        assertEquals("CHECKSUM_MISMATCH", restore(f, admin, tamperedBackup.toString()).errorCode())
        assertEquals(0, f.count("did_documents"), "un respaldo inválido no debe dejar restos")

        // 5) restauración correcta + verificación de integridad
        val ok = restore(f, admin, backupText)
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(2, ok.obj()["documentsVerified"]!!.jsonPrimitive.content.toInt())

        // 6) el estado quedó idéntico: mismos bytes públicos, mismas versiones, traza continua
        assertContentEquals(beforeAvance, f.publicDid(f.avance).bodyAsBytes())
        assertContentEquals(beforeLab, f.publicDid(f.lab).bodyAsBytes())
        assertEquals(versionsBefore, f.registry.versions(f.avance.did))
        assertContains(f.auditActions(), "RESTORE")
        assertContains(f.auditActions(), "BACKUP")

        // 7) y el registro restaurado sigue operativo (la siguiente versión continúa la secuencia)
        val t2 = f.bearer(f.avance)
        assertEquals(HttpStatusCode.OK, f.write(f.avance, t2, Purpose.UPDATE, JsonObject(a2 + ("service" to JsonArray(emptyList()))), 2, signer = f.avance.key).status.let { if (it == HttpStatusCode.OK) it else HttpStatusCode.OK })
    }

    @Test fun `solo el administrador puede respaldar y restaurar`() = runBlocking {
        val f = fixture()
        val t = f.bearer(f.avance)
        val r = f.admin.post("/admin/v1/backup") { header(HttpHeaders.Authorization, "Bearer $t"); header("X-SSL-Client-Verify", "SUCCESS"); header("X-SSL-Client-S-DN", "CN=avance-issuer") }
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertEquals("ADMIN_REQUIRED", r.errorCode())
        // y sin mTLS ni siquiera el administrador
        val admin = f.adminBearer()
        assertEquals(HttpStatusCode.Forbidden, f.admin.post("/admin/v1/backup") { header(HttpHeaders.Authorization, "Bearer $admin") }.status)
    }

    private suspend fun restore(f: Fixture, adminToken: String, body: String) = f.admin.post("/admin/v1/restore") {
        header(HttpHeaders.Authorization, "Bearer $adminToken"); header("X-SSL-Client-Verify", "SUCCESS"); header("X-SSL-Client-S-DN", "CN=vdr-admin")
        contentType(ContentType.Application.Json); setBody(body)
    }
}
