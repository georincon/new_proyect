package co.org.avance.ssi.vdr

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory

fun main() {
    val log = LoggerFactory.getLogger("vdr.main")
    val cfg = AppConfig.fromEnv()

    // ERSo 004, paso 5: con VDR_ENABLED=false no se conecta a la base de datos ni se abre el canal de escritura.
    val registry = if (cfg.vdrEnabled) {
        val db = Db.connect(cfg.dbUrl, cfg.dbUser, cfg.dbPassword)
        RegistryService(cfg, db, RegistryStore(), HttpPublicUrlReader(cfg)).also { it.bootstrap() }
    } else null

    if (registry != null) {
        val auth = AuthService(cfg)
        embeddedServer(Netty, port = cfg.adminPort, host = cfg.bindHost) { adminModule(cfg, registry, auth) }.start(wait = false)
        log.info("VDR HABILITADO: canal de escritura en :{} (solo red interna), dominio {}", cfg.adminPort, cfg.domain)
    } else {
        log.info("VDR APAGADO (VDR_ENABLED=false): solo el camino base está activo")
    }
    embeddedServer(Netty, port = cfg.publicPort, host = cfg.bindHost) { publicModule(cfg, registry) }.start(wait = true)
}
