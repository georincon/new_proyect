package co.org.avance.ssi.credential

data class CredentialConfig(
    val domain: String = "civica-desarrollo.avance.org.co",
    val port: Int = 8100,
    val bindHost: String = "0.0.0.0",
    /** Identificador del emisor OpenID4VCI (URL https sin barra final). */
    val issuerId: String = "https://civica-desarrollo.avance.org.co:8444",
    /** DID del emisor: su DID Document publicado en el VDR contiene la clave que verifica sus credenciales. */
    val issuerDid: String = "did:web:civica-desarrollo.avance.org.co:entidades:avance",
    val issuerKeyFile: String? = null,
    /** Portal de administración del emisor / del verificador (no es protocolo de credenciales). */
    val adminToken: String = "",
    val resolverCaPath: String? = null,
    val offerTtlSeconds: Long = 600,
    val accessTokenTtlSeconds: Long = 300,
    val nonceTtlSeconds: Long = 300,
    val proofMaxAgeSeconds: Long = 300,
) {
    val issuerKid: String get() = "$issuerDid#key-1"
    val verifierResponseUri: String get() = "$issuerId/verifier/response"
    val verifierClientId: String get() = "redirect_uri:$verifierResponseUri"

    init { require(adminToken.length >= 24 || adminToken.isEmpty()) { "ADMIN_TOKEN debe tener al menos 24 caracteres" } }

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): CredentialConfig {
            val domain = env["VDR_DOMAIN"] ?: "civica-desarrollo.avance.org.co"
            return CredentialConfig(
                domain = domain,
                port = env["CREDENTIAL_PORT"]?.toInt() ?: 8100,
                bindHost = env["BIND_HOST"] ?: "0.0.0.0",
                issuerId = (env["CREDENTIAL_ISSUER_ID"] ?: "https://$domain:8444").trimEnd('/'),
                issuerDid = env["ISSUER_DID"] ?: "did:web:$domain:entidades:avance",
                issuerKeyFile = env["ISSUER_KEY_FILE"]?.takeIf { it.isNotBlank() },
                adminToken = env["CREDENTIAL_ADMIN_TOKEN"] ?: "",
                resolverCaPath = env["RESOLVER_CA"]?.takeIf { it.isNotBlank() },
            )
        }
    }
}
