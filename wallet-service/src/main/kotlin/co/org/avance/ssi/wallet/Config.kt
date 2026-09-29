package co.org.avance.ssi.wallet

import co.org.avance.ssi.wallet.core.ProtectionLevel

data class WalletConfig(
    val domain: String = "civica-desarrollo.avance.org.co",
    val port: Int = 8090,
    val bindHost: String = "0.0.0.0",
    val dbUrl: String = "jdbc:postgresql://localhost:5432/vdr",
    val dbUser: String = "vdr",
    val dbPassword: String = "vdr",
    /** Nivel MÍNIMO aceptado para activar una cartera. En laboratorio SOFTWARE; en producción debería ser TEE o superior. */
    val minimumLevel: ProtectionLevel = ProtectionLevel.SOFTWARE,
    /** Un dispositivo con bootloader desbloqueado o arranque no verificado no puede afirmar hardware. */
    val requireVerifiedBoot: Boolean = true,
    val challengeTtlSeconds: Long = 300,
    val recoveryTokenTtlSeconds: Long = 600,
    val maxRecoveryAttempts: Int = 5,
    val recoveryLockMinutes: Long = 15,
    /** Raíces (PEM) en las que se confía para la attestation. En producción: raíces de Google/fabricantes. */
    val attestationRootsPem: String? = null,
    val vdrBaseUrl: String = "https://civica-desarrollo.avance.org.co:8443",
    val vdrClientId: String = "wallet-backend",
    val vdrClientSecret: String = "",
    val vdrP12Path: String? = null,
    val vdrP12Password: String = "changeit",
    val vdrCaPath: String? = null,
    val maxBackupBytes: Int = 64 * 1024,
) {
    val audience: String get() = "wallet:$domain:activation"

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()) = WalletConfig(
            domain = env["VDR_DOMAIN"] ?: "civica-desarrollo.avance.org.co",
            port = env["WALLET_PORT"]?.toInt() ?: 8090,
            bindHost = env["BIND_HOST"] ?: "0.0.0.0",
            dbUrl = env["DB_URL"] ?: "jdbc:postgresql://localhost:5432/vdr",
            dbUser = env["DB_USER"] ?: "vdr",
            dbPassword = env["DB_PASSWORD"] ?: "vdr",
            minimumLevel = env["WALLET_MIN_LEVEL"]?.let { ProtectionLevel.valueOf(it) } ?: ProtectionLevel.SOFTWARE,
            requireVerifiedBoot = env["WALLET_REQUIRE_VERIFIED_BOOT"]?.toBooleanStrictOrNull() ?: true,
            attestationRootsPem = env["WALLET_ATTESTATION_ROOTS"]?.takeIf { it.isNotBlank() && java.io.File(it).exists() }?.let { java.io.File(it).readText() },
            vdrBaseUrl = env["WALLET_VDR_URL"] ?: "https://${env["VDR_DOMAIN"] ?: "civica-desarrollo.avance.org.co"}:8443",
            vdrClientId = env["WALLET_VDR_CLIENT_ID"] ?: "wallet-backend",
            vdrClientSecret = env["WALLET_VDR_CLIENT_SECRET"] ?: "",
            vdrP12Path = env["WALLET_VDR_P12"]?.takeIf { it.isNotBlank() },
            vdrP12Password = env["WALLET_VDR_P12_PASS"] ?: "changeit",
            vdrCaPath = env["WALLET_VDR_CA"]?.takeIf { it.isNotBlank() },
        )
    }
}
