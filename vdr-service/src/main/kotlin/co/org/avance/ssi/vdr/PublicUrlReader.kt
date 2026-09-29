package co.org.avance.ssi.vdr

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class PublicRead(val status: Int, val body: ByteArray)

/** Lee el documento por su URL pública (canal de lectura), para confirmar por contenido y hash. */
interface PublicUrlReader {
    suspend fun read(url: String): PublicRead
}

class HttpPublicUrlReader(cfg: AppConfig) : PublicUrlReader, AutoCloseable {
    private val client = HttpClient(CIO) {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = cfg.confirmTimeoutMs; connectTimeoutMillis = cfg.confirmTimeoutMs }
        expectSuccess = false
        cfg.confirmCaPem?.let { pem -> engine { https { trustManager = trustManagerFor(pem) } } }
    }

    override suspend fun read(url: String): PublicRead = client.get(url).let { PublicRead(it.status.value, it.bodyAsBytes()) }

    override fun close() = client.close()

    private fun trustManagerFor(pem: String): X509TrustManager {
        val certs = CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(pem.toByteArray()))
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            certs.forEachIndexed { i, cert -> setCertificateEntry("ca-$i", cert) }
        }
        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}
