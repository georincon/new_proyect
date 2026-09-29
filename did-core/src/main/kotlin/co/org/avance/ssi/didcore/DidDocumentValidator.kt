package co.org.avance.ssi.didcore

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class Violation(val code: String, val message: String)

enum class Profile {
    /** Lo que el productor/registro acepta publicar: estricto (lista blanca, sin datos civiles ni claves privadas). */
    PUBLISHER,

    /** Lo que el consumidor exige para confiar: conformidad estructural del modelo de datos DID + sin claves privadas. */
    CONSUMER,
}

object DidDocumentValidator {
    val RELATIONSHIPS = listOf("authentication", "assertionMethod", "keyAgreement", "capabilityInvocation", "capabilityDelegation")
    private val CONTEXTS = setOf("https://www.w3.org/ns/did/v1", "https://www.w3.org/ns/did/v1.1")
    private val PUBLISHER_ALLOWED = setOf("@context", "id", "controller", "verificationMethod", "service") + RELATIONSHIPS
    private val PRIVATE_KEY_FIELDS = setOf(
        "d", "privatekeyjwk", "privatekeymultibase", "secretkeymultibase", "privatekeybase58", "privatekeypem", "secretkey", "privatekey",
    )
    private val CIVIL_FIELDS = setOf(
        "credentialsubject", "name", "givenname", "familyname", "fullname", "birthdate", "dateofbirth", "documentnumber", "nationalid",
        "idnumber", "email", "phone", "telephone", "address", "gender", "nationality", "cedula", "nit",
    )
    const val MAX_BYTES = 64 * 1024

