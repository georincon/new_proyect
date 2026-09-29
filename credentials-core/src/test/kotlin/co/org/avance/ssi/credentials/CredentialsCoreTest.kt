package co.org.avance.ssi.credentials

import co.org.avance.ssi.didcore.DidKeys
import co.org.avance.ssi.didcore.Jws
import com.upokecenter.cbor.CBORObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.security.KeyPair
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val ISSUER = "did:web:civica-desarrollo.avance.org.co:entidades:avance"
private const val KID = "$ISSUER#key-1"

private fun signer(kp: KeyPair): (ByteArray) -> ByteArray = { data ->
    Signature.getInstance("SHA256withECDSAinP1363Format").apply { initSign(kp.private); update(data) }.sign()
}

/** ERSo 2026-002 — formatos dc+sd-jwt y mso_mdoc, perfiles y separación de claves. */
class CredentialsCoreTest {
    private val issuerKp = DidKeys.generateP256()
    private val holderKp = DidKeys.generateP256()
    private val resolver = IssuerKeyResolver { kid -> if (kid == KID) issuerKp.public as ECPublicKey else error("clave desconocida $kid") }
    private val claims = buildJsonObject { put("given_name", JsonPrimitive("Ana")); put("program", JsonPrimitive("Ingeniería")); put("gpa", JsonPrimitive(4.5)) }
    private val issuer = SdJwtVcIssuer(ISSUER, KID, signer(issuerKp))
    private fun issue() = issuer.issue("urn:eudi:academic:credential:1", claims, holderKp.public as ECPublicKey)
    private fun present(sd: String, reveal: Set<String>, aud: String = "https://verifier.example", nonce: String = "n-1") =
        SdJwtVcHolder.present(sd, reveal, aud, nonce, signer = signer(holderKp))

    // ---------------- SD-JWT VC ----------------
    @Test fun `sd-jwt - emision, divulgacion selectiva y presentacion validas`() = runBlocking {
        val log = ExecutionLog()
        val v = SdJwtVcVerifier(resolver, log = log)
        val sd = issue()
        assertTrue(sd.endsWith("~"))
        assertEquals(setOf("given_name", "program", "gpa"), v.verifyIssuance(sd).claims.keys.filter { it in setOf("given_name", "program", "gpa") }.toSet())
        val pres = present(sd, setOf("program"))
        val ok = v.verifyPresentation(pres, "https://verifier.example", "n-1")
        assertEquals("Ingeniería", (ok.claims["program"] as JsonPrimitive).content)
        assertTrue(ok.claims["given_name"] == null && ok.claims["gpa"] == null, "solo debe verse lo que el titular reveló")
        assertTrue(ok.holderBound)
        assertEquals(ISSUER, ok.issuer)
        assertEquals(emptyList(), log.mismatches(), "lo usado coincide con el perfil declarado")
        assertEquals("dc+sd-jwt", ok.header["typ"]!!.let { (it as JsonPrimitive).content })
    }

    @Test fun `sd-jwt - una divulgacion alterada o ajena se rechaza`() = runBlocking {
        val v = SdJwtVcVerifier(resolver)
        val sd = issue()
        val parts = SdJwtParts.parse(sd)
        val forged = Disclosure.create("program", JsonPrimitive("Medicina"), java.security.SecureRandom()).encoded
        val tampered = parts.jwt + "~" + forged + "~"
        assertEquals("UNMATCHED_DISCLOSURE", assertFailsWith<CredentialException> { v.verifyIssuance(tampered) }.code)
        val dup = parts.jwt + "~" + parts.disclosures[0] + "~" + parts.disclosures[0] + "~"
        assertEquals("DUPLICATE_DISCLOSURE", assertFailsWith<CredentialException> { v.verifyIssuance(dup) }.code)
    }

    @Test fun `sd-jwt - la firma del emisor no se puede reemplazar por otra clave`() = runBlocking {
        val evil = DidKeys.generateP256()
        val forged = SdJwtVcIssuer(ISSUER, KID, signer(evil)).issue("urn:x", claims, holderKp.public as ECPublicKey)
        assertEquals("INVALID_ISSUER_SIGNATURE", assertFailsWith<CredentialException> { SdJwtVcVerifier(resolver).verifyIssuance(forged) }.code)
        // kid de otro DID pero iss del emisor legítimo
        val otherKid = SdJwtVcIssuer(ISSUER, "did:web:otro#key-1", signer(issuerKp)).issue("urn:x", claims, holderKp.public as ECPublicKey)
        assertEquals("ISSUER_KEY_MISMATCH", assertFailsWith<CredentialException> { SdJwtVcVerifier(resolver).verifyIssuance(otherKid) }.code)
    }

