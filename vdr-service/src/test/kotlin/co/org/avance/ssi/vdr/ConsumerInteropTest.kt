package co.org.avance.ssi.vdr

import co.org.avance.ssi.didcore.DidKeys
import co.org.avance.ssi.didcore.Jws
import co.org.avance.ssi.resolver.DidWebResolver
import co.org.avance.ssi.resolver.ProofVerifier
import co.org.avance.ssi.resolver.VerificationRelationship
import co.org.avance.ssi.resolver.VerificationResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Interoperabilidad productor -> consumidor: lo que publica el VDR (ERSo 004/005/006/008) lo consume el cliente de
 * resolución de solo lectura (ERSo 007) sin ninguna adaptación.
 */
class ConsumerInteropTest {
    private var f: Fixture? = null
    @AfterTest fun cleanup() { f?.close() }

    private fun resolver(f: Fixture) = DidWebResolver(HttpClient(MockEngine { req ->
        val r = f.public.get(req.url.encodedPath)
        respond(r.bodyAsBytes(), r.status, r.headers)
    }))

    @Test fun `el consumidor resuelve y verifica lo publicado, rechaza clave ajena y respeta rotacion y desactivacion`() = runBlocking {
        val f = Fixture.create().also { f = it }
        val t = f.bearer(f.avance)
        val e = f.avance
        f.write(e, t, Purpose.CREATE, e.document(), 0)
        val verifier = ProofVerifier(resolver(f))
        val kid = "${e.did}#key-1"

        val proof = Jws.sign(e.key.private, kid, "presentacion".toByteArray())
        assertIs<VerificationResult.Valid>(verifier.verify(proof, VerificationRelationship.ASSERTION_METHOD, e.did))
        assertEquals("INVALID_SIGNATURE", (verifier.verify(Jws.sign(DidKeys.generateP256().private, kid, "x".toByteArray()), VerificationRelationship.ASSERTION_METHOD) as VerificationResult.Invalid).code)

        // rotación: la prueba de la clave anterior deja de ser válida
        val nueva = DidKeys.generateP256()
        f.write(e, t, Purpose.UPDATE, e.document(nueva), 1)
        assertEquals("INVALID_SIGNATURE", (verifier.verify(proof, VerificationRelationship.ASSERTION_METHOD) as VerificationResult.Invalid).code)
        assertIs<VerificationResult.Valid>(verifier.verify(Jws.sign(nueva.private, kid, "x".toByteArray()), VerificationRelationship.AUTHENTICATION))

        // desactivación
        f.write(e, t, Purpose.DEACTIVATE, null, 2, signer = nueva)
        assertEquals("DEACTIVATED", (verifier.verify(Jws.sign(nueva.private, kid, "x".toByteArray()), VerificationRelationship.AUTHENTICATION) as VerificationResult.Invalid).code)
        // un DID que nunca existió
        assertEquals("RESOLUTION_FAILED", (verifier.verify(Jws.sign(nueva.private, "did:web:civica-desarrollo.avance.org.co:entidades:fantasma#key-1", "x".toByteArray()), VerificationRelationship.AUTHENTICATION) as VerificationResult.Invalid).code)
    }
}