    fun validate(
        doc: JsonObject,
        expectedDid: String?,
        profile: Profile,
        allowedControllers: Set<String> = emptySet(),
    ): List<Violation> {
        val v = mutableListOf<Violation>()
        fun add(code: String, msg: String) { v += Violation(code, msg) }

        // 1) Material privado: se revisa siempre, en ambos perfiles, y en cualquier profundidad.
        scanKeys(doc) { key, path ->
            if (key.lowercase() in PRIVATE_KEY_FIELDS) add("PRIVATE_KEY_MATERIAL", "Campo prohibido con posible clave privada en $path")
        }

        // 2) id
        val id = (doc["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (id == null) add("MISSING_ID", "El documento no tiene 'id'")
        else {
            try { DidWeb.parse(id) } catch (e: InvalidDidException) { add("INVALID_ID", "id inválido: ${e.message}") }
            if (expectedDid != null && id != expectedDid) add("ID_MISMATCH", "El id '$id' no coincide con el DID esperado '$expectedDid'")
        }

        // 3) @context
        val ctx = doc["@context"]
        val first = when (ctx) {
            is JsonPrimitive -> ctx.contentOrNull
            is JsonArray -> (ctx.firstOrNull() as? JsonPrimitive)?.contentOrNull
            else -> null
        }
        if (first == null || first !in CONTEXTS) add("INVALID_CONTEXT", "El primer @context debe ser el contexto DID de W3C")

        // 4) verificationMethod
        val methods = doc["verificationMethod"]
        val methodIds = mutableSetOf<String>()
        if (methods != null && methods !is JsonArray) add("INVALID_VERIFICATION_METHOD", "verificationMethod debe ser un arreglo")
        (methods as? JsonArray)?.forEachIndexed { i, m ->
            val obj = m as? JsonObject
            if (obj == null) { add("INVALID_VERIFICATION_METHOD", "verificationMethod[$i] debe ser un objeto"); return@forEachIndexed }
            checkMethod(obj, id, profile, allowedControllers, methodIds, ::add)
        }

        // 5) relaciones de verificación: referencias por cadena deben existir; objetos embebidos se validan
        for (rel in RELATIONSHIPS) {
            val value = doc[rel] ?: continue
            if (value !is JsonArray) { add("INVALID_RELATIONSHIP", "$rel debe ser un arreglo"); continue }
            value.forEach { entry ->
                when {
                    entry is JsonPrimitive && entry.isString ->
                        if (entry.content !in methodIds) add("DANGLING_REFERENCE", "$rel referencia '${entry.content}' que no está en verificationMethod")
                    entry is JsonObject -> checkMethod(entry, id, profile, allowedControllers, mutableSetOf(), ::add).also {
                        (entry["id"] as? JsonPrimitive)?.contentOrNull?.let(methodIds::add)
                    }
                    else -> add("INVALID_RELATIONSHIP", "$rel contiene una entrada inválida")
                }
            }
        }

        // 6) service
        (doc["service"] as? JsonArray)?.forEachIndexed { i, s ->
            val o = s as? JsonObject
            val endpoint = (o?.get("serviceEndpoint") as? JsonPrimitive)?.contentOrNull
            if (o == null || (o["id"] as? JsonPrimitive)?.contentOrNull == null || (o["type"] == null)) {
                add("INVALID_SERVICE", "service[$i] requiere id, type y serviceEndpoint")
            } else if (profile == Profile.PUBLISHER && (endpoint == null || !endpoint.startsWith("https://"))) {
                add("INVALID_SERVICE", "service[$i].serviceEndpoint debe ser una URL https")
            }
        }
        if (doc["service"] != null && doc["service"] !is JsonArray) add("INVALID_SERVICE", "service debe ser un arreglo")

        if (profile == Profile.PUBLISHER) {
            // 7) datos civiles y lista blanca
            scanKeys(doc) { key, path ->
                if (key.lowercase() in CIVIL_FIELDS) add("CIVIL_DATA", "Campo de datos civiles no permitido en $path")
            }
            doc.keys.filter { it !in PUBLISHER_ALLOWED }.forEach { add("UNKNOWN_PROPERTY", "Propiedad no permitida en el perfil de publicación: '$it'") }
            // 8) controller
            val ctl = doc["controller"]
            val controllers = when (ctl) {
                null -> emptyList()
                is JsonPrimitive -> listOf(ctl.content)
                is JsonArray -> ctl.map { it.jsonPrimitive.content }
                else -> { add("INVALID_CONTROLLER", "controller inválido"); emptyList() }
            }
            controllers.filter { it != id && it !in allowedControllers }.forEach { add("UNAUTHORIZED_CONTROLLER", "Controlador no autorizado: $it") }
            // 9) debe haber al menos una clave de autenticación (necesaria para la prueba de posesión)
            if ((doc["authentication"] as? JsonArray).isNullOrEmpty()) add("MISSING_AUTHENTICATION", "El documento debe declarar al menos una clave en 'authentication'")
            if (doc.toString().toByteArray().size > MAX_BYTES) add("TOO_LARGE", "El documento supera $MAX_BYTES bytes")
        }
        return v
    }

    private fun checkMethod(
        m: JsonObject,
        did: String?,
        profile: Profile,
        allowedControllers: Set<String>,
        ids: MutableSet<String>,
        add: (String, String) -> Unit,
    ) {
        val mid = (m["id"] as? JsonPrimitive)?.contentOrNull
        if (mid == null) { add("INVALID_VERIFICATION_METHOD", "Un método de verificación no tiene id"); return }
        if (did != null && !mid.startsWith("$did#")) add("INVALID_VERIFICATION_METHOD", "El id '$mid' debe ser una URL DID con fragmento del propio documento")
        if (!ids.add(mid)) add("DUPLICATE_ID", "id de método duplicado: $mid")
        if ((m["type"] as? JsonPrimitive)?.contentOrNull != "Multikey") add("UNSUPPORTED_KEY_TYPE", "$mid: solo se admite type=Multikey")
        val controller = (m["controller"] as? JsonPrimitive)?.contentOrNull
        if (controller == null) add("INVALID_VERIFICATION_METHOD", "$mid: falta controller")
        else if (profile == Profile.PUBLISHER && controller != did && controller !in allowedControllers) {
            add("UNAUTHORIZED_CONTROLLER", "$mid: controlador no autorizado ($controller)")
        }
        val pk = (m["publicKeyMultibase"] as? JsonPrimitive)?.contentOrNull
        if (pk == null) add("INVALID_KEY", "$mid: falta publicKeyMultibase")
        else try { Multikey.decodeP256(pk) } catch (e: InvalidKeyException) { add("INVALID_KEY", "$mid: ${e.message}") }
    }

    private fun scanKeys(e: JsonElement, path: String = "$", onKey: (String, String) -> Unit) {
        when (e) {
            is JsonObject -> e.forEach { (k, v) -> onKey(k, "$path.$k"); scanKeys(v, "$path.$k", onKey) }
            is JsonArray -> e.forEachIndexed { i, v -> scanKeys(v, "$path[$i]", onKey) }
            else -> Unit
        }
    }
}
