package co.org.avance.ssi.sim

import co.org.avance.ssi.didcore.DidKeys
import co.org.avance.ssi.wallet.core.AttestationEvidence
import co.org.avance.ssi.wallet.core.ProtectionLevel
import co.org.avance.ssi.wallet.core.SoftwareKeyCustodian
import org.bouncycastle.asn1.ASN1Boolean
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Enumerated
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.DERTaggedObject
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.time.Instant
import java.util.Base64
import java.util.Date

/**
 * AUTORIDAD DE ATTESTATION DE LABORATORIO. Hace el papel de la raíz del fabricante/Google: firma cadenas cuya hoja lleva la extensión
 * KeyDescription (OID 1.3.6.1.4.1.11129.2.1.17) con la MISMA estructura que Android Key Attestation.
 * Todo lo que produce es SIMULADO: no demuestra nada sobre hardware real. Sirve para probar el verificador del backend.
 */
class LabAttestationAuthority private constructor(
    private val rootCert: X509Certificate,
    private val interCert: X509Certificate,
    private val interKey: PrivateKey,
) {
    companion object {
        fun load(json: String): LabAttestationAuthority {
            val o = kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
            fun cert(k: String) = java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate((o[k] as kotlinx.serialization.json.JsonPrimitive).content.byteInputStream()) as X509Certificate
            val key = java.security.KeyFactory.getInstance("EC").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(Base64.getDecoder().decode((o["interKeyPkcs8"] as kotlinx.serialization.json.JsonPrimitive).content)))
            return LabAttestationAuthority(cert("rootPem"), cert("interPem"), key)
        }

        private fun pem(c: X509Certificate) = StringWriter().also { w ->
            w.append("-----BEGIN CERTIFICATE-----\n").append(Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(c.encoded)).append("\n-----END CERTIFICATE-----\n")
        }.toString()

        private val OID = ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17")
        const val ORIGIN_GENERATED = 0
        const val ORIGIN_IMPORTED = 2
        const val BOOT_VERIFIED = 0
        const val BOOT_UNVERIFIED = 2

        fun create(name: String = "Lab"): LabAttestationAuthority {
            val rootPair = DidKeys.generateP256(); val interPair = DidKeys.generateP256()
            val rootName = X500Name("CN=$name Attestation Root (SIMULADA),O=Avance Laboratorio")
            val root = build(rootName, rootPair.public, rootName, rootPair.private, ca = true, ext = null)
            val inter = build(X500Name("CN=$name Attestation Intermediate (SIMULADA),O=Avance Laboratorio"), interPair.public, rootName, rootPair.private, ca = true, ext = null)
            return LabAttestationAuthority(root, inter, interPair.private)
        }

        private fun build(subject: X500Name, subjectKey: PublicKey, issuer: X500Name, issuerKey: PrivateKey, ca: Boolean, ext: ASN1Encodable?): X509Certificate {
            val now = Instant.now()
            val b = JcaX509v3CertificateBuilder(issuer, BigInteger(64, SecureRandom()).add(BigInteger.ONE), Date.from(now.minusSeconds(3600)), Date.from(now.plusSeconds(86400L * 365)), subject, subjectKey)
            b.addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
            b.addExtension(Extension.keyUsage, true, KeyUsage(if (ca) KeyUsage.keyCertSign else KeyUsage.digitalSignature))
            if (ext != null) b.addExtension(OID, false, ext)
            return JcaX509CertificateConverter().getCertificate(b.build(JcaContentSignerBuilder("SHA256withECDSA").build(issuerKey)))
        }

        private fun tagged(tag: Int, v: ASN1Encodable) = DERTaggedObject(true, tag, v)

        /** AuthorizationList con lo que la verificación del backend lee: propósito, algoritmo, curva, origen y raíz de confianza. */
        private fun authList(origin: Int, bootState: Int, locked: Boolean): DERSequence = DERSequence(arrayOf<ASN1Encodable>(
            tagged(1, DERSet(ASN1Integer(2))),                       // purpose = SIGN
            tagged(2, ASN1Integer(3)),                                // algorithm = EC
            tagged(3, ASN1Integer(256)),                              // keySize
            tagged(10, ASN1Integer(1)),                               // ecCurve = P-256
            tagged(702, ASN1Integer(origin.toLong())),                // origin
            tagged(704, DERSequence(arrayOf<ASN1Encodable>(           // rootOfTrust
                DEROctetString(ByteArray(32) { 7 }), ASN1Boolean.getInstance(locked), ASN1Enumerated(bootState), DEROctetString(ByteArray(32) { 9 }),
            ))),
        ))
    }

    /** Emite [hoja, intermedia] para `devicePublic`. `level` es el nivel que la attestation AFIRMA. */
    fun issue(
        devicePublic: ECPublicKey,
        challenge: ByteArray,
        level: ProtectionLevel,
        origin: Int = ORIGIN_GENERATED,
        bootState: Int = BOOT_VERIFIED,
        deviceLocked: Boolean = true,
    ): List<X509Certificate> {
        val lvl = level.attestationCode.toLong()
        val empty = DERSequence()
        val hw = if (level == ProtectionLevel.SOFTWARE) empty else authList(origin, bootState, deviceLocked)
        val sw = if (level == ProtectionLevel.SOFTWARE) authList(origin, bootState, deviceLocked) else empty
        val keyDescription = DERSequence(arrayOf<ASN1Encodable>(
            ASN1Integer(4L), ASN1Enumerated(lvl.toInt()), ASN1Integer(4L), ASN1Enumerated(lvl.toInt()),
            DEROctetString(challenge), DEROctetString(ByteArray(0)), sw, hw,
        ))
        val leaf = build(X500Name("CN=Android Keystore Key (SIMULADA)"), devicePublic, X500Name.getInstance(interCert.subjectX500Principal.encoded), interKey, ca = false, ext = keyDescription)
        return listOf(leaf, interCert)
    }

    fun rootPem(): String = pem(rootCert)

    /** Persistencia de LABORATORIO (incluye la clave privada de la intermedia simulada): permite que el backend confíe en la raíz antes de arrancar. */
    fun export(): String = kotlinx.serialization.json.buildJsonObject {
        put("rootPem", kotlinx.serialization.json.JsonPrimitive(pem(rootCert)))
        put("interPem", kotlinx.serialization.json.JsonPrimitive(pem(interCert)))
        put("interKeyPkcs8", kotlinx.serialization.json.JsonPrimitive(Base64.getEncoder().encodeToString(interKey.encoded)))
    }.toString()

    val root: X509Certificate get() = rootCert
}

/**
 * Dispositivo SIMULADO que puede aportar evidencia firmada por la autoridad de laboratorio.
 * `level` es el nivel que ESTE dispositivo simulado dice tener (y la autoridad de laboratorio atestigua). `origin`/`bootState` permiten simular
 * dispositivos deshonestos: clave importada, arranque no verificado.
 */
class SimulatedHardwareCustodian(
    private val authority: LabAttestationAuthority,
    level: ProtectionLevel = ProtectionLevel.TEE,
    name: String = "dispositivo-simulado-${level.name.lowercase()}",
    keyPairSource: () -> KeyPair = { DidKeys.generateP256() },
    private val origin: Int = LabAttestationAuthority.ORIGIN_GENERATED,
    private val bootState: Int = LabAttestationAuthority.BOOT_VERIFIED,
) : SoftwareKeyCustodian(name = name, keyPairSource = keyPairSource, maxProtection = level) {
    override fun buildAttestation(publicKey: ECPublicKey, challenge: ByteArray): AttestationEvidence =
        AttestationEvidence(authority.issue(publicKey, challenge, maxProtection, origin, bootState).map { it.encoded })
}
