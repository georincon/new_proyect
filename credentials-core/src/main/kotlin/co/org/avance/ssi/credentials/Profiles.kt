package co.org.avance.ssi.credentials

/**
 * PERFIL POR FORMATO (ERSo 2026-002, paso 1). Es la fuente única de lo que el proyecto DECLARA; el registro de ejecución
 * ([ExecutionLog]) compara lo declarado con lo que realmente contienen los artefactos producidos.
 */
object CredentialProfiles {
    const val FORMAT_SD_JWT_VC = "dc+sd-jwt"
    const val FORMAT_MDOC = "mso_mdoc"
    const val TYP_SD_JWT_VC = "dc+sd-jwt"
    const val TYP_SD_JWT_VC_LEGACY = "vc+sd-jwt"
    const val TYP_KEY_BINDING = "kb+jwt"
    const val TYP_PROOF_OF_POSSESSION = "openid4vci-proof+jwt"
    const val JOSE_ALG = "ES256"
    const val COSE_ALG_ES256 = -7
    const val SD_ALG = "sha-256"
    const val MDOC_DIGEST_ALG = "SHA-256"

    data class FormatProfile(
        val format: String,
        val artifact: String,
        val signatureAlgorithms: List<String>,
        val digestAlgorithm: String,
        val holderProof: String,
        val references: List<String>,
    )

    val declared: List<FormatProfile> = listOf(
        FormatProfile(
            FORMAT_SD_JWT_VC, "SD-JWT VC (JWS compacto + divulgaciones), typ=$TYP_SD_JWT_VC, prueba del titular = KB-JWT typ=$TYP_KEY_BINDING",
            listOf(JOSE_ALG), SD_ALG, "cnf.jwk (EC P-256) + KB-JWT ES256 con sd_hash, aud y nonce",
            listOf("draft-ietf-oauth-sd-jwt-vc", "RFC 9901 (SD-JWT) o su borrador vigente", "OpenID4VCI 1.0 Anexo A.3"),
        ),
        FormatProfile(
            FORMAT_MDOC, "mdoc IssuerSigned (CBOR) con MSO firmado en COSE_Sign1",
            listOf("ES256 (COSE alg=$COSE_ALG_ES256)"), MDOC_DIGEST_ALG, "deviceKeyInfo.deviceKey (COSE_Key EC2 P-256) en el MSO",
            listOf("ISO/IEC 18013-5", "OpenID4VCI 1.0 Anexo A.2"),
        ),
    )

    /** Protocolos permitidos: NO se definen protocolos propios paralelos (criterio 3). */
    val PROTOCOLS = listOf("OpenID4VCI 1.0", "OpenID4VP 1.0")
}

/** Lo observado en un artefacto REAL (se lee del propio artefacto, no de lo que el código dice haber hecho). */
data class Observed(val role: String, val format: String, val typ: String?, val alg: String, val digestAlg: String?)

/** Registro de ejecución: evidencia del criterio 2 ("declarado == efectivamente usado"). */
class ExecutionLog {
    private val entries = mutableListOf<Observed>()
    @Synchronized fun record(o: Observed) { entries += o }
    @Synchronized fun all(): List<Observed> = entries.toList()

    /** Devuelve las diferencias entre lo observado y el perfil declarado (vacío = coincide). */
    fun mismatches(): List<String> = all().mapNotNull { o ->
        val p = CredentialProfiles.declared.firstOrNull { it.format == o.format }
            ?: return@mapNotNull "${o.role}: formato no declarado '${o.format}'"
        val expectedTyp = if (o.format == CredentialProfiles.FORMAT_SD_JWT_VC) (if (o.role.contains("kb", true) || o.role.contains("prueba", true)) null else CredentialProfiles.TYP_SD_JWT_VC) else null
        when {
            p.signatureAlgorithms.none { it.startsWith(o.alg) } -> "${o.role}: algoritmo usado '${o.alg}' no está en el perfil ${p.signatureAlgorithms}"
            o.digestAlg != null && !o.digestAlg.equals(p.digestAlgorithm, ignoreCase = true) -> "${o.role}: digest usado '${o.digestAlg}' != declarado '${p.digestAlgorithm}'"
            expectedTyp != null && o.typ != expectedTyp -> "${o.role}: typ usado '${o.typ}' != declarado '$expectedTyp'"
            else -> null
        }
    }
}
