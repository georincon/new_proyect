package co.org.avance.ssi.sim

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Cliente HTTPS con confianza en la CA de laboratorio y, opcionalmente, certificado cliente (mTLS). Motor Java: el CIO no presenta certificados cliente EC. */
fun netClient(caPath: String?, p12: String? = null, p12Pass: String? = null): HttpClient {
    val trust = caPath?.let { path ->
        val certs = CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(File(path).readBytes()))
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); certs.forEachIndexed { i, c -> setCertificateEntry("ca-$i", c) } }
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }.trustManagers
    }
    val keys = p12?.let {
        val pass = (p12Pass ?: "").toCharArray()
        val ks = KeyStore.getInstance("PKCS12").apply { File(it).inputStream().use { s -> load(s, pass) } }
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, pass) }.keyManagers
    }
    val ctx = SSLContext.getInstance("TLS").apply { init(keys, trust, null) }
    return HttpClient(Java) { expectSuccess = false; followRedirects = false; engine { config { sslContext(ctx) } } }
}
