package co.org.avance.ssi.wallet

import java.io.ByteArrayInputStream
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.time.Clock
import java.util.Date

class AttestationException(val code: String, message: String) : RuntimeException(message)

/** Lector DER mínimo (lo justo para la extensión KeyDescription). Admite etiquetas de número alto (p. ej. [702]). */
internal class Der(private val b: ByteArray, private var pos: Int = 0, private val end: Int = b.size) {
    class Tlv(val cls: Int, val constructed: Boolean, val tag: Int, val start: Int, val end: Int, val bytes: ByteArray) {
        val content: ByteArray get() = bytes.copyOfRange(start, end)
        fun reader() = Der(bytes, start, end)
    }

    fun hasMore() = pos < end

    fun next(): Tlv {
        if (pos >= end) throw AttestationException("MALFORMED_ATTESTATION", "DER truncado")
        val first = b[pos++].toInt() and 0xFF
        val cls = first shr 6
        val constructed = (first and 0x20) != 0
        var tag = first and 0x1F
        if (tag == 0x1F) {
            tag = 0
            do {
                if (pos >= end) throw AttestationException("MALFORMED_ATTESTATION", "Etiqueta truncada")
                val x = b[pos++].toInt() and 0xFF
                tag = (tag shl 7) or (x and 0x7F)
                if (tag > 1_000_000) throw AttestationException("MALFORMED_ATTESTATION", "Etiqueta absurda")
            } while (x and 0x80 != 0)
        }
        if (pos >= end) throw AttestationException("MALFORMED_ATTESTATION", "Longitud ausente")
        var len = b[pos++].toInt() and 0xFF
        if (len and 0x80 != 0) {
            val n = len and 0x7F
            if (n == 0 || n > 4 || pos + n > end) throw AttestationException("MALFORMED_ATTESTATION", "Longitud inválida")
            len = 0
            repeat(n) { len = (len shl 8) or (b[pos++].toInt() and 0xFF) }
        }
        if (len < 0 || pos + len > end) throw AttestationException("MALFORMED_ATTESTATION", "Longitud fuera de rango")
        return Tlv(cls, constructed, tag, pos, pos + len, b).also { pos += len }
    }

    companion object {
        const val UNIVERSAL = 0
        const val CONTEXT = 2
        const val T_BOOLEAN = 1; const val T_INTEGER = 2; const val T_OCTET_STRING = 4; const val T_ENUMERATED = 10; const val T_SEQUENCE = 16; const val T_SET = 17

        fun int(t: Der.Tlv): Int {
            if (t.cls != UNIVERSAL || (t.tag != T_INTEGER && t.tag != T_ENUMERATED) || t.end - t.start !in 1..4) throw AttestationException("MALFORMED_ATTESTATION", "Entero DER inválido")
            var v = if (t.bytes[t.start].toInt() < 0) -1 else 0
            for (i in t.start until t.end) v = (v shl 8) or (t.bytes[i].toInt() and 0xFF)
            return v
        }
    }
}

/** Lo que el dispositivo puede DEMOSTRAR sobre una clave (campos de KeyDescription de Android Key Attestation). */
data class AttestationResult(
    val attestationVersion: Int,
    val attestationSecurityLevel: Int,
    val keymasterSecurityLevel: Int,
    val challenge: ByteArray,
    val origin: Int?,
    val purposes: List<Int>,
    val algorithm: Int?,
    val ecCurve: Int?,
    val verifiedBootState: Int?,
    val deviceLocked: Boolean?,
    val leafPublicKey: ECPublicKey,
) {
    /** El menor de los dos niveles: no se cree más de lo que ambos componentes respaldan. */
    val effectiveLevelCode: Int get() = minOf(attestationSecurityLevel, keymasterSecurityLevel)
    val generatedInSecureEnvironment: Boolean get() = origin == ORIGIN_GENERATED
    companion object { const val ORIGIN_GENERATED = 0 }
}

/**
 * Verificador de Android Key Attestation (esquema público: https://source.android.com/docs/security/features/keystore/attestation).
 *
 * LÍMITE DECLARADO: está implementado contra el esquema publicado y probado con cadenas de LABORATORIO firmadas por una raíz propia.
 * NO se ha probado con cadenas reales emitidas por Google/fabricantes. Para producción hay que configurar las raíces de Google como
 * anclas de confianza y validar contra dispositivos reales. La revocación (CRL de attestation) no se consulta.
 */
