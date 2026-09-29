package co.org.avance.ssi.didcore

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DidCoreTest {
    private val did = "did:web:civica-desarrollo.avance.org.co:entidades:avance"

    @Test fun `base58 round trip incluye ceros iniciales`() {
        val data = byteArrayOf(0, 0, 1, 2, 3, 127, -1)
        assertContentEquals(data, Base58.decode(Base58.encode(data)))
        assertEquals("", Base58.encode(ByteArray(0)))
        assertEquals("2NEpo7TZRRrLZSi2U", Base58.encode("Hello World!".toByteArray())) // vector conocido
    }

    @Test fun `multikey P-256 empieza por zDn y hace round trip`() {
        repeat(20) {
            val pair = DidKeys.generateP256()
            val mk = DidKeys.multikey(pair)
            assertTrue(mk.startsWith("zDn"), mk)
            val decoded = Multikey.decodeP256(mk)
            assertEquals((pair.public as java.security.interfaces.ECPublicKey).w, decoded.w)
        }
    }

    @Test fun `multikey rechaza claves ajenas o corruptas`() {
        assertFailsWith<InvalidKeyException> { Multikey.decodeP256("uAAAA") }
        assertFailsWith<InvalidKeyException> { Multikey.decodeP256("z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK") } // Ed25519
        val mk = DidKeys.multikey(DidKeys.generateP256())
        val corrupted = mk.dropLast(1) + (if (mk.last() == 'a') 'b' else 'a')
        // una alteración casi siempre deja un punto fuera de la curva o un largo distinto
        runCatching { Multikey.decodeP256(corrupted) }
    }

    @Test fun `did web se convierte a URL segun el metodo`() {
        assertEquals("https://w3c-ccg.github.io/.well-known/did.json", DidWeb.url("did:web:w3c-ccg.github.io"))
        assertEquals("https://w3c-ccg.github.io/user/alice/did.json", DidWeb.url("did:web:w3c-ccg.github.io:user:alice"))
        assertEquals("https://example.com:3000/user/alice/did.json", DidWeb.url("did:web:example.com%3A3000:user:alice"))
        assertEquals("https://civica-desarrollo.avance.org.co/entidades/avance/did.json", DidWeb.url(did))
        assertFailsWith<InvalidDidException> { DidWeb.parse("did:key:zDn...") }
        assertFailsWith<InvalidDidException> { DidWeb.parse("did:web:host:..:x") }
        assertFailsWith<InvalidDidException> { DidWeb.parse("did:web:") }
    }

    @Test fun `ruta a DID es el camino inverso`() {
        assertEquals(did, DidWeb.fromUrlPath("civica-desarrollo.avance.org.co", listOf("entidades", "avance", "did.json")))
        assertEquals("did:web:h.co", DidWeb.fromUrlPath("h.co", listOf(".well-known", "did.json")))
        assertEquals(null, DidWeb.fromUrlPath("h.co", listOf("entidades", "avance", "otro.json")))
    }

    @Test fun `canonico es determinista y el hash cambia con el contenido`() {
        val a = Json.parseToJsonElement("""{"b":1,"a":[{"z":true,"y":null}]}""")
        val b = Json.parseToJsonElement("""{ "a": [ {"y":null, "z":true} ], "b": 1 }""")
        assertEquals("""{"a":[{"y":null,"z":true}],"b":1}""", CanonicalJson.canonicalize(a))
        assertEquals(CanonicalJson.hash(a), CanonicalJson.hash(b))
        assertTrue(CanonicalJson.hash(a).startsWith("sha256:"))
        assertFalse(CanonicalJson.hash(a) == CanonicalJson.hash(Json.parseToJsonElement("""{"a":[],"b":1}""")))
    }

    @Test fun `jws firma y verifica, rechaza clave ajena y manipulacion`() {
        val k1 = DidKeys.generateP256()
        val k2 = DidKeys.generateP256()
        val jws = Jws.sign(k1.private, "$did#key-1", "hola".toByteArray())
        val parsed = Jws.parse(jws)
        assertEquals("$did#key-1", parsed.kid)
        assertTrue(Jws.verify(parsed, k1.public as java.security.interfaces.ECPublicKey))
        assertFalse(Jws.verify(parsed, k2.public as java.security.interfaces.ECPublicKey))
        val tampered = jws.split(".").let { "${it[0]}.${java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("adios".toByteArray())}.${it[2]}" }
        assertFalse(Jws.verify(Jws.parse(tampered), k1.public as java.security.interfaces.ECPublicKey))
        assertFailsWith<JwsException> { Jws.parse("a.b") }
    }

    @Test fun `jws rechaza alg none`() {
        val enc = java.util.Base64.getUrlEncoder().withoutPadding()
        val h = enc.encodeToString("""{"alg":"none","kid":"x"}""".toByteArray())
        assertFailsWith<JwsException> { Jws.parse("$h.${enc.encodeToString("p".toByteArray())}.${enc.encodeToString(ByteArray(64))}") }
    }

    private fun validDoc(): JsonObject = DidDocumentBuilder.build(did, DidKeys.multikey(DidKeys.generateP256()))

    @Test fun `documento construido es valido en ambos perfiles`() {
        val doc = validDoc()
        assertEquals(emptyList(), DidDocumentValidator.validate(doc, did, Profile.PUBLISHER))
        assertEquals(emptyList(), DidDocumentValidator.validate(doc, did, Profile.CONSUMER))
    }

    @Test fun `id desajustado se rechaza`() {
        val codes = DidDocumentValidator.validate(validDoc(), "$did-otro", Profile.PUBLISHER).map { it.code }
        assertTrue("ID_MISMATCH" in codes)
    }

    @Test fun `clave privada se detecta en cualquier profundidad`() {
        val doc = JsonObject(validDoc() + ("verificationMethod" to JsonArray(listOf(buildJsonObject {
            put("id", JsonPrimitive("$did#key-1")); put("type", JsonPrimitive("Multikey")); put("controller", JsonPrimitive(did))
            put("publicKeyMultibase", JsonPrimitive(DidKeys.multikey(DidKeys.generateP256())))
            put("privateKeyJwk", buildJsonObject { put("kty", JsonPrimitive("EC")); put("d", JsonPrimitive("secreto")) })
        }))))
        val codes = DidDocumentValidator.validate(doc, did, Profile.CONSUMER).map { it.code }
        assertTrue("PRIVATE_KEY_MATERIAL" in codes)
    }

    @Test fun `datos civiles y propiedades desconocidas se rechazan al publicar pero no bloquean al consumidor`() {
        val doc = JsonObject(validDoc() + ("credentialSubject" to buildJsonObject { put("name", JsonPrimitive("Ana")) }))
        val pub = DidDocumentValidator.validate(doc, did, Profile.PUBLISHER).map { it.code }
        assertTrue("CIVIL_DATA" in pub && "UNKNOWN_PROPERTY" in pub, pub.toString())
        assertEquals(emptyList(), DidDocumentValidator.validate(doc, did, Profile.CONSUMER))
    }

    @Test fun `referencias colgantes, tipo de clave y controlador ajeno`() {
        val base = validDoc()
        val dangling = JsonObject(base + ("assertionMethod" to JsonArray(listOf(JsonPrimitive("$did#nope")))))
        assertTrue("DANGLING_REFERENCE" in DidDocumentValidator.validate(dangling, did, Profile.CONSUMER).map { it.code })
        val foreign = JsonObject(base + ("controller" to JsonPrimitive("did:web:otro.co")))
        assertTrue("UNAUTHORIZED_CONTROLLER" in DidDocumentValidator.validate(foreign, did, Profile.PUBLISHER).map { it.code })
        assertEquals(emptyList(), DidDocumentValidator.validate(foreign, did, Profile.PUBLISHER, setOf("did:web:otro.co")).map { it.code })
        val jwk = JsonObject(base + ("verificationMethod" to JsonArray(listOf(buildJsonObject {
            put("id", JsonPrimitive("$did#key-1")); put("type", JsonPrimitive("JsonWebKey2020")); put("controller", JsonPrimitive(did))
        }))))
        assertTrue("UNSUPPORTED_KEY_TYPE" in DidDocumentValidator.validate(jwk, did, Profile.CONSUMER).map { it.code })
    }

    @Test fun `service http se rechaza al publicar`() {
        val doc = DidDocumentBuilder.build(did, DidKeys.multikey(DidKeys.generateP256()), services = listOf(Triple("issuer", "OID4VCI", "http://x.co")))
        assertTrue("INVALID_SERVICE" in DidDocumentValidator.validate(doc, did, Profile.PUBLISHER).map { it.code })
    }
}
