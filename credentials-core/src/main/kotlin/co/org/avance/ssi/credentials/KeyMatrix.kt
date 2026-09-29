package co.org.avance.ssi.credentials

import java.security.interfaces.ECPublicKey

enum class KeyRole { ISSUER_SIGNING, HOLDER_PROOF }

data class KeyMatrixEntry(
    val role: KeyRole,
    val thumbprint: String,
    val algorithm: String,
    val privateKeyLivesIn: String,
    val signs: String,
    val publicKeyPublishedIn: String,
    val verifiedBy: String,
)

/**
 * Matriz de claves por rol (ERSo 002, criterio 4). Se construye con las claves REALES de una ejecución, y falla si el emisor y el
 * titular comparten clave: la firma del emisor y la prueba del titular nunca deben poder sustituirse entre sí.
 */
object KeyMatrix {
    fun build(issuerKey: ECPublicKey, holderKey: ECPublicKey, issuerKid: String): List<KeyMatrixEntry> {
        val issuer = Jwk.thumbprint(issuerKey); val holder = Jwk.thumbprint(holderKey)
        if (issuer == holder) throw CredentialException("KEY_ROLES_NOT_SEPARATED", "El emisor y el titular usan la misma clave")
        return listOf(
            KeyMatrixEntry(KeyRole.ISSUER_SIGNING, issuer, "ES256 (JOSE) / ES256 (COSE alg=-7)", "Signature Service del emisor (nunca sale del servicio)",
                "la credencial: JWT del SD-JWT VC y COSE_Sign1 del MSO", "DID Document del emisor ($issuerKid) en el VDR", "cualquier verificador, resolviendo el DID del emisor"),
            KeyMatrixEntry(KeyRole.HOLDER_PROOF, holder, "ES256", "Hardware seguro del dispositivo del titular (no exportable)",
                "la prueba de posesión: JWT de prueba de OpenID4VCI y KB-JWT de la presentación", "dentro de la credencial: 'cnf.jwk' (SD-JWT) o 'deviceKeyInfo' (mdoc)", "emisor (al emitir) y verificador (al presentar), con la clave de 'cnf'"),
        )
    }

    fun toMarkdown(rows: List<KeyMatrixEntry>): String = buildString {
        appendLine("| Rol | Huella (RFC 7638) | Algoritmo | La clave privada vive en | Firma | Clave pública publicada en | Verifica |")
        appendLine("|---|---|---|---|---|---|---|")
        rows.forEach { appendLine("| ${it.role} | `${it.thumbprint}` | ${it.algorithm} | ${it.privateKeyLivesIn} | ${it.signs} | ${it.publicKeyPublishedIn} | ${it.verifiedBy} |") }
    }
}
