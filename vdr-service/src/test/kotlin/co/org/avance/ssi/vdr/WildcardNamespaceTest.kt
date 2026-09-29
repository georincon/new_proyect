package co.org.avance.ssi.vdr

import co.org.avance.ssi.didcore.DidDocumentBuilder
import co.org.avance.ssi.didcore.DidKeys
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

// Extensión para ERSo 2026-003: el Wallet Backend publica DID de titulares bajo un namespace comodín de un nivel (titulares + barra + asterisco).
class WildcardNamespaceTest {
    private var f: Fixture? = null
    @AfterTest fun cleanup() { f?.close() }

    private fun holder(id: String) = Entity("wallet-backend", "wallet-secret-123", "titulares/$id")

    @Test fun `el backend de cartera crea el DID de un titular sin reservar su namespace uno por uno`() = runBlocking {
        val f = Fixture.create().also { this@WildcardNamespaceTest.f = it }
        val t = f.bearer(holder("abc"), cn = "wallet-backend")
        val h = holder("Zk3-titular_1")
        val doc = h.document()
        val r = f.write(h, t, Purpose.CREATE, doc, 0, cn = "wallet-backend")
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        assertEquals(HttpStatusCode.OK, f.publicDid(h).status)
        // se materializó el namespace concreto y quedó a nombre del backend
        assertEquals(1, f.count("namespaces", "path = 'titulares/Zk3-titular_1' AND owner_client_id = 'wallet-backend'"))
        assertContains(f.auditActions(h.did), "WRITE_CREATE")
        // y se puede actualizar como cualquier otro DID
        assertEquals(HttpStatusCode.OK, f.write(h, t, Purpose.UPDATE, DidDocumentBuilder.build(h.did, DidKeys.multikey(h.key)), 1, cn = "wallet-backend").status)
    }

    @Test fun `el comodin cubre un solo nivel y no otorga permisos a otras cuentas`() = runBlocking {
        val f = Fixture.create().also { this@WildcardNamespaceTest.f = it }
        val t = f.bearer(f.avance)
        // otra entidad no puede escribir bajo titulares/*
        val h = Entity("avance-issuer", "avance-secret-123", "titulares/robado")
        val other = f.challenge(t, h.did, "CREATE")
        assertEquals(HttpStatusCode.PreconditionFailed, other.status)
        assertContains(other.details(), "NAMESPACE_NOT_OWNED")
        // dos niveles no están cubiertos
        val tw = f.bearer(Entity("wallet-backend", "wallet-secret-123", "titulares/x"), "wallet-backend")
        val deep = Entity("wallet-backend", "wallet-secret-123", "titulares/a/b")
        val r = f.challenge(tw, deep.did, "CREATE", cn = "wallet-backend")
        assertEquals(HttpStatusCode.PreconditionFailed, r.status)
        assertContains(r.details(), "NAMESPACE_NOT_RESERVED")
        // y el propio comodín no es un DID válido
        assertContains(listOf(400, 412), f.challenge(tw, "did:web:civica-desarrollo.avance.org.co:titulares:*", "CREATE", cn = "wallet-backend").status.value)
    }
}
