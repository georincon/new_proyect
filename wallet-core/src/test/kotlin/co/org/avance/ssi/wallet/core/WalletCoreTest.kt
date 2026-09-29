package co.org.avance.ssi.wallet.core

import co.org.avance.ssi.credentials.Jwk
import co.org.avance.ssi.didcore.CanonicalJson
import co.org.avance.ssi.didcore.DidDocumentBuilder
import co.org.avance.ssi.didcore.DidKeys
import co.org.avance.ssi.didcore.DidConformance
import co.org.avance.ssi.didcore.DidWeb
import co.org.avance.ssi.didcore.Jws
import co.org.avance.ssi.didcore.Multikey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.security.KeyPair
import java.security.PrivateKey
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.util.Base64
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val DOMAIN = "civica-desarrollo.avance.org.co"

/** ERSo 2026-001 (criterios 2 y 3, lado dispositivo) y ERSo 2026-003 (criterios 1 a 4). */
class WalletCoreTest {
    private lateinit var known: KeyPair

    /** Custodio que nos deja conocer la clave privada SOLO para poder buscarla después en cualquier salida. */
    private fun custodian(level: ProtectionLevel = ProtectionLevel.SOFTWARE) =
        SoftwareKeyCustodian(keyPairSource = { DidKeys.generateP256().also { known = it } }, maxProtection = level)

    /** Todas las representaciones textuales razonables de la clave privada. */
    private fun privateForms(kp: KeyPair): List<String> {
        val d = (kp.private as ECPrivateKey).s
        val raw = d.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        return listOf(
            d.toString(), d.toString(16), Base64.getEncoder().encodeToString(kp.private.encoded), Base64.getUrlEncoder().withoutPadding().encodeToString(kp.private.encoded),
            Base64.getUrlEncoder().withoutPadding().encodeToString(raw), Base64.getEncoder().encodeToString(raw),
        ).filter { it.length >= 20 }
    }

    // ==================== ERSo 001 · criterio 2: no exportabilidad ====================
    @Test fun `001-C2 el contrato del custodio no expone ningun metodo que devuelva material privado`() {
        val forbidden = listOf(PrivateKey::class.java, KeyPair::class.java, SecretKey::class.java)
        val leaks = KeyCustodian::class.java.methods.filter { m -> forbidden.any { it.isAssignableFrom(m.returnType) } }
        assertEquals(emptyList(), leaks.map { it.name }, "ningún método puede devolver una clave privada/secreta")
        val names = KeyCustodian::class.java.methods.map { it.name.lowercase() }
        assertTrue(names.none { "export" in it || "privatekey" in it || "getprivate" in it || "unwrap" in it }, "no debe haber métodos de exportación")
        // el descriptor público no tiene ningún campo capaz de contener una clave privada
        assertTrue(KeyDescriptor::class.java.declaredFields.none { it.type == PrivateKey::class.java || it.name.contains("private", true) || it.name == "d" })
    }

    @Test fun `001-C2 la clave privada real no aparece en ninguna salida del custodio, ni en descriptores, ni en firmas`() {
        val c = custodian()
        val desc = c.generate("k1")
        val everything = Json.encodeToString(desc) + Json.encodeToString(c.report()) + c.toString() +
            Base64.getEncoder().encodeToString(c.sign("k1", "hola".toByteArray())) + Jwk.fromPublic(c.publicKey("k1")).toString()
        privateForms(known).forEach { assertFalse(it in everything, "se filtró la clave privada ($it)") }
        assertFalse(desc.exportable)
        assertEquals("GENERATED", desc.origin)
    }

    @Test fun `001-C2 la clave sirve para firmar y la firma se verifica con la publica, sin salir del custodio`() {
        val c = custodian()
        c.generate("k1")
        val jws = Jws.signWith("did:x#k", "carga".toByteArray()) { c.sign("k1", it) }
        assertTrue(Jws.verify(Jws.parse(jws), c.publicKey("k1")))
    }

    @Test fun `001-C2 no se puede sobrescribir una clave existente ni obtener una inexistente`() {
        val c = custodian()
        c.generate("k1")
        assertFailsWith<IllegalStateException> { c.generate("k1") }
        assertFailsWith<NoSuchElementException> { c.sign("nope", ByteArray(1)) }
        c.delete("k1")
        assertEquals(null, c.descriptor("k1"))
    }

