package co.org.avance.ssi.credential

import co.org.avance.ssi.credentials.ExecutionLog
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.security.MessageDigest

private val log = LoggerFactory.getLogger("credential")
private const val MAX_BODY = 256 * 1024

suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: JsonElement) = respondText(body.toString(), ContentType.Application.Json, status)

private suspend fun ApplicationCall.jsonBody(): JsonObject {
    val text = receiveText()
    if (text.length > MAX_BODY) throw ProtocolError(HttpStatusCode.PayloadTooLarge, "invalid_request", "Cuerpo demasiado grande")
    return try { Json.parseToJsonElement(text).jsonObject } catch (e: Exception) { throw ProtocolError(HttpStatusCode.BadRequest, "invalid_request", "El cuerpo no es un JSON válido") }
}

private fun ApplicationCall.bearer(): String? = request.header(HttpHeaders.Authorization)?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Rutas del Issuer y del Verifier. Cada ruta declara su clase y el estándar que la sustenta; el catálogo resultante es el inventario
 * de endpoints (ERSo 2026-002, criterio 3). NO existe ningún endpoint de emisión o presentación fuera de OpenID4VCI / OpenID4VP.
 */
fun Application.credentialModule(cfg: CredentialConfig, issuer: IssuerService, verifier: VerifierService, catalog: EndpointCatalog = EndpointCatalog()) {
    install(StatusPages) {
        exception<ProtocolError> { call, e -> call.respondJson(e.status, buildJsonObject { put("error", JsonPrimitive(e.error)); put("error_description", JsonPrimitive(e.description)) }) }
        exception<Throwable> { call, e -> log.error("Error no controlado", e); call.respondJson(HttpStatusCode.InternalServerError, buildJsonObject { put("error", JsonPrimitive("server_error")) }) }
    }

    fun Route.ep(method: String, path: String, kind: String, standard: String, auth: String, handler: suspend ApplicationCall.() -> Unit) {
        catalog.entries += EndpointInfo(method, path, kind, standard, auth)
        if (method == "GET") get(path) { call.handler() } else post(path) { call.handler() }
    }

    fun ApplicationCall.requireAdmin() {
        val t = bearer()
        if (cfg.adminToken.isEmpty() || t == null || !MessageDigest.isEqual(t.toByteArray(), cfg.adminToken.toByteArray())) {
            throw ProtocolError(HttpStatusCode.Unauthorized, "invalid_token", "Se requiere el token de administración")
        }
    }

    routing {
        ep("GET", "/health", "OPS", "n/a", "ninguna") { respondJson(HttpStatusCode.OK, buildJsonObject { put("status", JsonPrimitive("UP")); put("service", JsonPrimitive("credential-service")) }) }

        // ---------------- OpenID4VCI 1.0 ----------------
        ep("GET", "/.well-known/openid-credential-issuer", "OID4VCI", "OpenID4VCI 1.0 §12.2", "ninguna") { respondJson(HttpStatusCode.OK, issuer.metadata()) }
        ep("GET", "/.well-known/oauth-authorization-server", "OID4VCI", "RFC 8414 (usado por OpenID4VCI 1.0 §11)", "ninguna") { respondJson(HttpStatusCode.OK, issuer.authorizationServerMetadata()) }
        ep("POST", "/token", "OID4VCI", "RFC 6749 + OpenID4VCI 1.0 §6 (pre-authorized_code)", "código pre-autorizado (+ tx_code)") {
            val p = receiveParameters()
            response.header(HttpHeaders.CacheControl, "no-store")
            respondJson(HttpStatusCode.OK, issuer.token(p.names().associateWith { p[it] }))
        }
        ep("POST", "/nonce", "OID4VCI", "OpenID4VCI 1.0 §7", "ninguna") { response.header(HttpHeaders.CacheControl, "no-store"); respondJson(HttpStatusCode.OK, issuer.newNonce()) }
        ep("POST", "/credential", "OID4VCI", "OpenID4VCI 1.0 §8", "token de acceso + prueba de posesión") {
            response.header(HttpHeaders.CacheControl, "no-store")
            issuer.requireAccessToken(bearer())
            respondJson(HttpStatusCode.OK, issuer.credential(bearer(), jsonBody()))
        }

        // ---------------- OpenID4VP 1.0 ----------------
        ep("POST", "/verifier/response", "OID4VP", "OpenID4VP 1.0 §8.2 (response_mode=direct_post)", "state + nonce de la solicitud") {
            val p = receiveParameters()
            respondJson(HttpStatusCode.OK, verifier.receive(p["vp_token"], p["state"]))
        }

        // ---------------- Administración (portales de emisor y verificador; NO son protocolo de credenciales) ----------------
        ep("POST", "/admin/offers", "ADMIN", "Portal del emisor (no estandarizado); produce una oferta OpenID4VCI 1.0 §4", "token de administración") {
            requireAdmin()
            val b = jsonBody()
            val ids = (b["credential_configuration_ids"] as? JsonArray)?.map { it.toString().trim('"') } ?: throw ProtocolError(HttpStatusCode.BadRequest, "invalid_request", "Falta credential_configuration_ids")
            val claims = (b["claims"] as? JsonObject) ?: throw ProtocolError(HttpStatusCode.BadRequest, "invalid_request", "Falta claims")
            val o = issuer.createOffer(ids, claims, (b["tx_code"] as? JsonPrimitive)?.contentOrNull == "true")
            respondJson(HttpStatusCode.Created, buildJsonObject {
                put("credential_offer", o.offer); put("credential_offer_uri_by_value", JsonPrimitive(o.uri)); o.txCode?.let { put("tx_code", JsonPrimitive(it)) }
            })
        }
        ep("POST", "/admin/verifier/requests", "ADMIN", "Portal del verificador (no estandarizado); produce una solicitud OpenID4VP 1.0 §5", "token de administración") {
            requireAdmin()
            respondJson(HttpStatusCode.Created, verifier.create((jsonBody()["dcql_query"] as? JsonObject) ?: throw ProtocolError(HttpStatusCode.BadRequest, "invalid_request", "Falta dcql_query")))
        }
        ep("GET", "/admin/verifier/requests/{id}", "ADMIN", "Portal del verificador (no estandarizado)", "token de administración") {
            requireAdmin(); respondJson(HttpStatusCode.OK, verifier.result(parameters["id"] ?: ""))
        }
        ep("GET", "/admin/execution-log", "OPS", "Evidencia del criterio 2 (registro de ejecución)", "token de administración") {
            requireAdmin()
            val l: ExecutionLog = issuer.log
            respondJson(HttpStatusCode.OK, buildJsonObject {
                put("observed", JsonArray(l.all().map { o -> buildJsonObject {
                    put("role", JsonPrimitive(o.role)); put("format", JsonPrimitive(o.format)); put("typ", o.typ?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
                    put("alg", JsonPrimitive(o.alg)); put("digest", o.digestAlg?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
                } }))
                put("mismatches", JsonArray(l.mismatches().map(::JsonPrimitive)))
            })
        }
    }
}
