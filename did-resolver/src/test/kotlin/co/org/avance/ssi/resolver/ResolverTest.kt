package co.org.avance.ssi.resolver

import co.org.avance.ssi.didcore.CanonicalJson
import co.org.avance.ssi.didcore.DidDocumentBuilder
import co.org.avance.ssi.didcore.DidKeys
import co.org.avance.ssi.didcore.Jws
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Headers
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.KeyPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResolverTest {
    private val did = "did:web:civica-desarrollo.avance.org.co:entidades:avance"
    private val url = "https://civica-desarrollo.avance.org.co/entidades/avance/did.json"
    private val keys: KeyPair = DidKeys.generateP256()
    private val doc: JsonObject = DidDocumentBuilder.build(did, DidKeys.multikey(keys))
    private val methods = mutableListOf<HttpMethod>()
    private val urls = mutableListOf<String>()

    private fun resolverServing(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = CanonicalJson.canonicalize(doc),
        contentType: String = "application/did+json",
    ) = DidWebResolver(HttpClient(MockEngine { req ->
        methods += req.method; urls += req.url.toString()
        if (status == HttpStatusCode.OK) respond(body, status, Headers.build { append(HttpHeaders.ContentType, contentType); append(HttpHeaders.ETag, "\"3\"") })
        else respondError(status, body, headersOf(HttpHeaders.ContentType, contentType))
    }) { followRedirects = false })

    private fun sign(k: KeyPair = keys, kid: String = "$did#key-1") = Jws.sign(k.private, kid, "reto".toByteArray())

    @Test fun `resuelve un did web por https y devuelve metadatos`() = runBlocking {
        val r = resolverServing().resolve(did)
        assertTrue(r.isSuccess)
        assertEquals(url, urls.single())
        assertEquals("3", r.documentMetadata.versionId)
        assertEquals(CanonicalJson.hash(doc), r.documentMetadata.contentHash)
    }

    @Test fun `CRITERIO 1 - prueba firmada por la clave resuelta se valida`() = runBlocking {
        val v = ProofVerifier(resolverServing()).verify(sign(), VerificationRelationship.AUTHENTICATION, expectedDid = did)
        assertIs<VerificationResult.Valid>(v)
        assertEquals("reto", String(v.payload))
        assertIs<VerificationResult.Valid>(ProofVerifier(resolverServing()).verify(sign(), VerificationRelationship.ASSERTION_METHOD))
        Unit
    }

    @Test fun `CRITERIO 2 - prueba firmada por clave ajena se rechaza`() = runBlocking {
        val intruso = DidKeys.generateP256()
        val v = ProofVerifier(resolverServing()).verify(sign(intruso), VerificationRelationship.AUTHENTICATION)
        assertEquals("INVALID_SIGNATURE", (v as VerificationResult.Invalid).code)
    }

    @Test fun `clave que no existe o no esta autorizada para la relacion se rechaza`() = runBlocking {
        val noExiste = ProofVerifier(resolverServing()).verify(sign(kid = "$did#key-9"), VerificationRelationship.AUTHENTICATION)
        assertEquals("KEY_NOT_FOUND", (noExiste as VerificationResult.Invalid).code)
        val soloAuth = JsonObject(doc + ("assertionMethod" to JsonArray(emptyList())))
        val v = ProofVerifier(resolverServing(body = CanonicalJson.canonicalize(soloAuth))).verify(sign(), VerificationRelationship.ASSERTION_METHOD)
        assertEquals("KEY_NOT_AUTHORIZED", (v as VerificationResult.Invalid).code)
    }

    @Test fun `CRITERIO 3 - documento con id desajustado se rechaza`() = runBlocking {
        val otro = JsonObject(doc + ("id" to JsonPrimitive("$did-falso")))
        val r = resolverServing(body = CanonicalJson.canonicalize(otro)).resolve(did)
        assertNull(r.didDocument)
        assertEquals("invalidDidDocument", r.resolutionMetadata.error)
        assertTrue("ID_MISMATCH" in r.resolutionMetadata.violations)
        assertIs<VerificationResult.Invalid>(ProofVerifier(resolverServing(body = CanonicalJson.canonicalize(otro))).verify(sign(), VerificationRelationship.AUTHENTICATION))
        Unit
    }

    @Test fun `CRITERIO 3 - did inexistente, invalido o desactivado se rechaza`() = runBlocking {
        assertEquals("notFound", resolverServing(HttpStatusCode.NotFound, "{}").resolve(did).resolutionMetadata.error)
        assertEquals("invalidDid", resolverServing().resolve("did:key:zDn").resolutionMetadata.error)
        val gone = resolverServing(HttpStatusCode.Gone, "{}").resolve(did)
        assertTrue(gone.documentMetadata.deactivated)
        val v = ProofVerifier(resolverServing(HttpStatusCode.Gone, "{}")).verify(sign(), VerificationRelationship.AUTHENTICATION)
        assertEquals("DEACTIVATED", (v as VerificationResult.Invalid).code)
    }

    @Test fun `no sigue redirecciones ni acepta content-type ajeno ni documentos gigantes`() = runBlocking {
        assertEquals("internalError", resolverServing(HttpStatusCode.Found, "").resolve(did).resolutionMetadata.error)
        assertEquals("representationNotSupported", resolverServing(contentType = "text/html").resolve(did).resolutionMetadata.error)
        val big = DidWebResolver(HttpClient(MockEngine { respond("x".repeat(200_000), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }))
        assertEquals("invalidDidDocumentLength", big.resolve(did).resolutionMetadata.error)
    }

    @Test fun `dereferenciar devuelve el metodo de verificacion concreto`() = runBlocking {
        val r = resolverServing().dereference("$did#key-1")
        assertNotNull(r.contentStream)
        assertEquals("Multikey", (r.contentStream!!["type"] as JsonPrimitive).content)
        assertEquals("notFound", resolverServing().dereference("$did#nada").error)
    }

    @Test fun `pruebas malformadas se rechazan`() = runBlocking {
        val v = ProofVerifier(resolverServing()).verify("no-es-jws", VerificationRelationship.AUTHENTICATION)
        assertEquals("MALFORMED_PROOF", (v as VerificationResult.Invalid).code)
        val otroDid = ProofVerifier(resolverServing()).verify(sign(kid = "did:web:otro.co#k"), VerificationRelationship.AUTHENTICATION, expectedDid = did)
        assertEquals("DID_MISMATCH", (otroDid as VerificationResult.Invalid).code)
    }

    @Test fun `CRITERIO 4 - el consumidor solo emite GET`() = runBlocking {
        val r = resolverServing()
        ProofVerifier(r).verify(sign(), VerificationRelationship.AUTHENTICATION)
        r.resolve(did); r.dereference("$did#key-1")
        assertTrue(methods.isNotEmpty())
        assertTrue(methods.all { it == HttpMethod.Get }, methods.toString())
    }

    @Test fun `CRITERIO 4 - la interfaz publica no expone operaciones de escritura`() {
        val forbidden = Regex("^(put|post|patch|delete|publish|write|create|register|update|deactivate|save|store|upload|send)", RegexOption.IGNORE_CASE)
        val api = listOf(DidResolver::class.java, DidWebResolver::class.java, ProofVerifier::class.java)
        for (cls in api) {
            val offending = cls.declaredMethods.filter { java.lang.reflect.Modifier.isPublic(it.modifiers) && forbidden.containsMatchIn(it.name) }
            assertTrue(offending.isEmpty(), "${cls.simpleName} expone: ${offending.map { it.name }}")
        }
        assertFalse(DidResolver::class.java.methods.any { it.name.contains("publish", true) })
    }

    @Test fun `guarda - todo metodo de prueba devuelve void para que JUnit lo ejecute`() {
        val bad = ResolverTest::class.java.declaredMethods.filter { m ->
            m.isAnnotationPresent(Test::class.java) && m.returnType != Void.TYPE
        }.map { it.name }
        assertTrue(bad.isEmpty(), "Estas pruebas NO se ejecutarían (retorno no void): $bad")
    }
}