    @Test fun `sd-jwt - prueba del titular (KB-JWT) - clave ajena, audiencia, nonce y repeticion`() = runBlocking {
        val v = SdJwtVcVerifier(resolver)
        val sd = issue()
        // CRITERIO 4: el emisor NO puede fabricar la prueba del titular, ni un tercero
        val impostor = DidKeys.generateP256()
        val byImpostor = SdJwtVcHolder.present(sd, setOf("program"), "https://verifier.example", "n-1", signer = signer(impostor))
        assertEquals("INVALID_HOLDER_PROOF", assertFailsWith<CredentialException> { v.verifyPresentation(byImpostor, "https://verifier.example", "n-1") }.code)
        val byIssuer = SdJwtVcHolder.present(sd, setOf("program"), "https://verifier.example", "n-1", signer = signer(issuerKp))
        assertEquals("INVALID_HOLDER_PROOF", assertFailsWith<CredentialException> { v.verifyPresentation(byIssuer, "https://verifier.example", "n-1") }.code)

        val pres = present(sd, setOf("program"))
        assertEquals("AUDIENCE_MISMATCH", assertFailsWith<CredentialException> { v.verifyPresentation(pres, "https://otro.example", "n-1") }.code)
        assertEquals("NONCE_MISMATCH", assertFailsWith<CredentialException> { v.verifyPresentation(pres, "https://verifier.example", "n-2") }.code)
        // agregar una divulgación no cubierta por sd_hash
        val extra = SdJwtParts.parse(sd).disclosures.first { Disclosure.parse(it).name == "gpa" }
        val p = SdJwtParts.parse(pres)
        val widened = p.jwt + "~" + p.disclosures.joinToString("") { "$it~" } + extra + "~" + p.keyBinding
        assertEquals("SD_HASH_MISMATCH", assertFailsWith<CredentialException> { v.verifyPresentation(widened, "https://verifier.example", "n-1") }.code)
        assertEquals("KEY_BINDING_REQUIRED", assertFailsWith<CredentialException> { v.verifyPresentation(sd, "https://verifier.example", "n-1") }.code)
    }

    @Test fun `sd-jwt - la prueba del titular sola no basta - verificacion independiente de la firma del emisor`() = runBlocking {
        // Se cambia la clave del emisor en el resolvedor: la presentacion (KB-JWT valido) debe fallar por la firma del EMISOR.
        val pres = present(issue(), setOf("program"))
        val other = DidKeys.generateP256()
        val v = SdJwtVcVerifier(IssuerKeyResolver { other.public as ECPublicKey })
        assertEquals("INVALID_ISSUER_SIGNATURE", assertFailsWith<CredentialException> { v.verifyPresentation(pres, "https://verifier.example", "n-1") }.code)
    }

    @Test fun `sd-jwt - perfil - typ vc+sd-jwt (perfil anterior) se rechaza salvo habilitarlo`() = runBlocking {
        val sd = issue()
        val parts = SdJwtParts.parse(sd)
        val legacyJwt = relabel(parts.jwt, "vc+sd-jwt")
        val legacy = legacyJwt + "~" + parts.disclosures.joinToString("") { "$it~" }
        assertEquals("INVALID_TYP", assertFailsWith<CredentialException> { SdJwtVcVerifier(resolver).verifyIssuance(legacy) }.code)
        assertEquals("INVALID_ISSUER_SIGNATURE", assertFailsWith<CredentialException> { SdJwtVcVerifier(resolver, acceptLegacyTyp = true).verifyIssuance(legacy) }.code,
            "con la compatibilidad habilitada el typ pasa, pero cambiar el typ invalida la firma (no se puede 'reetiquetar')")
    }

