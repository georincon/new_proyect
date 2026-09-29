package co.org.avance.ssi.vdr

import co.org.avance.ssi.didcore.CanonicalJson
import co.org.avance.ssi.didcore.DidDocumentValidator
import co.org.avance.ssi.didcore.DidWeb
import co.org.avance.ssi.didcore.DidWebId
import co.org.avance.ssi.didcore.InvalidDidException
import co.org.avance.ssi.didcore.InvalidKeyException
import co.org.avance.ssi.didcore.Jws
import co.org.avance.ssi.didcore.JwsException
import co.org.avance.ssi.didcore.Multikey
import co.org.avance.ssi.didcore.Profile
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.postgresql.util.PSQLException
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Adaptador del método DID (ERSo 004, paso 2): la FORMA del registro depende del método.
 * Para did:web, el registro es un servicio web indexado por identificador y la ruta pública sale del propio DID.
 */
interface DidMethodAdapter {
    val method: String
    fun parse(did: String): DidWebId
    fun namespaceOf(did: String): String
    fun publicPath(did: String): String
    fun didFromUrlPath(host: String, segments: List<String>): String?
    fun didForNamespace(host: String, namespace: String): String
}

class DidWebAdapter : DidMethodAdapter {
    override val method = "web"
    override fun parse(did: String) = DidWeb.parse(did)
    override fun namespaceOf(did: String) = DidWeb.parse(did).namespacePath
    override fun publicPath(did: String) = DidWeb.parse(did).relativePath
    override fun didFromUrlPath(host: String, segments: List<String>) = DidWeb.fromUrlPath(host, segments)
    override fun didForNamespace(host: String, namespace: String) = DidWeb.of(host, namespace.split("/"))
}

