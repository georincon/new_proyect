package co.org.avance.ssi.vdr

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
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
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.format.DateTimeParseException

private val log = LoggerFactory.getLogger("vdr")
val DID_JSON: ContentType = ContentType("application", "did+json")
private val PRECONDITION_REQUIRED = HttpStatusCode(428, "Precondition Required")

suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: JsonElement) =
    respondText(body.toString(), ContentType.Application.Json, status)

private fun errorBody(code: String, message: String?, details: List<String> = emptyList()) = buildJsonObject {
    put("error", JsonPrimitive(code)); put("message", JsonPrimitive(message ?: code))
    if (details.isNotEmpty()) put("details", JsonArray(details.map(::JsonPrimitive)))
}

private fun io.ktor.server.application.Application.installErrorHandling() = install(StatusPages) {
    exception<ApiException> { call, e -> call.respondJson(e.status, errorBody(e.code, e.message, e.details)) }
    exception<SerializationException> { call, _ -> call.respondJson(HttpStatusCode.BadRequest, errorBody("INVALID_JSON", "El cuerpo no es un JSON válido")) }
    exception<IllegalArgumentException> { call, e -> call.respondJson(HttpStatusCode.BadRequest, errorBody("BAD_REQUEST", e.message)) }
    exception<Throwable> { call, e ->
        log.error("Error no controlado", e)
        call.respondJson(HttpStatusCode.InternalServerError, errorBody("INTERNAL_ERROR", "Error interno"))
    }
}

/**
 * CANAL PÚBLICO (solo lectura). Contiene el "camino base" (/health, /base/ping), que funciona igual con la extensión
 * apagada, y —solo si el VDR está habilitado— la lectura de los did.json publicados.
 */
fun Application.publicModule(cfg: AppConfig, registry: RegistryService?) {
    installErrorHandling()
    routing {
        get("/health") {
            call.respondJson(HttpStatusCode.OK, buildJsonObject { put("status", JsonPrimitive("UP")); put("vdr", JsonPrimitive(if (registry != null) "enabled" else "disabled")) })
        }
        get("/base/ping") { call.respondText("pong") }
        if (registry != null) {
            get("{path...}") {
                val segments = call.parameters.getAll("path").orEmpty()
                val did = registry.didFromUrlPath(segments) ?: throw ApiException(HttpStatusCode.NotFound, "NOT_FOUND", "Recurso no encontrado")
                when (val doc = registry.publicDocument(did)) {
                    is PublicDoc.Found -> {
                        call.response.header(HttpHeaders.ETag, "\"${doc.version}\"")
                        call.response.header(HttpHeaders.CacheControl, "no-cache")
                        call.response.header("X-Content-Hash", doc.hash)
                        call.respondBytes(doc.bytes, DID_JSON, HttpStatusCode.OK)
                    }
                    PublicDoc.Gone -> throw ApiException(HttpStatusCode.Gone, "DEACTIVATED", "El DID fue desactivado")
                    PublicDoc.NotFound -> throw ApiException(HttpStatusCode.NotFound, "NOT_FOUND", "El DID no existe")
                }
            }
        }
    }
}

