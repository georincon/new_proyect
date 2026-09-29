package co.org.avance.ssi.wallet

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("wallet")
private const val MAX_BODY = 256 * 1024

/** Inventario de endpoints: cada ruta se declara con su clase. Sirve como evidencia de que no hay protocolos propios paralelos (ERSo 002, criterio 3). */
data class EndpointInfo(val method: String, val path: String, val kind: String, val auth: String)

class EndpointCatalog { val entries = mutableListOf<EndpointInfo>() }

suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: JsonElement) = respondText(body.toString(), ContentType.Application.Json, status)

private fun errorBody(code: String, message: String?, details: List<String> = emptyList()) = buildJsonObject {
    put("error", JsonPrimitive(code)); put("message", JsonPrimitive(message ?: code))
    if (details.isNotEmpty()) put("details", JsonArray(details.map(::JsonPrimitive)))
}

private suspend fun ApplicationCall.body(): JsonObject {
    val text = receiveText()
    if (text.length > MAX_BODY) throw ApiException(HttpStatusCode.PayloadTooLarge, "BODY_TOO_LARGE", "Cuerpo demasiado grande")
    return try { Json.parseToJsonElement(text).jsonObject } catch (e: Exception) { throw ApiException(HttpStatusCode.BadRequest, "INVALID_JSON", "El cuerpo no es un JSON válido") }
}

private fun ApplicationCall.bearer(): String? = request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }

/**
 * API DEL WALLET BACKEND (base /wallet/v1). Es gestión de la cartera, no un protocolo de credenciales: la emisión y la presentación
 * las hace únicamente el `credential-service` con OpenID4VCI / OpenID4VP.
 *  - `kind = WALLET_MANAGEMENT`: instancia, recuperación, respaldo.  - `kind = DID_MANAGEMENT`: publicación del DID del titular en el VDR.
 */
fun Application.walletModule(service: WalletService, catalog: EndpointCatalog = EndpointCatalog()) {
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respondJson(e.status, errorBody(e.code, e.message, e.details)) }
        exception<SerializationException> { call, _ -> call.respondJson(HttpStatusCode.BadRequest, errorBody("INVALID_JSON", "JSON inválido")) }
        exception<Throwable> { call, e -> log.error("Error no controlado", e); call.respondJson(HttpStatusCode.InternalServerError, errorBody("INTERNAL_ERROR", "Error interno")) }
    }

    fun Route.ep(method: String, path: String, kind: String, auth: String, handler: suspend ApplicationCall.() -> Unit) {
        catalog.entries += EndpointInfo(method, "/wallet/v1$path", kind, auth)
        when (method) {
            "GET" -> get(path) { call.handler() }
            "POST" -> post(path) { call.handler() }
            "PUT" -> put(path) { call.handler() }
        }
    }
    suspend fun ApplicationCall.instance() = service.authenticate(parameters["id"] ?: "", bearer())

    routing {
        catalog.entries += EndpointInfo("GET", "/health", "OPS", "ninguna")
        get("/health") { call.respondJson(HttpStatusCode.OK, buildJsonObject { put("status", JsonPrimitive("UP")); put("service", JsonPrimitive("wallet-backend")) }) }

        route("/wallet/v1") {
            ep("POST", "/citizens", "WALLET_MANAGEMENT", "ninguna (laboratorio)") { respondJson(HttpStatusCode.Created, service.createCitizen()) }
            ep("POST", "/challenges", "WALLET_MANAGEMENT", "ACTIVATION: ninguna · DID_KEY: token de instancia") {
                val b = body()
                val purpose = (b["purpose"] as? JsonPrimitive)?.contentOrNull ?: "ACTIVATION"
                val inst = if (purpose == "DID_KEY") service.authenticate((b["instanceId"] as? JsonPrimitive)?.contentOrNull ?: "", bearer()) else null
                respondJson(HttpStatusCode.Created, service.issueChallenge(purpose, (b["citizenRef"] as? JsonPrimitive)?.contentOrNull, inst))
            }
            ep("POST", "/instances", "WALLET_MANAGEMENT", "desafío + prueba de posesión de la clave del dispositivo") { respondJson(HttpStatusCode.Created, service.activate(body())) }
            ep("GET", "/instances/{id}", "WALLET_MANAGEMENT", "token de instancia") { respondJson(HttpStatusCode.OK, service.view(instance())) }
            ep("POST", "/instances/{id}/revoke", "WALLET_MANAGEMENT", "token de instancia") { respondJson(HttpStatusCode.OK, service.revoke(instance())) }
            ep("GET", "/instances/{id}/audit", "WALLET_MANAGEMENT", "token de instancia") { respondJson(HttpStatusCode.OK, service.auditFor(instance())) }
            ep("POST", "/recovery/start", "WALLET_MANAGEMENT", "código de recuperación") {
                val b = body()
                respondJson(HttpStatusCode.OK, service.recoveryStart((b["citizenRef"] as? JsonPrimitive)?.contentOrNull ?: "", (b["recoveryCode"] as? JsonPrimitive)?.contentOrNull ?: ""))
            }
            ep("POST", "/instances/{id}/did", "DID_MANAGEMENT", "token de instancia") { respondJson(HttpStatusCode.Created, service.beginDid(instance(), body())) }
            ep("POST", "/instances/{id}/did/{pub}/proof", "DID_MANAGEMENT", "token de instancia + firma de la clave del DID") {
                val inst = instance()
                val proof = (body()["proof"] as? JsonPrimitive)?.contentOrNull ?: throw ApiException(HttpStatusCode.BadRequest, "MISSING_FIELD", "Falta proof")
                respondJson(HttpStatusCode.OK, service.completeDid(inst, parameters["pub"] ?: "", proof))
            }
            ep("PUT", "/instances/{id}/did-backup", "WALLET_MANAGEMENT", "token de instancia") {
                val inst = instance()
                respondJson(HttpStatusCode.OK, service.putBackup(inst, (body()["ciphertext"] as? JsonPrimitive)?.contentOrNull ?: throw ApiException(HttpStatusCode.BadRequest, "MISSING_FIELD", "Falta ciphertext")))
            }
            ep("GET", "/instances/{id}/did-backup", "WALLET_MANAGEMENT", "token de instancia") { respondJson(HttpStatusCode.OK, service.getBackup(instance())) }
        }
    }
}