class KeyAttestationVerifier(
    private val anchors: Set<X509Certificate>,
    private val clock: Clock = Clock.systemUTC(),
) {
    companion object {
        const val OID = "1.3.6.1.4.1.11129.2.1.17"
        fun parsePemChain(pem: String): List<X509Certificate> {
            val cf = CertificateFactory.getInstance("X.509")
            return cf.generateCertificates(ByteArrayInputStream(pem.toByteArray())).map { it as X509Certificate }
        }
    }

    /** `chainDer`: certificados DER, hoja primero. `expectedChallenge`: el desafío que el backend entregó. */
    fun verify(chainDer: List<ByteArray>, expectedChallenge: ByteArray): AttestationResult {
        if (anchors.isEmpty()) throw AttestationException("NO_TRUST_ANCHORS", "No hay raíces de confianza configuradas para la attestation")
        if (chainDer.isEmpty() || chainDer.size > 6) throw AttestationException("INVALID_CHAIN", "Cadena de attestation vacía o demasiado larga")
        val cf = CertificateFactory.getInstance("X.509")
        val certs = try { chainDer.map { cf.generateCertificate(ByteArrayInputStream(it)) as X509Certificate } } catch (e: Exception) {
            throw AttestationException("INVALID_CHAIN", "Certificado ilegible")
        }
        // La cadena a validar excluye las raíces ancla si venían incluidas.
        val anchorKeys = anchors.map { it.publicKey.encoded.toList() }.toSet()
        val path = certs.filterNot { it.publicKey.encoded.toList() in anchorKeys && it.subjectX500Principal == it.issuerX500Principal }
        if (path.isEmpty()) throw AttestationException("INVALID_CHAIN", "La cadena solo contiene la raíz")
        try {
            val params = PKIXParameters(anchors.map { TrustAnchor(it, null) }.toSet()).apply { isRevocationEnabled = false; date = Date.from(clock.instant()) }
            CertPathValidator.getInstance("PKIX").validate(cf.generateCertPath(path), params)
        } catch (e: java.security.cert.CertPathValidatorException) {
            throw AttestationException("UNTRUSTED_CHAIN", "La cadena no llega a una raíz de confianza: ${e.message}")
        }
        val leaf = certs.first()
        val leafKey = leaf.publicKey as? ECPublicKey ?: throw AttestationException("UNSUPPORTED_KEY", "La clave atestada debe ser EC")
        val ext = leaf.getExtensionValue(OID) ?: throw AttestationException("NO_ATTESTATION_EXTENSION", "El certificado hoja no trae la extensión de key attestation")
        val kd = Der(ext).next().reader().next() // OCTET STRING -> SEQUENCE KeyDescription
        if (kd.tag != Der.T_SEQUENCE) throw AttestationException("MALFORMED_ATTESTATION", "KeyDescription no es una secuencia")
        val r = kd.reader()
        val version = Der.int(r.next()); val attLevel = Der.int(r.next()); Der.int(r.next()); val kmLevel = Der.int(r.next())
        val challenge = r.next().let { if (it.tag != Der.T_OCTET_STRING) throw AttestationException("MALFORMED_ATTESTATION", "attestationChallenge inválido") else it.content }
        r.next() // uniqueId
        val software = r.next(); val tee = r.next()
        if (!java.security.MessageDigest.isEqual(challenge, expectedChallenge)) throw AttestationException("CHALLENGE_MISMATCH", "El desafío de la attestation no es el emitido por el backend")

        // Las afirmaciones de hardware (origen, propósito, arranque verificado) solo valen si vienen de la lista respaldada por hardware.
        val hw = parseAuthList(tee)
        val effective = minOf(attLevel, kmLevel)
        if (effective > 0) parseAuthList(software) // se valida su forma aunque no se confíe en ella
        return AttestationResult(version, attLevel, kmLevel, challenge, hw.origin, hw.purposes, hw.algorithm, hw.ecCurve, hw.bootState, hw.locked, leafKey)
    }

    private class Auth(var origin: Int? = null, var purposes: List<Int> = emptyList(), var algorithm: Int? = null, var ecCurve: Int? = null, var bootState: Int? = null, var locked: Boolean? = null)

    private fun parseAuthList(seq: Der.Tlv): Auth {
        if (seq.tag != Der.T_SEQUENCE) throw AttestationException("MALFORMED_ATTESTATION", "AuthorizationList no es una secuencia")
        val a = Auth()
        val r = seq.reader()
        while (r.hasMore()) {
            val e = r.next()
            if (e.cls != Der.CONTEXT) continue
            val inner = e.reader().next()
            when (e.tag) {
                1 -> a.purposes = inner.reader().let { s -> buildList { while (s.hasMore()) add(Der.int(s.next())) } }
                2 -> a.algorithm = Der.int(inner)
                10 -> a.ecCurve = Der.int(inner)
                702 -> a.origin = Der.int(inner)
                704 -> { // RootOfTrust: verifiedBootKey, deviceLocked, verifiedBootState, verifiedBootHash
                    val s = inner.reader(); s.next()
                    val locked = s.next(); a.locked = locked.bytes[locked.start].toInt() != 0
                    a.bootState = Der.int(s.next())
                }
            }
        }
        return a
    }
}
