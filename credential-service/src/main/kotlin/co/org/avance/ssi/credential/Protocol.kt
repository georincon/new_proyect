package co.org.avance.ssi.credential

import io.ktor.http.HttpStatusCode

/** Error de protocolo con el formato de OAuth 2.0 / OpenID4VCI / OpenID4VP: {"error": "...", "error_description": "..."}. */
class ProtocolError(val status: HttpStatusCode, val error: String, val description: String) : RuntimeException(description)

/** Inventario de endpoints (evidencia del criterio 3 de la ERSo 002: nada fuera de OpenID4VCI / OpenID4VP / administración / operación). */
data class EndpointInfo(val method: String, val path: String, val kind: String, val standard: String, val auth: String)

class EndpointCatalog { val entries = mutableListOf<EndpointInfo>() }
