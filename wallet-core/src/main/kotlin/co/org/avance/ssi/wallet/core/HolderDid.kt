package co.org.avance.ssi.wallet.core

import co.org.avance.ssi.didcore.ConformanceReport
import co.org.avance.ssi.didcore.DidConformance
import co.org.avance.ssi.didcore.DidDocumentBuilder
import co.org.avance.ssi.didcore.DidWeb
import co.org.avance.ssi.didcore.Jws
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.Clock

/** Registro de generación (ERSo 003, criterio 1). Solo material público: es seguro conservarlo, enviarlo y auditarlo. */
@Serializable
data class GenerationRecord(
    val did: String,
    val keyAlias: String,
    val publicKeyMultibase: String,
    val thumbprint: String,
    val protectionLevel: String,
    val keyOrigin: String,
    val exportable: Boolean,
    val custodian: String,
    val conformant: Boolean,
    val generatedAt: String,
)

class HolderIdentity(val did: String, val document: JsonObject, val record: GenerationRecord, val conformance: ConformanceReport)

class DidNotConformantException(val report: ConformanceReport) :
    IllegalStateException("El DID Document no es conforme; no puede publicarse: " + report.failed.joinToString { it.id })

/**
 * SSI SDK (lado titular): crea el DID del titular EN LA CARTERA (ERSo 2026-003).
 *
 * Derivación del identificador (decisión del proyecto, para did:web): `did:web:<dominio>:titulares:<huella RFC 7638 de la clave pública>`.
 * Es determinista, no contiene datos civiles y no se puede colisionar sin la clave. Requiere publicarse en el VDR solo si se
 * quiere que sea resoluble públicamente; la creación en sí es local y no depende de ningún registro.
 */
class HolderDidService(
    private val custodian: KeyCustodian,
    private val domain: String,
    private val namespaceRoot: String = "titulares",
    private val clock: Clock = Clock.systemUTC(),
) {
    fun create(alias: String = "holder-1", policy: KeyPolicy = KeyPolicy(), attestationChallenge: ByteArray? = null): HolderIdentity {
        val key = custodian.generate(alias, policy, attestationChallenge)                                  // 1) la clave nace dentro del custodio
        val did = DidWeb.of(domain, listOf(namespaceRoot, key.thumbprint))                                  // 2) identificador derivado de la clave pública
        val document = DidDocumentBuilder.build(did, key.publicKeyMultibase)                                // 3-4) Multikey + relaciones de verificación
        val report = DidConformance.check(document, did, key.publicKeyMultibase)                            // 6) validación previa a cualquier publicación
        if (!report.conformant) { custodian.delete(alias); throw DidNotConformantException(report) }
        val record = GenerationRecord(did, alias, key.publicKeyMultibase, key.thumbprint, key.protectionLevel, key.origin, key.exportable,
            custodian.name, report.conformant, clock.instant().toString())
        return HolderIdentity(did, document, record, report)
    }

    /** Firma un contenido con la clave del DID: el JWS lo arma el SDK, la firma la hace el custodio. */
    fun sign(identity: HolderIdentity, payload: ByteArray, typ: String? = null): String =
        Jws.signWith("${identity.did}#key-1", payload, typ) { custodian.sign(identity.record.keyAlias, it) }
}
