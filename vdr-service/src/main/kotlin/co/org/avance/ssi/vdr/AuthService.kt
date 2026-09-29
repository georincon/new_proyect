package co.org.avance.ssi.vdr

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpStatusCode
import java.security.MessageDigest
import java.time.Instant
import java.util.Date

/** OAuth2 client-credentials para el canal de escritura entre servicios (ERSo 006). El permiso viaja como scopes por namespace. */
class AuthService(private val cfg: AppConfig) {
    companion object {
        const val ISSUER = "vdr-extension"
        const val AUDIENCE = "vdr-admin"
    }

    private val algorithm = Algorithm.HMAC256(cfg.jwtSecret.ifBlank { "unused-when-vdr-disabled-0000000000" })
    val verifier = JWT.require(algorithm).withIssuer(ISSUER).withAudience(AUDIENCE).build()!!

    fun client(clientId: String): ClientConfig? = cfg.clients.firstOrNull { it.clientId == clientId }

    fun authenticate(clientId: String?, secret: String?): ClientConfig? {
        val client = clientId?.let(::client) ?: return null
        val ok = MessageDigest.isEqual(client.secret.toByteArray(), (secret ?: "").toByteArray())
        return client.takeIf { ok }
    }

    fun issueToken(client: ClientConfig): Pair<String, Long> {
        val scopes = client.namespaces.map { "did:write:$it" } + if (client.admin) listOf("vdr:admin") else emptyList()
        val expires = Instant.now().plusSeconds(cfg.tokenTtlSeconds)
        val token = JWT.create()
            .withIssuer(ISSUER).withAudience(AUDIENCE).withSubject(client.clientId)
            .withClaim("scope", scopes.joinToString(" "))
            .withIssuedAt(Date.from(Instant.now())).withExpiresAt(Date.from(expires))
            .sign(algorithm)
        return token to cfg.tokenTtlSeconds
    }

    /** Extrae el CN del DN que el proxy nginx entrega en X-SSL-Client-S-DN ("CN=x,O=y" o "/CN=x/O=y"). */
    fun mtlsCn(verifyHeader: String?, dnHeader: String?): String? {
        if (verifyHeader != "SUCCESS" || dnHeader == null) return null
        return Regex("(?:^|[,/])\\s*CN=([^,/]+)").find(dnHeader)?.groupValues?.get(1)?.trim()
    }

    /** Devuelve true si el canal cumple la restricción (mTLS verificado y ligado a la entidad) o si no se exige. */
    fun channelRestricted(client: ClientConfig, verifyHeader: String?, dnHeader: String?): Boolean {
        if (!cfg.requireMtls) return true
        val cn = mtlsCn(verifyHeader, dnHeader) ?: return false
        return cn == (client.mtlsCn ?: client.clientId)
    }

    fun assertChannel(client: ClientConfig, verifyHeader: String?, dnHeader: String?) {
        if (!channelRestricted(client, verifyHeader, dnHeader)) {
            throw ApiException(HttpStatusCode.Forbidden, "MTLS_REQUIRED", "El canal de escritura exige certificado cliente válido de la entidad")
        }
    }
}
