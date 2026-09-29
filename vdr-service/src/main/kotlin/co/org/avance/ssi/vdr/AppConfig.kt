package co.org.avance.ssi.vdr

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Cuenta de entidad (cliente OAuth2 client-credentials) con su namespace did:web reservado. */
@Serializable
data class ClientConfig(
    val clientId: String,
    val secret: String,
    val displayName: String = clientId,
    /** Rutas did:web reservadas para esta entidad, p. ej. "entidades/avance". */
    val namespaces: List<String> = emptyList(),
    /** CN del certificado cliente (mTLS) que debe presentar esta entidad. */
    val mtlsCn: String? = null,
    /** Puede ejecutar respaldo/restauración. */
    val admin: Boolean = false,
)

data class AppConfig(
    val domain: String = "civica-desarrollo.avance.org.co",
    /** ERSo 004: la extensión está APAGADA por defecto. */
    val vdrEnabled: Boolean = false,
    val bindHost: String = "0.0.0.0",
    val publicPort: Int = 8080,
    val adminPort: Int = 8081,
    val publicBaseUrl: String = "https://civica-desarrollo.avance.org.co",
    val confirmBaseUrl: String = publicBaseUrl,
    val confirmCaPem: String? = null,
    val confirmTimeoutMs: Long = 3_000,
    val dbUrl: String = "jdbc:postgresql://localhost:5432/vdr",
    val dbUser: String = "vdr",
    val dbPassword: String = "vdr",
    val jwtSecret: String = "",
    val tokenTtlSeconds: Long = 600,
    val challengeTtlSeconds: Long = 300,
    /** Exige que el canal de escritura llegue con certificado cliente verificado por el proxy (mTLS). */
    val requireMtls: Boolean = true,
    val clients: List<ClientConfig> = emptyList(),
) {
    init {
        if (vdrEnabled) {
            require(jwtSecret.length >= 32) { "VDR_JWT_SECRET debe tener al menos 32 caracteres cuando VDR_ENABLED=true" }
            require(clients.isNotEmpty()) { "VDR_CLIENTS no puede estar vacío cuando VDR_ENABLED=true" }
        }
    }

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            val domain = env["VDR_DOMAIN"] ?: "civica-desarrollo.avance.org.co"
            val publicBase = env["PUBLIC_BASE_URL"] ?: "https://$domain"
            return AppConfig(
                domain = domain,
                vdrEnabled = env["VDR_ENABLED"]?.toBooleanStrictOrNull() ?: false,
                bindHost = env["BIND_HOST"] ?: "0.0.0.0",
                publicPort = env["PUBLIC_PORT"]?.toInt() ?: 8080,
                adminPort = env["ADMIN_PORT"]?.toInt() ?: 8081,
                publicBaseUrl = publicBase,
                confirmBaseUrl = env["CONFIRM_BASE_URL"] ?: publicBase,
                confirmCaPem = env["CONFIRM_CA_PEM"]?.takeIf { it.isNotBlank() }?.let { java.io.File(it).readText() },
                confirmTimeoutMs = env["CONFIRM_TIMEOUT_MS"]?.toLong() ?: 3_000,
                dbUrl = env["DB_URL"] ?: "jdbc:postgresql://localhost:5432/vdr",
                dbUser = env["DB_USER"] ?: "vdr",
                dbPassword = env["DB_PASSWORD"] ?: "vdr",
                jwtSecret = env["VDR_JWT_SECRET"] ?: "",
                tokenTtlSeconds = env["TOKEN_TTL_SECONDS"]?.toLong() ?: 600,
                challengeTtlSeconds = env["CHALLENGE_TTL_SECONDS"]?.toLong() ?: 300,
                requireMtls = env["VDR_REQUIRE_MTLS"]?.toBooleanStrictOrNull() ?: true,
                clients = env["VDR_CLIENTS"]?.takeIf { it.isNotBlank() }?.let { Json.decodeFromString<List<ClientConfig>>(it) } ?: emptyList(),
            )
        }
    }
}
