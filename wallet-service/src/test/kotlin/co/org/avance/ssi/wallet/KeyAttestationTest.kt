package co.org.avance.ssi.wallet

import co.org.avance.ssi.didcore.DidKeys
import co.org.avance.ssi.sim.LabAttestationAuthority
import co.org.avance.ssi.wallet.core.ProtectionLevel
import java.security.interfaces.ECPublicKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** ERSo 2026-001, criterio 3 — el backend deduce el nivel REAL leyendo la key attestation (no le cree al dispositivo). */
class KeyAttestationTest {
    private val authority = LabAttestationAuthority.create()
    private val verifier = KeyAttestationVerifier(setOf(authority.root))
    private val challenge = "nonce-emitido-por-el-backend".toByteArray()

    private fun chain(level: ProtectionLevel, origin: Int = LabAttestationAuthority.ORIGIN_GENERATED, boot: Int = LabAttestationAuthority.BOOT_VERIFIED, ch: ByteArray = challenge, a: LabAttestationAuthority = authority): Pair<List<ByteArray>, ECPublicKey> {
        val key = DidKeys.generateP256().public as ECPublicKey
        return a.issue(key, ch, level, origin, boot).map { it.encoded } to key
    }

    @Test fun `una cadena TEE legitima se verifica y entrega el nivel, el origen y la clave`() {
        val (c, key) = chain(ProtectionLevel.TEE)
        val r = verifier.verify(c, challenge)
        assertEquals(1, r.effectiveLevelCode)
        assertEquals(ProtectionLevel.TEE, ProtectionLevel.fromAttestationCode(r.effectiveLevelCode))
        assertTrue(r.generatedInSecureEnvironment)
        assertTrue(2 in r.purposes)
        assertEquals(0, r.verifiedBootState)
        assertEquals(true, r.deviceLocked)
        assertContentEquals(key.encoded, r.leafPublicKey.encoded)
        assertContentEquals(challenge, r.challenge)
    }

    @Test fun `StrongBox y Software se distinguen`() {
        assertEquals(2, verifier.verify(chain(ProtectionLevel.STRONGBOX).first, challenge).effectiveLevelCode)
        val sw = verifier.verify(chain(ProtectionLevel.SOFTWARE).first, challenge)
        assertEquals(0, sw.effectiveLevelCode)
        assertEquals(null, sw.origin, "en software no hay afirmaciones respaldadas por hardware")
    }

    @Test fun `un desafio distinto se rechaza (evita reutilizar una attestation vieja)`() {
        val (c, _) = chain(ProtectionLevel.TEE, ch = "otro".toByteArray())
        assertEquals("CHALLENGE_MISMATCH", assertFailsWith<AttestationException> { verifier.verify(c, challenge) }.code)
    }

    @Test fun `una cadena firmada por una raiz desconocida se rechaza`() {
        val (c, _) = chain(ProtectionLevel.STRONGBOX, a = LabAttestationAuthority.create("Falsa"))
        assertEquals("UNTRUSTED_CHAIN", assertFailsWith<AttestationException> { verifier.verify(c, challenge) }.code)
    }

    @Test fun `una clave importada queda visible en el origen`() {
        val r = verifier.verify(chain(ProtectionLevel.TEE, origin = LabAttestationAuthority.ORIGIN_IMPORTED).first, challenge)
        assertEquals(false, r.generatedInSecureEnvironment)
    }

    @Test fun `entradas invalidas se rechazan sin excepciones inesperadas`() {
        assertEquals("INVALID_CHAIN", assertFailsWith<AttestationException> { verifier.verify(emptyList(), challenge) }.code)
        assertEquals("INVALID_CHAIN", assertFailsWith<AttestationException> { verifier.verify(listOf(byteArrayOf(1, 2, 3)), challenge) }.code)
        assertEquals("INVALID_CHAIN", assertFailsWith<AttestationException> { verifier.verify(listOf(authority.root.encoded), challenge) }.code)
        assertEquals("NO_TRUST_ANCHORS", assertFailsWith<AttestationException> { KeyAttestationVerifier(emptySet()).verify(chain(ProtectionLevel.TEE).first, challenge) }.code)
    }

    @Test fun `guarda - todo metodo de prueba devuelve void para que JUnit lo ejecute`() {
        val bad = KeyAttestationTest::class.java.declaredMethods.filter { it.isAnnotationPresent(Test::class.java) && it.returnType != Void.TYPE }.map { it.name }
        assertTrue(bad.isEmpty(), "Estas pruebas NO se ejecutarían: $bad")
    }
}