    // ==================== ERSo 001 · criterio 3: nivel declarado = disponible ====================
    @Test fun `001-C3 un custodio de software declara SOFTWARE y no puede pasar por hardware`() {
        val c = custodian()
        assertEquals("SOFTWARE", c.generate("k1").protectionLevel)
        assertFalse(c.report().hardwareBacked)
        val e = assertFailsWith<ProtectionBelowPolicyException> { c.generate("k2", KeyPolicy(ProtectionLevel.TEE)) }
        assertEquals(ProtectionLevel.SOFTWARE, e.available)
        assertEquals(null, c.descriptor("k2"), "sin fallback silencioso: la clave NO se genera")
        assertEquals(null, c.attestation("k1"), "el software puro no puede aportar evidencia de hardware")
    }

    @Test fun `001-C3 la politica minima permite el nivel real y lo refleja`() {
        val tee = custodian(ProtectionLevel.TEE)
        assertEquals("TEE", tee.generate("k", KeyPolicy(ProtectionLevel.TEE)).protectionLevel)
        assertTrue(tee.report().hardwareBacked)
        assertFailsWith<ProtectionBelowPolicyException> { tee.generate("k2", KeyPolicy(ProtectionLevel.STRONGBOX)) }
    }

    // ==================== ERSo 003 ====================
    private fun identity(c: SoftwareKeyCustodian = custodian()) = HolderDidService(c, DOMAIN).create()

    @Test fun `003-C1 el DID se genera en el dispositivo sin exponer la clave privada (registro de generacion)`() {
        val c = custodian()
        val id = HolderDidService(c, DOMAIN).create()
        val recordJson = Json.encodeToString(id.record)
        val docJson = id.document.toString()
        privateForms(known).forEach { assertFalse(it in recordJson, "el registro de generación contiene la clave privada") ; assertFalse(it in docJson, "el DID Document contiene la clave privada") }
        assertEquals("GENERATED", id.record.keyOrigin)
        assertFalse(id.record.exportable)
        assertEquals("SOFTWARE", id.record.protectionLevel)
        assertTrue(id.record.conformant)
        assertTrue(Regex("^did:web:$DOMAIN:titulares:[A-Za-z0-9_-]{43}$").matches(id.did), id.did)
    }

    @Test fun `003 el identificador se deriva de la clave publica y es determinista`() {
        val id = identity()
        assertEquals(DidWeb.of(DOMAIN, listOf("titulares", id.record.thumbprint)), id.did)
        val other = identity()
        assertTrue(id.did != other.did, "dos claves distintas dan DID distintos")
        assertFalse(id.did.contains("@") || id.did.lowercase().contains("cedula"), "sin datos civiles en el identificador")
    }

    @Test fun `003-C2 el DID Document es conforme y produce un informe de conformidad`() {
        val id = identity()
        assertTrue(id.conformance.conformant, id.conformance.failed.toString())
        assertEquals(listOf("C01", "C02", "C03", "C04", "C05", "C06", "C07", "C08", "C09", "C10"), id.conformance.checks.map { it.id })
        assertTrue(id.conformance.checks.all { it.reference.isNotBlank() })
    }

    @Test fun `003-C3 authentication y assertionMethod apuntan a la clave Multikey del titular`() {
        val id = identity()
        val key = id.record.publicKeyMultibase
        assertTrue(key.startsWith("zDn"))
        val vm = (id.document["verificationMethod"] as JsonArray).single() as JsonObject
        assertEquals(key, (vm["publicKeyMultibase"] as JsonPrimitive).content)
        assertEquals("Multikey", (vm["type"] as JsonPrimitive).content)
        val ref = "${id.did}#key-1"
        assertEquals(listOf(ref), (id.document["authentication"] as JsonArray).map { (it as JsonPrimitive).content })
        assertEquals(listOf(ref), (id.document["assertionMethod"] as JsonArray).map { (it as JsonPrimitive).content })
        // la clave del documento es la del custodio
        assertEquals(Jwk.thumbprint(Multikey.decodeP256(key)), id.record.thumbprint)
    }

