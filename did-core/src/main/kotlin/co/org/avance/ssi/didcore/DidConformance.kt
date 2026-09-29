package co.org.avance.ssi.didcore

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class ConformanceCheck(val id: String, val requirement: String, val reference: String, val passed: Boolean, val detail: String = "")

@Serializable
data class ConformanceReport(val did: String?, val checks: List<ConformanceCheck>) {
    val conformant: Boolean get() = checks.all { it.passed }
    val failed: List<ConformanceCheck> get() = checks.filterNot { it.passed }
}

/**
 * Informe de conformidad ESTRUCTURAL de un DID Document (ERSo 2026-003, paso 6 y criterio 2).
 * Comprueba el modelo de datos (W3C DIDs) y las condiciones de la ERSo; NO valida firmas ni resuelve nada por red.
 * Las referencias de sección apuntan a W3C DID Core / DIDs v1.1 y deben confirmarse contra la versión vigente (ver docs/VERSIONES-NORMATIVAS.md).
 */
object DidConformance {
    private val CODE_TO_CHECK = mapOf(
        "MISSING_ID" to "C01", "INVALID_ID" to "C01", "ID_MISMATCH" to "C01",
        "INVALID_CONTEXT" to "C02",
        "INVALID_VERIFICATION_METHOD" to "C03", "DUPLICATE_ID" to "C03", "UNSUPPORTED_KEY_TYPE" to "C04", "INVALID_KEY" to "C04",
        "UNAUTHORIZED_CONTROLLER" to "C05", "INVALID_CONTROLLER" to "C05",
        "INVALID_RELATIONSHIP" to "C06", "DANGLING_REFERENCE" to "C06", "MISSING_AUTHENTICATION" to "C06",
        "PRIVATE_KEY_MATERIAL" to "C08", "CIVIL_DATA" to "C09",
        "UNKNOWN_PROPERTY" to "C10", "INVALID_SERVICE" to "C10", "TOO_LARGE" to "C10",
    )

    /**
     * @param expectedDid DID que el documento debe describir.
     * @param holderPublicKeyMultibase clave pública del titular: `authentication` y `assertionMethod` deben apuntar a ella (criterio 3).
     */
    fun check(doc: JsonObject, expectedDid: String?, holderPublicKeyMultibase: String? = null): ConformanceReport {
        val violations = DidDocumentValidator.validate(doc, expectedDid, Profile.PUBLISHER)
        fun failures(id: String) = violations.filter { CODE_TO_CHECK[it.code] == id }.joinToString("; ") { "${it.code}: ${it.message}" }
        fun c(id: String, req: String, ref: String) = failures(id).let { ConformanceCheck(id, req, ref, it.isEmpty(), it) }

        val checks = mutableListOf(
            c("C01", "El 'id' es un DID sintácticamente válido y coincide con el DID esperado", "DID Core §3.1 (sintaxis) y §5.1 (id)"),
            c("C02", "El primer valor de @context es el contexto DID de W3C", "DID Core §4.1 (@context)"),
            c("C03", "Cada método de verificación tiene id (URL DID con fragmento del propio documento), type y controller", "DID Core §5.2 (verification methods)"),
            c("C04", "Cada clave es Multikey con clave pública P-256 válida (multibase 'z', multicodec 0x1200)", "Controlled Identifiers: Multikey"),
            c("C05", "Los controladores son el propio DID (sin controladores ajenos)", "DID Core §5.1.2 (controller)"),
            c("C06", "Las relaciones de verificación son arreglos que referencian métodos existentes, y hay al menos una clave en authentication", "DID Core §5.3 (verification relationships)"),
            c("C08", "El documento no contiene material de clave privada", "ERSo 003 (condición del proceso)"),
            c("C09", "El documento no contiene credenciales personales ni datos civiles", "ERSo 003 (condición del proceso)"),
            c("C10", "Solo propiedades registradas del modelo de datos DID: sin extensiones propietarias", "DID Core §4 y DID Spec Registries"),
        )
        if (holderPublicKeyMultibase != null) {
            val bound = boundTo(doc, "authentication", holderPublicKeyMultibase) && boundTo(doc, "assertionMethod", holderPublicKeyMultibase)
            checks += ConformanceCheck("C07", "authentication y assertionMethod apuntan a la clave pública Multikey del titular", "ERSo 003 criterio 3; DID Core §5.3",
                bound, if (bound) "" else "Alguna relación no referencia la clave del titular")
        }
        return ConformanceReport(expectedDid ?: (doc["id"] as? JsonPrimitive)?.contentOrNull, checks.sortedBy { it.id })
    }

    private fun boundTo(doc: JsonObject, relationship: String, multibase: String): Boolean {
        val refs = (doc[relationship] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        if (refs.isEmpty()) return false
        val methods = (doc["verificationMethod"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return refs.all { ref -> methods.any { (it["id"] as? JsonPrimitive)?.contentOrNull == ref && (it["publicKeyMultibase"] as? JsonPrimitive)?.contentOrNull == multibase } }
    }
}
