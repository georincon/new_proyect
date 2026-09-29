package co.org.avance.ssi.didcore

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Construye un DID Document mínimo con SOLO material público (ERSo 005 paso 2). */
object DidDocumentBuilder {
    const val MULTIKEY_CONTEXT = "https://w3id.org/security/multikey/v1"

    fun build(
        did: String,
        publicKeyMultibase: String,
        keyFragment: String = "key-1",
        services: List<Triple<String, String, String>> = emptyList(), // (fragmento, tipo, url https)
    ): JsonObject {
        val keyId = "$did#$keyFragment"
        return buildJsonObject {
            put("@context", JsonArray(listOf(JsonPrimitive("https://www.w3.org/ns/did/v1"), JsonPrimitive(MULTIKEY_CONTEXT))))
            put("id", JsonPrimitive(did))
            put("verificationMethod", JsonArray(listOf(buildJsonObject {
                put("id", JsonPrimitive(keyId))
                put("type", JsonPrimitive("Multikey"))
                put("controller", JsonPrimitive(did))
                put("publicKeyMultibase", JsonPrimitive(publicKeyMultibase))
            })))
            put("authentication", JsonArray(listOf(JsonPrimitive(keyId))))
            put("assertionMethod", JsonArray(listOf(JsonPrimitive(keyId))))
            if (services.isNotEmpty()) {
                put("service", JsonArray(services.map { (frag, type, url) ->
                    buildJsonObject {
                        put("id", JsonPrimitive("$did#$frag"))
                        put("type", JsonPrimitive(type))
                        put("serviceEndpoint", JsonPrimitive(url))
                    }
                }))
            }
        }
    }
}