class RegistryService(
    private val cfg: AppConfig,
    private val db: Db,
    private val store: RegistryStore,
    private val reader: PublicUrlReader,
    private val adapter: DidMethodAdapter = DidWebAdapter(),
) {
    companion object {
        val DEFAULT_PROFILE: JsonObject = buildJsonObject {
            put("verificationMethodTypes", JsonArray(listOf(JsonPrimitive("Multikey"))))
            put("relationships", JsonArray(listOf(JsonPrimitive("authentication"), JsonPrimitive("assertionMethod"))))
            put("maxKeys", JsonPrimitive(5))
        }
        private val NAMESPACE = Regex("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*(/\\*)?$")
    }

    private val random = SecureRandom()
    val audience: String = "vdr:${cfg.domain}:did-operation"

    // ------------------------------------------------------------------ arranque
    fun bootstrap() = db.tx { c ->
        for (cl in cfg.clients) {
            store.upsertAccount(c, cl.clientId, cl.displayName)
            for (ns in cl.namespaces) {
                require(NAMESPACE.matches(ns) && !ns.startsWith(".well-known")) { "Namespace inválido: $ns" }
                if (store.reserveNamespace(c, ns, cl.clientId, DEFAULT_PROFILE.toString())) {
                    store.audit(c, "system", "NAMESPACE_RESERVED", adapter.didForNamespace(cfg.domain, ns), detail = buildJsonObject { put("owner", JsonPrimitive(cl.clientId)) })
                }
            }
        }
    }

    // ------------------------------------------------------------------ lectura pública
    suspend fun publicDocument(did: String): PublicDoc = withContext(Dispatchers.IO) {
        db.tx { c ->
            val row = store.findDid(c, did) ?: return@tx PublicDoc.NotFound
            if (row.status == "DEACTIVATED") return@tx PublicDoc.Gone
            val v = store.version(c, did, row.currentVersion)!!
            PublicDoc.Found(v.document!!.toByteArray(Charsets.UTF_8), v.version, v.hash)
        }
    }

    fun didFromUrlPath(segments: List<String>): String? = adapter.didFromUrlPath(cfg.domain, segments)

    // ------------------------------------------------------------------ desafíos (ERSo 008, criterios 1 y 2)
    suspend fun issueChallenge(clientId: String, didStr: String, purposeStr: String?, channelRestricted: Boolean): ChallengeView = withContext(Dispatchers.IO) {
        val purpose = Purpose.parse(purposeStr) ?: throw ApiException(HttpStatusCode.BadRequest, "INVALID_PURPOSE", "purpose debe ser CREATE, UPDATE o DEACTIVATE")
        db.tx { c ->
            val failures = preconditionFailures(c, clientId, didStr, channelRestricted)
            if (failures.isNotEmpty()) {
                store.audit(c, clientId, "CHALLENGE_DENIED", didStr, detail = buildJsonObject { put("failures", JsonArray(failures.map(::JsonPrimitive))); put("purpose", JsonPrimitive(purpose.name)) })
                c.commit() // la denegación debe quedar registrada aunque se lance la excepción
                throw ApiException(HttpStatusCode.PreconditionFailed, "PRECONDITION_FAILED", "No se emite desafío: precondiciones incumplidas", failures)
            }
            val row = store.findDid(c, didStr)
            when {
                row?.status == "DEACTIVATED" -> throw ApiException(HttpStatusCode.Conflict, "TERMINAL_STATE", "El historial está desactivado; se requiere una nueva instancia (otro namespace)")
                purpose == Purpose.CREATE && row != null -> throw ApiException(HttpStatusCode.Conflict, "ALREADY_EXISTS", "El DID ya existe (versión ${row.currentVersion}); use UPDATE")
                purpose != Purpose.CREATE && row == null -> throw ApiException(HttpStatusCode.NotFound, "NOT_FOUND", "El DID no existe; use CREATE")
            }
            val id = UUID.randomUUID()
            val nonce = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
            val expires = store.insertChallenge(c, id, nonce, didStr, purpose.name, audience, clientId, cfg.challengeTtlSeconds)
            store.audit(c, clientId, "CHALLENGE_ISSUED", didStr, detail = buildJsonObject {
                put("challengeId", JsonPrimitive(id.toString())); put("purpose", JsonPrimitive(purpose.name)); put("audience", JsonPrimitive(audience)); put("expiresAt", JsonPrimitive(expires))
            })
            ChallengeView(id.toString(), nonce, didStr, purpose.name, audience, expires)
        }
    }

    /** Precondiciones de ERSo 006/008: cuenta habilitada, namespace propio y reservado, perfil definido, canal restringido. */
    private fun preconditionFailures(c: java.sql.Connection, clientId: String, did: String, channelRestricted: Boolean): List<String> {
        val f = mutableListOf<String>()
        if (store.accountEnabled(c, clientId) != true) f += "ACCOUNT_MISSING_OR_DISABLED"
        if (!channelRestricted) f += "WRITE_CHANNEL_NOT_RESTRICTED"
        val id = try { adapter.parse(did) } catch (e: InvalidDidException) { f += "DID_INVALID"; return f }
        if (id.host != cfg.domain.lowercase()) f += "DID_DOMAIN_MISMATCH"
        val ns = id.namespacePath
        val owner = if (ns.isEmpty()) null else store.namespaceOwner(c, ns)
        if (owner == null) f += "NAMESPACE_NOT_RESERVED"
        else {
            if (owner != clientId) f += "NAMESPACE_NOT_OWNED"
            val types = (store.namespaceProfile(c, ns)?.get("verificationMethodTypes") as? JsonArray)
            if (types.isNullOrEmpty()) f += "PROFILE_UNDEFINED"
        }
        return f
    }

    // ------------------------------------------------------------------ escritura (ERSo 008 criterios 3-6; ERSo 005/006)
    suspend fun write(
        clientId: String,
        didStr: String,
        purpose: Purpose,
        expectedVersion: Int,
        idempotencyKey: String,
        challengeId: String,
        document: JsonObject?,
        proof: String,
    ): Pair<OperationView, HttpStatusCode> {
        val outcome = withContext(Dispatchers.IO) { writeTx(clientId, didStr, purpose, expectedVersion, idempotencyKey, challengeId, document, proof) }
        if (outcome.replayed) return outcome.view to statusFor(outcome.view, purpose)
        val confirmed = confirm(outcome.view.operationId, outcome.view.did, outcome.view.hash, purpose, outcome.view.version)
        val view = outcome.view.copy(status = confirmed)
        return view to statusFor(view, purpose)
    }

    private fun statusFor(v: OperationView, purpose: Purpose) = when {
        v.status != "CONFIRMED" -> HttpStatusCode.Accepted
        purpose == Purpose.CREATE && !v.replayed -> HttpStatusCode.Created
        else -> HttpStatusCode.OK
    }

    private class TxOutcome(val view: OperationView, val replayed: Boolean)

    private fun writeTx(
        clientId: String, didStr: String, purpose: Purpose, expectedVersion: Int, idemKey: String,
        challengeId: String, document: JsonObject?, proof: String,
    ): TxOutcome {
        // 1) el DID debe estar en un namespace de la entidad (autorización por namespace)
        val id = try { adapter.parse(didStr) } catch (e: InvalidDidException) { throw ApiException(HttpStatusCode.BadRequest, "DID_INVALID", e.message ?: "DID inválido") }
        if (id.host != cfg.domain.lowercase()) throw ApiException(HttpStatusCode.UnprocessableEntity, "DID_DOMAIN_MISMATCH", "El DID no pertenece al dominio ${cfg.domain}")
        val ns = id.namespacePath
        db.tx { c -> if (store.namespaceOwner(c, ns) != clientId) throw denied(c, clientId, didStr, "NAMESPACE_NOT_OWNED") }

        // 2) documento: el perfil de publicación exige id igual al DID, sin claves privadas ni datos civiles
        if (purpose == Purpose.DEACTIVATE) {
            if (document != null) throw ApiException(HttpStatusCode.BadRequest, "UNEXPECTED_DOCUMENT", "DEACTIVATE no lleva documento")
        } else {
            if (document == null) throw ApiException(HttpStatusCode.BadRequest, "MISSING_DOCUMENT", "Falta el documento")
            val violations = DidDocumentValidator.validate(document, didStr, Profile.PUBLISHER)
            if (violations.isNotEmpty()) {
                db.tx { c -> store.audit(c, clientId, "WRITE_REJECTED", didStr, detail = buildJsonObject { put("violations", JsonArray(violations.map { JsonPrimitive(it.code) })) }) }
                throw ApiException(HttpStatusCode.UnprocessableEntity, "INVALID_DOCUMENT", "El documento no cumple el perfil de publicación", violations.map { "${it.code}: ${it.message}" })
            }
        }
        val docHashRequested = document?.let(CanonicalJson::hash) ?: ""
        val requestHash = CanonicalJson.sha256("$purpose|$didStr|$expectedVersion|$docHashRequested".toByteArray())

        // 3) idempotencia: un reintento con la misma clave devuelve el resultado original SIN gastar otro desafío
        val previous = db.tx { c -> store.operationByIdempotency(c, didStr, idemKey) }
        if (previous != null) {
            if (previous.requestHash != requestHash) throw ApiException(HttpStatusCode.UnprocessableEntity, "IDEMPOTENCY_KEY_REUSED", "La clave de idempotencia ya se usó con otra solicitud")
            return TxOutcome(view(previous, replayed = true), replayed = true)
        }

        // 4) el desafío se consume de forma atómica (un solo uso) y debe corresponder a ESTA operación
        val uuid = try { UUID.fromString(challengeId) } catch (e: IllegalArgumentException) { throw ApiException(HttpStatusCode.BadRequest, "CHALLENGE_INVALID", "challengeId inválido") }
        val challenge = db.tx { c ->
            store.consumeChallenge(c, uuid) ?: throw denied(c, clientId, didStr, "CHALLENGE_INVALID_OR_USED", ApiException(HttpStatusCode.Forbidden, "CHALLENGE_INVALID", "Desafío inexistente, expirado o ya utilizado"))
        }
        if (challenge.clientId != clientId || challenge.did != didStr || challenge.purpose != purpose.name || challenge.audience != audience) {
            db.tx { c -> store.audit(c, clientId, "CHALLENGE_MISMATCH", didStr, detail = buildJsonObject { put("challengeId", JsonPrimitive(challenge.id)) }) }
            throw ApiException(HttpStatusCode.Forbidden, "CHALLENGE_MISMATCH", "El desafío no corresponde a esta operación (tipo, DID, audiencia o cuenta)")
        }

        // 5) transacción de escritura bajo bloqueo de la fila del DID
        return db.tx { c ->
            val row = store.findDid(c, didStr, lock = true)
            when {
                row?.status == "DEACTIVATED" -> throw ApiException(HttpStatusCode.Conflict, "TERMINAL_STATE", "El historial está desactivado; se requiere una nueva instancia")
                purpose == Purpose.CREATE && row != null -> throw ApiException(HttpStatusCode.Conflict, "VERSION_CONFLICT", "El DID ya existe (versión actual ${row.currentVersion})", listOf("currentVersion=${row.currentVersion}"))
                purpose != Purpose.CREATE && row == null -> throw ApiException(HttpStatusCode.NotFound, "NOT_FOUND", "El DID no existe")
            }
            val current = row?.currentVersion ?: 0
            if (expectedVersion != current) {
                store.audit(c, clientId, "VERSION_CONFLICT", didStr, current, buildJsonObject { put("expected", JsonPrimitive(expectedVersion)) })
                c.commit()
                throw ApiException(HttpStatusCode.PreconditionFailed, "VERSION_CONFLICT", "Versión esperada $expectedVersion, versión actual $current", listOf("currentVersion=$current"))
            }
            val currentVersionRow = row?.let { store.version(c, didStr, it.currentVersion)!! }
            val docHash = if (purpose == Purpose.DEACTIVATE) currentVersionRow!!.hash else docHashRequested

            // prueba de posesión: firma sobre (desafío + hash) con una clave de `authentication` vigente
            val keyDoc: JsonObject = if (purpose == Purpose.CREATE) document!! else Json.parseToJsonElement(currentVersionRow!!.document!!).jsonObject
            verifyProof(proof, didStr, keyDoc, expectedPayload(challenge, purpose, docHash)) { reason ->
                store.audit(c, clientId, "PROOF_REJECTED", didStr, current, buildJsonObject { put("reason", JsonPrimitive(reason)) }); c.commit()
            }

            val maxKeys = (store.namespaceProfile(c, ns)?.get("maxKeys") as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 5
            if ((document?.get("verificationMethod") as? JsonArray).orEmpty().size > maxKeys) {
                throw ApiException(HttpStatusCode.UnprocessableEntity, "PROFILE_VIOLATION", "El perfil del namespace admite hasta $maxKeys claves")
            }

            val newVersion = current + 1
            val operationId = UUID.randomUUID().toString()
            val text = document?.let(CanonicalJson::canonicalize)
            if (purpose == Purpose.CREATE) { store.materializeNamespace(c, ns); store.insertDid(c, didStr, ns) }
            store.insertVersion(c, VersionRow(didStr, newVersion, purpose.name, text, docHash, clientId, operationId, ""))
            store.updateDid(c, didStr, if (purpose == Purpose.DEACTIVATE) "DEACTIVATED" else "ACTIVE", newVersion)
            val publicUrl = cfg.publicBaseUrl + adapter.publicPath(didStr)
            store.insertOperation(c, OperationRow(operationId, didStr, purpose.name, clientId, "PENDING", newVersion, docHash, publicUrl, idemKey, requestHash, "", null))
            store.audit(c, clientId, "WRITE_${purpose.name}", didStr, newVersion, buildJsonObject {
                put("operationId", JsonPrimitive(operationId)); put("hash", JsonPrimitive(docHash)); put("expectedVersion", JsonPrimitive(expectedVersion)); put("idempotencyKey", JsonPrimitive(idemKey))
            })
            TxOutcome(OperationView(operationId, didStr, purpose.name, "PENDING", newVersion, docHash, publicUrl), replayed = false)
        }
    }

    private fun expectedPayload(ch: ChallengeRow, purpose: Purpose, docHash: String) = buildJsonObject {
        put("aud", JsonPrimitive(ch.audience)); put("challenge", JsonPrimitive(ch.nonce)); put("did", JsonPrimitive(ch.did))
        put("docHash", JsonPrimitive(docHash)); put("purpose", JsonPrimitive(purpose.name))
    }

    private fun verifyProof(proof: String, did: String, keyDoc: JsonObject, expected: JsonObject, onReject: (String) -> Unit) {
        fun reject(reason: String): Nothing { onReject(reason); throw ApiException(HttpStatusCode.Forbidden, "INVALID_PROOF", "Prueba de posesión inválida: $reason") }
        val parsed = try { Jws.parse(proof) } catch (e: JwsException) { reject(e.message ?: "mal formada") }
        val kid = try { parsed.kid } catch (e: JwsException) { reject("sin kid") }
        if (kid.substringBefore('#') != did) reject("el kid no pertenece al DID")
        val authorized = (keyDoc["authentication"] as? JsonArray).orEmpty().any { (it as? JsonPrimitive)?.contentOrNull == kid }
        if (!authorized) reject("la clave no está en authentication")
        val method = (keyDoc["verificationMethod"] as? JsonArray).orEmpty().map { it.jsonObject }.firstOrNull { (it["id"] as? JsonPrimitive)?.contentOrNull == kid } ?: reject("clave inexistente")
        val key = try { Multikey.decodeP256((method["publicKeyMultibase"] as? JsonPrimitive)?.contentOrNull ?: "") } catch (e: InvalidKeyException) { reject("clave inválida") }
        if (!Jws.verify(parsed, key)) reject("firma no válida")
        val payload = try { Json.parseToJsonElement(String(parsed.payload)).jsonObject } catch (e: Exception) { reject("payload no es JSON") }
        if (payload != expected) reject("el payload no corresponde al desafío/hash de la operación")
    }

    private fun denied(c: java.sql.Connection, clientId: String, did: String, reason: String, ex: ApiException? = null): ApiException {
        store.audit(c, clientId, "WRITE_DENIED", did, detail = buildJsonObject { put("reason", JsonPrimitive(reason)) })
        c.commit()
        return ex ?: ApiException(HttpStatusCode.Forbidden, reason, "La entidad no tiene permiso sobre este namespace")
    }

    private fun view(o: OperationRow, replayed: Boolean = false) = OperationView(o.id, o.did, o.purpose, o.status, o.version, o.hash, o.publicUrl, replayed)

    // ------------------------------------------------------------------ confirmación (ERSo 008 criterios 5 y 6)
    /** Lee la URL pública y compara hash; si no responde a tiempo, queda PENDING y NO se da por publicada. */
    private suspend fun confirm(operationId: String, did: String, hash: String, purpose: Purpose, version: Int): String {
        val url = cfg.confirmBaseUrl + adapter.publicPath(did)
        val (status, detail) = try {
            val read = withTimeout(cfg.confirmTimeoutMs) { reader.read(url) }
            val ok = if (purpose == Purpose.DEACTIVATE) read.status == 410 else read.status == 200 && CanonicalJson.sha256(read.body) == hash
            (if (ok) "CONFIRMED" else "PENDING") to (if (ok) "hash coincide" else "lectura estado=${read.status} sin coincidencia de hash")
        } catch (e: TimeoutCancellationException) {
            "PENDING" to "sin respuesta en plazo (${cfg.confirmTimeoutMs} ms)"
        } catch (e: Exception) {
            "PENDING" to "error de lectura: ${e.javaClass.simpleName}"
        }
        withContext(Dispatchers.IO) {
            db.tx { c ->
                if (status == "CONFIRMED") store.setOperationStatus(c, operationId, "CONFIRMED")
                store.audit(c, "system", if (status == "CONFIRMED") "PUBLICATION_CONFIRMED" else "PUBLICATION_PENDING", did, version, buildJsonObject {
                    put("operationId", JsonPrimitive(operationId)); put("url", JsonPrimitive(url)); put("detail", JsonPrimitive(detail))
                })
            }
        }
        return status
    }

    /** Reconciliación: relee lo efectivamente publicado y decide si la operación pendiente se confirma o quedó superada. */
    suspend fun reconcile(clientId: String, operationId: String, isAdmin: Boolean): OperationView {
        val op = withContext(Dispatchers.IO) { db.tx { c -> store.operation(c, operationId) } } ?: throw ApiException(HttpStatusCode.NotFound, "NOT_FOUND", "Operación inexistente")
        if (!isAdmin && op.clientId != clientId) throw ApiException(HttpStatusCode.Forbidden, "FORBIDDEN", "La operación pertenece a otra entidad")
        if (op.status != "PENDING") return view(op)
        val purpose = Purpose.parse(op.purpose)!!
        val status = confirm(op.id, op.did, op.hash, purpose, op.version)
        if (status == "CONFIRMED") return view(op).copy(status = status)
        val superseded = withContext(Dispatchers.IO) {
            db.tx { c ->
                val latest = store.findDid(c, op.did)?.currentVersion ?: op.version
                (latest > op.version).also { if (it) { store.setOperationStatus(c, op.id, "SUPERSEDED"); store.audit(c, "system", "OPERATION_SUPERSEDED", op.did, op.version) } }
            }
        }
        return view(op).copy(status = if (superseded) "SUPERSEDED" else "PENDING")
    }

    // ------------------------------------------------------------------ consultas
    fun assertNamespaceAccess(clientId: String, isAdmin: Boolean, did: String) {
        if (isAdmin) return
        val id = try { adapter.parse(did) } catch (e: InvalidDidException) { throw ApiException(HttpStatusCode.BadRequest, "DID_INVALID", e.message ?: "DID inválido") }
        val owner = db.tx { c -> store.namespaceOwner(c, id.namespacePath) }
        if (owner != clientId) throw ApiException(HttpStatusCode.Forbidden, "NAMESPACE_NOT_OWNED", "La entidad no tiene permiso sobre este namespace")
    }

    suspend fun versions(did: String): List<VersionRow> = withContext(Dispatchers.IO) { db.tx { c -> store.versions(c, did) } }
    suspend fun version(did: String, n: Int): VersionRow = withContext(Dispatchers.IO) { db.tx { c -> store.version(c, did, n) } } ?: throw ApiException(HttpStatusCode.NotFound, "NOT_FOUND", "Versión inexistente")
    suspend fun operation(id: String): OperationRow = withContext(Dispatchers.IO) { db.tx { c -> store.operation(c, id) } } ?: throw ApiException(HttpStatusCode.NotFound, "NOT_FOUND", "Operación inexistente")
    suspend fun audit(did: String?): List<AuditRow> = withContext(Dispatchers.IO) { db.tx { c -> store.auditFor(c, did) } }

    /** Reconstruye el estado del DID en el instante `at` a partir de la traza de versiones. */
    suspend fun stateAt(did: String, at: Instant): StateView = withContext(Dispatchers.IO) {
        db.tx { c ->
            val v = store.versionAt(c, did, at) ?: throw ApiException(HttpStatusCode.NotFound, "NOT_FOUND", "El DID no existía en $at")
            StateView(did, at.toString(), if (v.operation == "DEACTIVATE") "DEACTIVATED" else "ACTIVE", v.version, v.hash, v.document)
        }
    }

    suspend fun auditEvent(actor: String, action: String, detail: String) = withContext(Dispatchers.IO) {
        db.tx { c -> store.audit(c, actor, action, detail = buildJsonObject { put("detail", JsonPrimitive(detail)) }) }
    }

    // ------------------------------------------------------------------ respaldo / restauración (ERSo 004)
    suspend fun backup(actor: String): JsonObject = withContext(Dispatchers.IO) {
        db.tx { c ->
            // la entrada de auditoría se escribe ANTES de exportar, para que el propio respaldo la contenga
            store.audit(c, actor, "BACKUP", detail = buildJsonObject { put("format", JsonPrimitive("vdr-backup/1")) })
            val tables = JsonObject(RegistryStore.BACKUP_TABLES.associateWith { store.exportTable(c, it) })
            val body = buildJsonObject {
                put("format", JsonPrimitive("vdr-backup/1")); put("domain", JsonPrimitive(cfg.domain))
                put("createdAt", JsonPrimitive(Instant.now().toString())); put("tables", tables)
            }
            JsonObject(body + ("checksum" to JsonPrimitive(CanonicalJson.hash(body))))
        }
    }

    suspend fun restore(actor: String, backup: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val checksum = (backup["checksum"] as? JsonPrimitive)?.contentOrNull ?: throw ApiException(HttpStatusCode.BadRequest, "INVALID_BACKUP", "El respaldo no trae checksum")
        val body = JsonObject(backup - "checksum")
        if (CanonicalJson.hash(body) != checksum) throw ApiException(HttpStatusCode.UnprocessableEntity, "CHECKSUM_MISMATCH", "El checksum del respaldo no coincide: archivo alterado o corrupto")
        if ((body["format"] as? JsonPrimitive)?.contentOrNull != "vdr-backup/1") throw ApiException(HttpStatusCode.BadRequest, "INVALID_BACKUP", "Formato de respaldo desconocido")
        if ((body["domain"] as? JsonPrimitive)?.contentOrNull != cfg.domain) throw ApiException(HttpStatusCode.UnprocessableEntity, "DOMAIN_MISMATCH", "El respaldo pertenece a otro dominio")
        val tables = body["tables"]?.jsonObject ?: throw ApiException(HttpStatusCode.BadRequest, "INVALID_BACKUP", "Faltan las tablas")
        db.tx { c ->
            if (!store.isEmpty(c, "did_documents") || !store.isEmpty(c, "did_document_versions")) {
                throw ApiException(HttpStatusCode.Conflict, "REGISTRY_NOT_EMPTY", "La restauración solo se permite sobre un registro vacío")
            }
            val counts = RegistryStore.BACKUP_TABLES.associateWith { t -> store.importTable(c, t, tables[t]?.jsonArray ?: JsonArray(emptyList())) }
            // verificación de integridad: la versión vigente de cada DID debe existir y su hash coincidir con el contenido
            var verified = 0
            for (row in tables["did_documents"]!!.jsonArray) {
                val did = row.jsonObject["did"]!!.jsonPrimitive.content
                val current = row.jsonObject["current_version"]!!.jsonPrimitive.content.toInt()
                val v = store.version(c, did, current) ?: throw ApiException(HttpStatusCode.UnprocessableEntity, "INTEGRITY_ERROR", "Falta la versión $current de $did")
                if (v.document != null && CanonicalJson.sha256(v.document.toByteArray()) != v.hash) throw ApiException(HttpStatusCode.UnprocessableEntity, "INTEGRITY_ERROR", "Hash inconsistente en $did v$current")
                verified++
            }
            store.audit(c, actor, "RESTORE", detail = buildJsonObject { put("checksum", JsonPrimitive(checksum)); put("documentsVerified", JsonPrimitive(verified)) })
            buildJsonObject {
                put("restored", JsonObject(counts.mapValues { JsonPrimitive(it.value) })); put("documentsVerified", JsonPrimitive(verified)); put("checksum", JsonPrimitive(checksum))
            }
        }
    }
}

