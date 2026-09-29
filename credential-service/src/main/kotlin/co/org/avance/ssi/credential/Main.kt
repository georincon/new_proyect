package co.org.avance.ssi.credential

import co.org.avance.ssi.credentials.ExecutionLog
import co.org.avance.ssi.credentials.IssuerKeyResolver
import co.org.avance.ssi.credentials.SdJwtVcVerifier
import co.org.avance.ssi.didcore.Multikey
import co.org.avance.ssi.resolver.DidWebResolver
import co.org.avance.ssi.resolver.VerificationKeyResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

fun main() {
    val log = LoggerFactory.getLogger("credential.main")
    val cfg = CredentialConfig.fromEnv()
    require(cfg.adminToken.isNotEmpty()) { "CREDENTIAL_ADMIN_TOKEN es obligatorio" }
    val keyFile = requireNotNull(cfg.issuerKeyFile) { "ISSUER_KEY_FILE es obligatorio (JSON de did-tools keygen)" }
    val j = Json.parseToJsonElement(File(keyFile).readText()).jsonObject
    val priv = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(j["privateKeyPkcs8"]!!.jsonPrimitive.content)))
    Multikey.decodeP256(j["publicKeyMultibase"]!!.jsonPrimitive.content) // valida que la clave pública sea coherente
    val signer: (ByteArray) -> ByteArray = { data -> Signature.getInstance("SHA256withECDSAinP1363Format").apply { initSign(priv); update(data) }.sign() }

    val http = cfg.resolverCaPath?.let { path ->
        val certs = CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(File(path).readBytes()))
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); certs.forEachIndexed { i, c -> setCertificateEntry("ca-$i", c) } }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }.trustManagers, null) }
        HttpClient(Java) { expectSuccess = false; followRedirects = false; engine { config { sslContext(ctx) } } }
    } ?: DidWebResolver.defaultClient()
    val keys = VerificationKeyResolver(DidWebResolver(http))
    val resolver = IssuerKeyResolver { kid -> keys.resolve(kid) }

    val execLog = ExecutionLog()
    val issuer = IssuerService(cfg, signer, log = execLog)
    val verifier = VerifierService(cfg, SdJwtVcVerifier(resolver, log = execLog))
    log.info("Credential service en :{} · emisor {} · DID {}", cfg.port, cfg.issuerId, cfg.issuerDid)
    embeddedServer(Netty, port = cfg.port, host = cfg.bindHost) { credentialModule(cfg, issuer, verifier) }.start(wait = true)
}
