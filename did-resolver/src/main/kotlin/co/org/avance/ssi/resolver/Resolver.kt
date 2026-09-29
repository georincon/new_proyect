package co.org.avance.ssi.resolver

import co.org.avance.ssi.didcore.CanonicalJson
import co.org.avance.ssi.didcore.DidDocumentValidator
import co.org.avance.ssi.didcore.DidWeb
import co.org.avance.ssi.didcore.InvalidDidException
import co.org.avance.ssi.didcore.Profile
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Metadatos de resolución (DID Resolution v1): `error` usa los códigos normativos (invalidDid, notFound, ...). */
data class ResolutionMetadata(
    val error: String? = null,
    val message: String? = null,
    val contentType: String? = null,
    val url: String? = null,
    val violations: List<String> = emptyList(),
)

data class DocumentMetadata(val deactivated: Boolean = false, val versionId: String? = null, val contentHash: String? = null)

data class ResolutionResult(
    val didDocument: JsonObject?,
    val resolutionMetadata: ResolutionMetadata,
    val documentMetadata: DocumentMetadata = DocumentMetadata(),
) {
    val isSuccess: Boolean get() = didDocument != null && resolutionMetadata.error == null
}

data class DereferenceResult(val contentStream: JsonObject?, val error: String? = null, val message: String? = null)

/** Contrato del consumidor: solo LEER. No hay operaciones de escritura ni de publicación en esta interfaz (ERSo 007, criterio 4). */
interface DidResolver {
    /** Resolver: DID -> DID Document. */
    suspend fun resolve(did: String): ResolutionResult

    /** Dereferenciar: DID URL (con fragmento) -> recurso concreto dentro del documento (p. ej. un método de verificación). */
    suspend fun dereference(didUrl: String): DereferenceResult
}

class DidWebResolver(
    private val http: HttpClient = defaultClient(),
    private val options: Options = Options(),
) : DidResolver {

    /** `insecureHosts`: solo para laboratorio/pruebas; por defecto todo se resuelve por HTTPS. */
    data class Options(val insecureHosts: Set<String> = emptySet(), val maxBytes: Int = 128 * 1024)

    override suspend fun resolve(did: String): ResolutionResult {
        val id = try {
            DidWeb.parse(did)
        } catch (e: InvalidDidException) {
            return failure("invalidDid", e.message)
        }
        val hostOnly = id.host.substringBefore("%3A")
        val scheme = if (hostOnly in options.insecureHosts) "http" else "https"
        val url = DidWeb.url(did, scheme)

        val response = try {
            http.get(url) { header(HttpHeaders.Accept, "application/did+json, application/did+ld+json, application/json") }
        } catch (e: Exception) {
            return failure("internalError", "No se pudo leer $url: ${e.javaClass.simpleName}", url)
        }
        when {
            response.status == HttpStatusCode.NotFound -> return failure("notFound", "El DID no existe en $url", url)
            response.status == HttpStatusCode.Gone -> return ResolutionResult(
                null, ResolutionMetadata(url = url, message = "DID desactivado"), DocumentMetadata(deactivated = true),
            )
            response.status.value in 300..399 -> return failure("internalError", "Redirecciones no permitidas (${response.status.value})", url)
            response.status != HttpStatusCode.OK -> return failure("internalError", "Estado HTTP inesperado ${response.status.value}", url)
        }
        val contentType = response.headers[HttpHeaders.ContentType]?.substringBefore(";")?.trim()?.lowercase()
        if (contentType !in setOf("application/did+json", "application/did+ld+json", "application/json")) {
            return failure("representationNotSupported", "Content-Type no soportado: $contentType", url)
        }
        val bytes = response.bodyAsBytes()
        if (bytes.size > options.maxBytes) return failure("invalidDidDocumentLength", "El documento supera ${options.maxBytes} bytes", url)
        val doc = try {
            Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            return failure("invalidDidDocument", "El contenido no es un objeto JSON", url)
        }
        val violations = DidDocumentValidator.validate(doc, did, Profile.CONSUMER)
        if (violations.isNotEmpty()) {
            return ResolutionResult(
                null,
                ResolutionMetadata("invalidDidDocument", violations.first().message, contentType, url, violations.map { it.code }),
            )
        }
        val version = response.headers[HttpHeaders.ETag]?.trim('"')
        return ResolutionResult(
            doc,
            ResolutionMetadata(contentType = contentType, url = url),
            DocumentMetadata(versionId = version, contentHash = CanonicalJson.sha256(bytes)),
        )
    }

    override suspend fun dereference(didUrl: String): DereferenceResult {
        if (!didUrl.contains('#')) return DereferenceResult(null, "invalidDidUrl", "La DID URL no tiene fragmento")
        val did = didUrl.substringBefore('#')
        val result = resolve(did)
        val doc = result.didDocument ?: return DereferenceResult(null, result.resolutionMetadata.error ?: "notFound", result.resolutionMetadata.message)
        val candidates = mutableListOf<JsonObject>()
        (doc["verificationMethod"] as? JsonArray)?.forEach { (it as? JsonObject)?.let(candidates::add) }
        for (rel in listOf("authentication", "assertionMethod", "keyAgreement", "capabilityInvocation", "capabilityDelegation")) {
            (doc[rel] as? JsonArray)?.forEach { (it as? JsonObject)?.let(candidates::add) }
        }
        (doc["service"] as? JsonArray)?.forEach { (it as? JsonObject)?.let(candidates::add) }
        val found = candidates.firstOrNull { (it["id"] as? JsonPrimitive)?.contentOrNull == didUrl }
        return found?.let { DereferenceResult(it) } ?: DereferenceResult(null, "notFound", "No existe $didUrl en el documento")
    }

    private fun failure(error: String, message: String?, url: String? = null) =
        ResolutionResult(null, ResolutionMetadata(error = error, message = message, url = url))

    companion object {
        /** Cliente de solo lectura: sin redirecciones, con tiempos de espera. */
        fun defaultClient(): HttpClient = HttpClient(CIO) {
            followRedirects = false
            install(HttpRedirect) { checkHttpMethod = true }
            install(HttpTimeout) { requestTimeoutMillis = 5_000; connectTimeoutMillis = 3_000 }
            expectSuccess = false
        }
    }
}