    @Test fun `sd-jwt - expiracion`() = runBlocking {
        val past = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC)
        val old = SdJwtVcIssuer(ISSUER, KID, signer(issuerKp), clock = past).issue("urn:x", claims, holderKp.public as ECPublicKey, Duration.ofDays(1))
        assertEquals("EXPIRED", assertFailsWith<CredentialException> { SdJwtVcVerifier(resolver).verifyIssuance(old) }.code)
    }

    // ---------------- mdoc ----------------
    private val mdocIssuer = MdocIssuer(KID, signer(issuerKp))
    private fun mdoc() = mdocIssuer.issue("org.avance.academic.1", mapOf("org.avance.academic.1" to mapOf("given_name" to "Ana", "program" to "Ingeniería")), holderKp.public as ECPublicKey)

    @Test fun `mdoc - emision y recepcion verificada`() = runBlocking {
        val log = ExecutionLog()
        val v = MdocVerifier(resolver, log = log).verify(mdoc())
        assertEquals("org.avance.academic.1", v.docType)
        assertEquals("Ingeniería", v.elements["org.avance.academic.1"]!!["program"])
        assertEquals(Jwk.thumbprint(holderKp.public as ECPublicKey), Jwk.thumbprint(v.deviceKey), "el MSO enlaza la clave del titular")
        assertEquals(emptyList(), log.mismatches())
    }

    @Test fun `mdoc - un elemento alterado, una firma ajena o un algoritmo distinto se rechazan`() = runBlocking {
        val bytes = mdoc()
        // alterar el valor de un elemento sin recalcular el digest
        val top = CBORObject.DecodeFromBytes(bytes)
        val first = top["nameSpaces"]["org.avance.academic.1"][0]
        val inner = CBORObject.DecodeFromBytes(first.UntagOne().GetByteString())
        inner.Set("elementValue", "Medicina")
        top["nameSpaces"]["org.avance.academic.1"].Set(0, CBORObject.FromObjectAndTag(CBORObject.FromObject(inner.EncodeToBytes()), 24))
        assertEquals("DIGEST_MISMATCH", assertFailsWith<CredentialException> { MdocVerifier(resolver).verify(top.EncodeToBytes()) }.code)

        val evil = DidKeys.generateP256()
        val forged = MdocIssuer(KID, signer(evil)).issue("d", mapOf("n" to mapOf("a" to "b")), holderKp.public as ECPublicKey)
        assertEquals("INVALID_ISSUER_SIGNATURE", assertFailsWith<CredentialException> { MdocVerifier(resolver).verify(forged) }.code)

        val t2 = CBORObject.DecodeFromBytes(bytes)
        t2["issuerAuth"].Set(0, CBORObject.FromObject(CBORObject.NewMap().apply { Add(CBORObject.FromObject(1), CBORObject.FromObject(-35)) }.EncodeToBytes())) // ES384
        assertEquals("UNSUPPORTED_ALG", assertFailsWith<CredentialException> { MdocVerifier(resolver).verify(t2.EncodeToBytes()) }.code)
        assertEquals("INVALID_MDOC", assertFailsWith<CredentialException> { MdocVerifier(resolver).verify(byteArrayOf(1, 2, 3)) }.code)
    }

    // ---------------- perfil, registro de ejecucion y matriz de claves ----------------
    @Test fun `criterio 2 - el registro de ejecucion detecta un algoritmo distinto al declarado`() {
        val log = ExecutionLog()
        log.record(Observed("sd-jwt-vc emisor", "dc+sd-jwt", "dc+sd-jwt", "ES256", "sha-256"))
        assertEquals(emptyList(), log.mismatches())
        log.record(Observed("sd-jwt-vc emisor", "dc+sd-jwt", "dc+sd-jwt", "ES384", "sha-256"))
        log.record(Observed("x", "jwt_vc_json", null, "ES256", null))
        val diffs = log.mismatches()
        assertEquals(2, diffs.size)
        assertTrue(diffs.any { "ES384" in it } && diffs.any { "no declarado" in it })
    }

    @Test fun `criterio 4 - matriz de claves por rol y rechazo de claves compartidas`() {
        val rows = KeyMatrix.build(issuerKp.public as ECPublicKey, holderKp.public as ECPublicKey, KID)
        assertEquals(2, rows.size)
        assertNotEquals(rows[0].thumbprint, rows[1].thumbprint)
        assertTrue(KeyMatrix.toMarkdown(rows).contains("ISSUER_SIGNING"))
        assertEquals("KEY_ROLES_NOT_SEPARATED", assertFailsWith<CredentialException> {
            KeyMatrix.build(issuerKp.public as ECPublicKey, issuerKp.public as ECPublicKey, KID)
        }.code)
    }

    @Test fun `jwk - no acepta material privado ni puntos fuera de la curva`() {
        val j = Jwk.fromPublic(holderKp.public as ECPublicKey)
        assertEquals(Jwk.thumbprint(holderKp.public as ECPublicKey), Jwk.thumbprint(Jwk.toPublic(j)))
        assertFailsWith<CredentialException> { Jwk.toPublic(JsonObject(j + ("d" to JsonPrimitive("AA")))) }
        assertFailsWith<CredentialException> { Jwk.toPublic(JsonObject(j + ("y" to JsonPrimitive(B64.encode(ByteArray(32) { 1 }))))) }
    }

    /** Cambia el `typ` de un JWS dejando payload y firma tal cual (simula reetiquetar un token). */
    private fun relabel(jwt: String, typ: String): String {
        val p = jwt.split(".")
        val header = String(B64.decode(p[0])).replace("\"typ\":\"dc+sd-jwt\"", "\"typ\":\"$typ\"")
        return B64.encode(header.toByteArray()) + "." + p[1] + "." + p[2]
    }

    @Suppress("unused") private val jwsRef = Jws
}
