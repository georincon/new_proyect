package co.org.avance.ssi.didcore

class InvalidDidException(message: String) : IllegalArgumentException(message)

/** did:web:<dominio[%3Apuerto]>[:segmento]*  ->  https://<dominio>/(.well-known|segmento/...)/did.json */
data class DidWebId(val host: String, val path: List<String>) {
    /** Ruta relativa del recurso en el servidor web (la "forma del registro" para did:web). */
    val relativePath: String
        get() = if (path.isEmpty()) "/.well-known/did.json" else "/" + path.joinToString("/") + "/did.json"

    /** Espacio de nombres = ruta del DID (p. ej. "entidades/avance"); vacío para el DID raíz del dominio. */
    val namespacePath: String get() = path.joinToString("/")
}

object DidWeb {
    private val SEGMENT = Regex("^[A-Za-z0-9._-]+$")
    private val HOST = Regex("^[A-Za-z0-9.-]+(%3[Aa][0-9]{1,5})?$")
    const val PREFIX = "did:web:"

    /** Dominio en minúsculas; el separador de puerto se conserva como %3A. */
    private fun normalizeHost(host: String) = host.lowercase().replace("%3a", "%3A")

    fun parse(did: String): DidWebId {
        if (!did.startsWith(PREFIX)) throw InvalidDidException("El DID debe usar el método did:web")
        val parts = did.removePrefix(PREFIX).split(":")
        val host = parts.first()
        if (!HOST.matches(host)) throw InvalidDidException("Dominio inválido en did:web (los puertos van como %3A)")
        val path = parts.drop(1)
        if (path.any { !SEGMENT.matches(it) || it == "." || it == ".." }) {
            throw InvalidDidException("Segmento de ruta inválido en did:web")
        }
        return DidWebId(normalizeHost(host), path)
    }

    fun of(host: String, path: List<String>): String = PREFIX + normalizeHost(host) + path.joinToString("") { ":$it" }

    /** Con `scheme` = "https" se obtiene la URL normativa. */
    fun url(did: String, scheme: String = "https"): String {
        val id = parse(did)
        return "$scheme://${id.host.replace("%3A", ":")}${id.relativePath}"
    }

    /** Camino inverso: dominio + segmentos de la URL solicitada -> DID. Devuelve null si no termina en did.json. */
    fun fromUrlPath(host: String, segments: List<String>): String? {
        if (segments.lastOrNull() != "did.json") return null
        val dirs = segments.dropLast(1)
        return try {
            val path = if (dirs == listOf(".well-known")) emptyList() else dirs
            of(host, path).also { parse(it) }
        } catch (e: InvalidDidException) {
            null
        }
    }
}
