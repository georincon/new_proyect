package co.org.avance.ssi.didcore

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/**
 * Serialización determinista (claves ordenadas, sin espacios). Lo que se firma, se hashea y se sirve son
 * exactamente los mismos bytes, así "comparar contenido y hash" es una verificación exacta.
 */
object CanonicalJson {
    fun canonicalize(element: JsonElement): String = StringBuilder().also { write(element, it) }.toString()

    fun bytes(element: JsonElement): ByteArray = canonicalize(element).toByteArray(Charsets.UTF_8)

    /** "sha256:<hex>" del contenido canónico. */
    fun hash(element: JsonElement): String = sha256(bytes(element))

    fun sha256(bytes: ByteArray): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun write(e: JsonElement, out: StringBuilder) {
        when (e) {
            is JsonNull -> out.append("null")
            is JsonPrimitive -> out.append(e.toString())
            is JsonArray -> {
                out.append('[')
                e.forEachIndexed { i, v -> if (i > 0) out.append(','); write(v, out) }
                out.append(']')
            }
            is JsonObject -> {
                out.append('{')
                e.entries.sortedBy { it.key }.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')
                    out.append(JsonPrimitive(k).toString()).append(':')
                    write(v, out)
                }
                out.append('}')
            }
        }
    }
}
