package co.org.avance.ssi.wallet

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory

fun main() {
    val log = LoggerFactory.getLogger("wallet.main")
    val cfg = WalletConfig.fromEnv()
    val db = Db.connect(cfg.dbUrl, cfg.dbUser, cfg.dbPassword)
    val anchors = cfg.attestationRootsPem?.let { KeyAttestationVerifier.parsePemChain(it).toSet() } ?: emptySet()
    if (anchors.isEmpty()) log.warn("Sin raíces de attestation configuradas: solo se aceptarán carteras SOFTWARE sin evidencia")
    val client = HttpVdrGateway.mtlsClient(cfg.vdrCaPath, cfg.vdrP12Path, cfg.vdrP12Password)
    val vdr = HttpVdrGateway(client, cfg.vdrBaseUrl, cfg.vdrClientId, cfg.vdrClientSecret)
    val service = WalletService(cfg, db, KeyAttestationVerifier(anchors), vdr)
    log.info("Wallet Backend en :{} (dominio {}, nivel mínimo {}, VDR {})", cfg.port, cfg.domain, cfg.minimumLevel, cfg.vdrBaseUrl)
    embeddedServer(Netty, port = cfg.port, host = cfg.bindHost) { walletModule(service) }.start(wait = true)
}