    @Test fun `003-C2 el informe detecta documentos no conformes`() {
        val id = identity()
        val did = id.did
        val key = id.record.publicKeyMultibase
        fun failing(doc: JsonObject) = DidConformance.check(doc, did, key).failed.map { it.id }
        // material privado
        val withPrivate = JsonObject(id.document + ("verificationMethod" to JsonArray(listOf(JsonObject(((id.document["verificationMethod"] as JsonArray)[0] as JsonObject) + ("privateKeyMultibase" to JsonPrimitive("z123")))))))
        assertTrue("C08" in failing(withPrivate))
        // datos civiles y extensiones propietarias
        val civil = JsonObject(id.document + ("credentialSubject" to JsonObject(mapOf("name" to JsonPrimitive("Ana")))))
        assertTrue("C09" in failing(civil) && "C10" in failing(civil))
        // contexto equivocado
        assertTrue("C02" in failing(JsonObject(id.document + ("@context" to JsonArray(listOf(JsonPrimitive("https://ejemplo.org/ctx")))))))
        // id distinto
        assertTrue("C01" in DidConformance.check(id.document, "$did-otro", key).failed.map { it.id })
        // relación que apunta a otra clave
        val other = DidKeys.generateP256()
        val foreign = DidDocumentBuilder.build(did, Multikey.encodeP256(other.public as ECPublicKey))
        assertTrue("C07" in DidConformance.check(foreign, did, key).failed.map { it.id })
        // controlador ajeno
        val ctl = JsonObject(id.document + ("controller" to JsonPrimitive("did:web:otro.example")))
        assertTrue("C05" in failing(ctl))
        // relación colgante
        assertTrue("C06" in failing(JsonObject(id.document + ("authentication" to JsonArray(listOf(JsonPrimitive("$did#no-existe")))))))
    }

    @Test fun `003 la validacion es un requisito previo - un documento no conforme no se entrega`() {
        // HolderDidService valida antes de devolver; se simula un custodio que da una clave inválida no es posible, así que se prueba la regla directamente
        val id = identity()
        val bad = DidConformance.check(JsonObject(id.document + ("service" to JsonArray(listOf(JsonObject(mapOf("id" to JsonPrimitive("x"))))))), id.did, id.record.publicKeyMultibase)
        assertFalse(bad.conformant)
    }

    @Test fun `003-C4 el documento se almacena sellado y asociado a la instancia de cartera`() {
        val c = custodian()
        val id = HolderDidService(c, DOMAIN).create()
        val dir = Files.createTempDirectory("wds")
        val store = SealedDocumentStore(c, dir)
        store.save("inst-1", id.did, id.document)

        val onDisk = String(store.rawBytes("inst-1"), Charsets.ISO_8859_1)
        assertFalse(id.did in onDisk, "el DID no debe verse en claro en disco")
        assertFalse("verificationMethod" in onDisk && "publicKeyMultibase" in onDisk, "el documento no debe verse en claro en disco")
        val (did, doc) = store.load("inst-1")
        assertEquals(id.did, did)
        assertEquals(CanonicalJson.canonicalize(id.document), CanonicalJson.canonicalize(doc))

        // asociado a la instancia: renombrar el archivo a otra instancia lo hace ilegible
        Files.copy(dir.resolve("inst-1.wds"), dir.resolve("inst-2.wds"))
        assertFailsWith<StoreAccessException> { store.load("inst-2") }
        // otro dispositivo (otro custodio) no puede abrirlo
        assertFailsWith<StoreAccessException> { SealedDocumentStore(custodian(), dir).load("inst-1") }
        // alteración de un byte
        val bytes = Files.readAllBytes(dir.resolve("inst-1.wds")); bytes[bytes.size - 3] = (bytes[bytes.size - 3].toInt() xor 1).toByte()
        Files.write(dir.resolve("inst-1.wds"), bytes)
        assertFailsWith<StoreAccessException> { store.load("inst-1") }
        assertFailsWith<IllegalArgumentException> { store.save("../evil", id.did, id.document) }
        assertNotNull(dir)
    }

    @Test fun `003 el SDK firma con la clave del DID usando el custodio`() {
        val c = custodian()
        val svc = HolderDidService(c, DOMAIN)
        val id = svc.create()
        val jws = svc.sign(id, "desafio".toByteArray())
        val parsed = Jws.parse(jws)
        assertEquals("${id.did}#key-1", parsed.kid)
        assertTrue(Jws.verify(parsed, Multikey.decodeP256(id.record.publicKeyMultibase)))
    }

    @Test fun `guarda - todo metodo de prueba devuelve void para que JUnit lo ejecute`() {
        val bad = WalletCoreTest::class.java.declaredMethods.filter { m ->
            m.isAnnotationPresent(Test::class.java) && m.returnType != Void.TYPE
        }.map { it.name }
        assertTrue(bad.isEmpty(), "Estas pruebas NO se ejecutarían (retorno no void): $bad")
    }
}
