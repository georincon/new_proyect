package co.org.avance.ssi.wallet

import co.org.avance.ssi.didcore.DidKeys
import co.org.avance.ssi.sim.HolderApp
import co.org.avance.ssi.sim.LabAttestationAuthority
import co.org.avance.ssi.sim.SimulatedHardwareCustodian
import co.org.avance.ssi.sim.WalletBackendClient
import co.org.avance.ssi.vdr.AppConfig
import co.org.avance.ssi.vdr.AuthService
import co.org.avance.ssi.vdr.ClientConfig
import co.org.avance.ssi.vdr.PublicDoc
import co.org.avance.ssi.vdr.PublicRead
import co.org.avance.ssi.vdr.PublicUrlReader
import co.org.avance.ssi.vdr.RegistryService
import co.org.avance.ssi.vdr.RegistryStore
import co.org.avance.ssi.vdr.adminModule
import co.org.avance.ssi.wallet.core.KeyCustodian
import co.org.avance.ssi.wallet.core.ProtectionLevel
import co.org.avance.ssi.wallet.core.SoftwareKeyCustodian
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.server.testing.TestApplication
import org.junit.jupiter.api.Assumptions
import java.nio.file.Files
import java.security.KeyPair
import co.org.avance.ssi.vdr.Db as VdrDb

/** Lee la "URL pública" del VDR en proceso (misma idea que en las pruebas del VDR). */
class InProcessReader : PublicUrlReader {
    lateinit var registry: RegistryService
    lateinit var baseUrl: String
    override suspend fun read(url: String): PublicRead {
        val did = registry.didFromUrlPath(url.removePrefix(baseUrl).split("/").filter { it.isNotEmpty() }) ?: return PublicRead(404, ByteArray(0))
        return when (val d = registry.publicDocument(did)) {
            is PublicDoc.Found -> PublicRead(200, d.bytes)
            PublicDoc.Gone -> PublicRead(410, ByteArray(0))
            PublicDoc.NotFound -> PublicRead(404, ByteArray(0))
        }
    }
}

/**
 * Entorno de prueba: VDR REAL (en proceso, PostgreSQL) + Wallet Backend REAL + autoridad de attestation de laboratorio.
 * Requiere TEST_DB_URL (una base propia de estas pruebas: se vacían las tablas).
 */
class WalletFixture private constructor(
    val cfg: WalletConfig,
    val service: WalletService,
    val registry: RegistryService,
    val authority: LabAttestationAuthority,
    val catalog: EndpointCatalog,
    private val walletDb: Db,
    private val vdrDb: VdrDb,
    private val vdrApp: TestApplication,
    private val walletApp: TestApplication,
) : AutoCloseable {
    val http: HttpClient get() = walletApp.client
    val backend: WalletBackendClient get() = WalletBackendClient(walletApp.client)

    override fun close() { kotlinx.coroutines.runBlocking { runCatching { walletApp.stop() }; runCatching { vdrApp.stop() } }; walletDb.close(); vdrDb.close() }

    fun exec(sql: String) = walletDb.tx { c -> c.createStatement().use { it.execute(sql) } }
    fun count(sql: String): Int = walletDb.tx { c -> c.createStatement().use { st -> st.executeQuery(sql).use { it.next(); it.getInt(1) } } }
    fun vdrCount(sql: String): Int = vdrDb.tx { c -> c.createStatement().use { st -> st.executeQuery(sql).use { it.next(); it.getInt(1) } } }

    /** Un dispositivo SOFTWARE puro (sin evidencia). `capture` permite conocer su clave privada para buscarla después en la base. */
    fun softwareDevice(capture: ((KeyPair) -> Unit)? = null): SoftwareKeyCustodian =
        SoftwareKeyCustodian(keyPairSource = { DidKeys.generateP256().also { capture?.invoke(it) } })

    fun attestingDevice(level: ProtectionLevel, origin: Int = LabAttestationAuthority.ORIGIN_GENERATED, boot: Int = LabAttestationAuthority.BOOT_VERIFIED,
                        a: LabAttestationAuthority = authority, capture: ((KeyPair) -> Unit)? = null) =
        SimulatedHardwareCustodian(a, level, keyPairSource = { DidKeys.generateP256().also { capture?.invoke(it) } }, origin = origin, bootState = boot)

    fun app(c: KeyCustodian): HolderApp = HolderApp(c, backend, cfg.domain, Files.createTempDirectory("holder"))

    companion object {
        const val DOMAIN = "civica-desarrollo.avance.org.co"

        fun create(minimumLevel: ProtectionLevel = ProtectionLevel.SOFTWARE, requireVerifiedBoot: Boolean = true, maxRecoveryAttempts: Int = 5): WalletFixture {
            val url = System.getenv("TEST_DB_URL").orEmpty()
            Assumptions.assumeTrue(url.isNotBlank(), "TEST_DB_URL no definida: se omiten las pruebas de integración con PostgreSQL")
            val authority = LabAttestationAuthority.create()

            // ---- VDR real en proceso ----
            val vdrCfg = AppConfig(
                vdrEnabled = true, confirmTimeoutMs = 2_000, dbUrl = url, dbUser = "vdr", dbPassword = "vdr", jwtSecret = "s".repeat(48), requireMtls = true,
                clients = listOf(
                    ClientConfig("wallet-backend", "wallet-secret-123", "Wallet Backend", listOf("titulares/*")),
                    ClientConfig("vdr-admin", "admin-secret-123", "Administración VDR", emptyList(), admin = true),
                ),
            )
            val vdrDb = VdrDb.connect(url, "vdr", "vdr")
            val walletDb = Db.connect(url, "vdr", "vdr")
            walletDb.tx { c -> c.createStatement().use { it.execute("TRUNCATE wallet_audit, wallet_did_backups, wallet_did_publications, wallet_recovery_tokens, wallet_challenges, wallet_instances, wallet_citizens CASCADE") } }
            vdrDb.tx { c -> c.createStatement().use { it.execute("TRUNCATE audit_log, operations, challenges, did_document_versions, did_documents, namespaces, entity_accounts CASCADE") } }
            val reader = InProcessReader()
            val registry = RegistryService(vdrCfg, vdrDb, RegistryStore(), reader).also { it.bootstrap() }
            reader.registry = registry; reader.baseUrl = vdrCfg.confirmBaseUrl
            val vdrApp = TestApplication { application { adminModule(vdrCfg, registry, AuthService(vdrCfg)) } }

            // ---- Wallet Backend real ----
            val cfg = WalletConfig(domain = DOMAIN, dbUrl = url, minimumLevel = minimumLevel, requireVerifiedBoot = requireVerifiedBoot, maxRecoveryAttempts = maxRecoveryAttempts)
            val gateway = HttpVdrGateway(vdrApp.client, "", "wallet-backend", "wallet-secret-123", mapOf("X-SSL-Client-Verify" to "SUCCESS", "X-SSL-Client-S-DN" to "CN=wallet-backend,O=Avance"))
            val service = WalletService(cfg, walletDb, KeyAttestationVerifier(setOf(authority.root)), gateway)
            val catalog = EndpointCatalog()
            val walletApp = TestApplication { application { walletModule(service, catalog) } }
            kotlinx.coroutines.runBlocking { walletApp.client.get("/health") } // arranca la aplicación (y puebla el catálogo de rutas)
            return WalletFixture(cfg, service, registry, authority, catalog, walletDb, vdrDb, vdrApp, walletApp)
        }
    }
}