/** CANAL DE ESCRITURA (separado del público): OAuth2 client-credentials + mTLS terminado en el proxy. */
fun Application.adminModule(cfg: AppConfig, registry: RegistryService, auth: AuthService) {
    installErrorHandling()
    install(Authentication) {
        jwt("svc") {
            verifier(auth.verifier)
            validate { cred -> cred.payload.subject?.takeIf { auth.client(it) != null }?.let { JWTPrincipal(cred.payload) } }
            challenge { _, _ ->
                registry.auditEvent("anonymous", "AUTH_DENIED", "token ausente, inválido o expirado")
                call.respondJson(HttpStatusCode.Unauthorized, errorBody("UNAUTHORIZED", "Token de servicio ausente, inválido o expirado"))
            }
        }
    }

    fun ApplicationCall.verifyHeader() = request.header("X-SSL-Client-Verify")
    fun ApplicationCall.dnHeader() = request.header("X-SSL-Client-S-DN")
    fun ApplicationCall.client(): ClientConfig = auth.client(principal<JWTPrincipal>()!!.payload.subject)!!
    fun ApplicationCall.did(): String = parameters["did"] ?: throw ApiException(HttpStatusCode.BadRequest, "DID_REQUIRED", "Falta el DID")
    suspend fun ApplicationCall.jsonBody(): JsonObject = Json.parseToJsonElement(receiveText()).jsonObject

    routing {
        route("/admin/v1") {
            post("/oauth/token") {
                val form = call.receiveParameters()
                if (form["grant_type"] != "client_credentials") throw ApiException(HttpStatusCode.BadRequest, "unsupported_grant_type", "Solo client_credentials")
                val client = auth.authenticate(form["client_id"], form["client_secret"]) ?: run {
                    registry.auditEvent(form["client_id"] ?: "unknown", "TOKEN_DENIED", "credenciales inválidas")
                    throw ApiException(HttpStatusCode.Unauthorized, "invalid_client", "Credenciales de cliente inválidas")
                }
                if (!auth.channelRestricted(client, call.verifyHeader(), call.dnHeader())) {
                    registry.auditEvent(client.clientId, "TOKEN_DENIED", "canal sin mTLS válido")
                    throw ApiException(HttpStatusCode.Forbidden, "MTLS_REQUIRED", "El canal de escritura exige certificado cliente válido de la entidad")
                }
                val (token, ttl) = auth.issueToken(client)
                registry.auditEvent(client.clientId, "TOKEN_ISSUED", "scopes=${client.namespaces.joinToString { "did:write:$it" }}")
                call.respondJson(HttpStatusCode.OK, buildJsonObject {
                    put("access_token", JsonPrimitive(token)); put("token_type", JsonPrimitive("Bearer")); put("expires_in", JsonPrimitive(ttl))
                })
            }

            authenticate("svc") {
                post("/challenges") {
                    val client = call.client()
                    val restricted = auth.channelRestricted(client, call.verifyHeader(), call.dnHeader())
                    val body = call.jsonBody()
                    val did = body["did"]?.jsonPrimitive?.contentOrNull ?: throw ApiException(HttpStatusCode.BadRequest, "DID_REQUIRED", "Falta 'did'")
                    val view = registry.issueChallenge(client.clientId, did, body["purpose"]?.jsonPrimitive?.contentOrNull, restricted)
                    call.respondJson(HttpStatusCode.Created, Json.encodeToJsonElement(view))
                }

                put("/documents/{did}") {
                    val client = call.client()
                    auth.assertChannel(client, call.verifyHeader(), call.dnHeader())
                    val expected = parseIfMatch(call.request.header(HttpHeaders.IfMatch))
                    val idem = idempotencyKey(call.request.header("Idempotency-Key"))
                    val body = call.jsonBody()
                    val purpose = if (expected == 0) Purpose.CREATE else Purpose.UPDATE
                    val (view, status) = registry.write(
                        client.clientId, call.did(), purpose, expected, idem,
                        body["challengeId"]?.jsonPrimitive?.contentOrNull ?: throw ApiException(HttpStatusCode.BadRequest, "CHALLENGE_REQUIRED", "Falta 'challengeId'"),
                        body["document"]?.jsonObject,
                        body["proof"]?.jsonPrimitive?.contentOrNull ?: throw ApiException(HttpStatusCode.BadRequest, "PROOF_REQUIRED", "Falta 'proof'"),
                    )
                    call.respondJson(status, Json.encodeToJsonElement(view))
                }

                post("/documents/{did}/deactivate") {
                    val client = call.client()
                    auth.assertChannel(client, call.verifyHeader(), call.dnHeader())
                    val expected = parseIfMatch(call.request.header(HttpHeaders.IfMatch))
                    val idem = idempotencyKey(call.request.header("Idempotency-Key"))
                    val body = call.jsonBody()
                    val (view, status) = registry.write(
                        client.clientId, call.did(), Purpose.DEACTIVATE, expected, idem,
                        body["challengeId"]?.jsonPrimitive?.contentOrNull ?: throw ApiException(HttpStatusCode.BadRequest, "CHALLENGE_REQUIRED", "Falta 'challengeId'"),
                        null,
                        body["proof"]?.jsonPrimitive?.contentOrNull ?: throw ApiException(HttpStatusCode.BadRequest, "PROOF_REQUIRED", "Falta 'proof'"),
                    )
                    call.respondJson(status, Json.encodeToJsonElement(view))
                }

                get("/documents/{did}/versions") {
                    val client = call.client()
                    registry.assertNamespaceAccess(client.clientId, client.admin, call.did())
                    call.respondJson(HttpStatusCode.OK, Json.encodeToJsonElement(registry.versions(call.did()).map { it.copy(document = null) }))
                }
                get("/documents/{did}/versions/{n}") {
                    val client = call.client()
                    registry.assertNamespaceAccess(client.clientId, client.admin, call.did())
                    val n = call.parameters["n"]?.toIntOrNull() ?: throw ApiException(HttpStatusCode.BadRequest, "BAD_REQUEST", "Versión inválida")
                    call.respondJson(HttpStatusCode.OK, Json.encodeToJsonElement(registry.version(call.did(), n)))
                }
                get("/documents/{did}/state") {
                    val client = call.client()
                    registry.assertNamespaceAccess(client.clientId, client.admin, call.did())
                    val at = try {
                        Instant.parse(call.request.queryParameters["at"] ?: throw ApiException(HttpStatusCode.BadRequest, "BAD_REQUEST", "Falta ?at=<instante ISO-8601>"))
                    } catch (e: DateTimeParseException) {
                        throw ApiException(HttpStatusCode.BadRequest, "BAD_REQUEST", "Instante ISO-8601 inválido")
                    }
                    call.respondJson(HttpStatusCode.OK, Json.encodeToJsonElement(registry.stateAt(call.did(), at)))
                }

                get("/operations/{id}") {
                    val client = call.client()
                    val op = registry.operation(call.parameters["id"]!!)
                    if (!client.admin && op.clientId != client.clientId) throw ApiException(HttpStatusCode.Forbidden, "FORBIDDEN", "La operación pertenece a otra entidad")
                    call.respondJson(HttpStatusCode.OK, Json.encodeToJsonElement(op))
                }
                post("/operations/{id}/reconcile") {
                    val client = call.client()
                    auth.assertChannel(client, call.verifyHeader(), call.dnHeader())
                    call.respondJson(HttpStatusCode.OK, Json.encodeToJsonElement(registry.reconcile(client.clientId, call.parameters["id"]!!, client.admin)))
                }

                get("/audit") {
                    val client = call.client()
                    val did = call.request.queryParameters["did"]
                    if (did == null && !client.admin) throw ApiException(HttpStatusCode.Forbidden, "FORBIDDEN", "Sin ?did= solo un administrador puede consultar toda la auditoría")
                    if (did != null) registry.assertNamespaceAccess(client.clientId, client.admin, did)
                    call.respondJson(HttpStatusCode.OK, Json.encodeToJsonElement(registry.audit(did)))
                }

                post("/backup") {
                    val client = call.client()
                    auth.assertChannel(client, call.verifyHeader(), call.dnHeader())
                    if (!client.admin) throw ApiException(HttpStatusCode.Forbidden, "ADMIN_REQUIRED", "Solo un administrador puede respaldar")
                    call.respondJson(HttpStatusCode.OK, registry.backup(client.clientId))
                }
                post("/restore") {
                    val client = call.client()
                    auth.assertChannel(client, call.verifyHeader(), call.dnHeader())
                    if (!client.admin) throw ApiException(HttpStatusCode.Forbidden, "ADMIN_REQUIRED", "Solo un administrador puede restaurar")
                    call.respondJson(HttpStatusCode.OK, registry.restore(client.clientId, call.jsonBody()))
                }
            }
        }
    }
}

private fun parseIfMatch(header: String?): Int {
    val raw = header?.trim()?.removePrefix("W/")?.trim('"')
        ?: throw ApiException(PRECONDITION_REQUIRED, "IF_MATCH_REQUIRED", "La escritura exige If-Match con la versión esperada (0 para crear)")
    return raw.toIntOrNull()?.takeIf { it >= 0 } ?: throw ApiException(HttpStatusCode.BadRequest, "IF_MATCH_INVALID", "If-Match debe ser un entero >= 0")
}

private fun idempotencyKey(header: String?): String = header?.trim()?.takeIf { it.isNotEmpty() && it.length <= 128 }
    ?: throw ApiException(PRECONDITION_REQUIRED, "IDEMPOTENCY_KEY_REQUIRED", "La escritura exige el encabezado Idempotency-Key (máx. 128 caracteres)")
