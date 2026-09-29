package co.org.avance.ssi.wallet

import co.org.avance.ssi.credentials.CredentialException
import co.org.avance.ssi.credentials.Jwk
import co.org.avance.ssi.didcore.CanonicalJson
import co.org.avance.ssi.didcore.DidConformance
import co.org.avance.ssi.didcore.DidWeb
import co.org.avance.ssi.didcore.InvalidDidException
import co.org.avance.ssi.didcore.InvalidKeyException
import co.org.avance.ssi.didcore.Jws
import co.org.avance.ssi.didcore.JwsException
import co.org.avance.ssi.didcore.Multikey
import co.org.avance.ssi.wallet.core.ProtectionLevel
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.sql.Connection
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class ApiException(val status: HttpStatusCode, val code: String, message: String, val details: List<String> = emptyList()) : RuntimeException(message)

private val b64u = Base64.getUrlEncoder().withoutPadding()
private val b64uDec = Base64.getUrlDecoder()
private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

class InstanceRow(
    val id: UUID, val citizenId: UUID, val status: String, val publicJwk: JsonObject, val thumbprint: String,
    val declared: String, val verified: String, val attested: Boolean, val profile: JsonObject, val tokenHash: ByteArray,
    val activatedAt: String, val revokedAt: String?, val revokedReason: String?, val replacedBy: String?,
)

private class Eval(val level: ProtectionLevel, val attested: Boolean, val info: JsonObject)

/**
 * WALLET BACKEND (se CONSTRUYE, no se configura). Coordina — sin acceso a ninguna clave privada — lo que la cartera necesita del servidor:
 * registro/activación de la instancia, nivel de protección verificado, recuperación de cuenta, respaldo cifrado y publicación del DID en el VDR.
 */
class WalletService(
    private val cfg: WalletConfig,
    private val db: Db,
    private val verifier: KeyAttestationVerifier,
    private val vdr: VdrGateway,
) {
    private val random = SecureRandom()
    private fun token(n: Int = 32) = b64u.encodeToString(ByteArray(n).also(random::nextBytes))

    // ------------------------------------------------------------------ auditoría
    private fun audit(c: Connection, actor: String, action: String, citizenId: UUID? = null, instanceId: UUID? = null, detail: JsonObject = JsonObject(emptyMap())): Long =
        c.prepareStatement("INSERT INTO wallet_audit(actor, action, citizen_id, instance_id, detail) VALUES (?, ?, ?, ?, ?::jsonb) RETURNING id").use {
            it.setString(1, actor); it.setString(2, action); it.setObject(3, citizenId); it.setObject(4, instanceId); it.setString(5, detail.toString())
            it.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }

    /** Registra el rechazo y CONFIRMA la transacción para que el rastro sobreviva a la excepción. */
    private fun reject(c: Connection, actor: String, action: String, ex: ApiException, citizenId: UUID? = null, instanceId: UUID? = null): ApiException {
        audit(c, actor, action, citizenId, instanceId, buildJsonObject { put("code", JsonPrimitive(ex.code)); put("message", JsonPrimitive(ex.message ?: "")); if (ex.details.isNotEmpty()) put("details", JsonArray(ex.details.map(::JsonPrimitive))) })
        c.commit()
        return ex
    }

    // ------------------------------------------------------------------ ciudadanos y recuperación
    private fun recoveryHash(code: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(code.uppercase().replace("-", "").toCharArray(), salt, 120_000, 256)).encoded

    /** Alta de la identidad de cartera del ciudadano: un identificador opaco y un código de recuperación que se muestra UNA vez. */
    suspend fun createCitizen(): JsonObject = withContext(Dispatchers.IO) {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val raw = (1..20).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        val code = raw.chunked(4).joinToString("-")
        val salt = ByteArray(16).also(random::nextBytes)
        val id = UUID.randomUUID()
        db.tx { c ->
            c.prepareStatement("INSERT INTO wallet_citizens(id, recovery_salt, recovery_hash) VALUES (?, ?, ?)").use {
                it.setObject(1, id); it.setBytes(2, salt); it.setBytes(3, recoveryHash(code, salt)); it.executeUpdate()
            }
            audit(c, "system", "CITIZEN_CREATED", id)
        }
        buildJsonObject { put("citizenRef", JsonPrimitive(id.toString())); put("recoveryCode", JsonPrimitive(code)); put("note", JsonPrimitive("Guarde el código de recuperación: no se volverá a mostrar")) }
    }

    suspend fun recoveryStart(citizenRef: String, code: String): JsonObject = withContext(Dispatchers.IO) {
        val id = uuid(citizenRef, "citizenRef")
        db.tx { c ->
            val row = c.prepareStatement("SELECT recovery_salt, recovery_hash, failed_attempts, (locked_until IS NOT NULL AND locked_until > now()) FROM wallet_citizens WHERE id = ? FOR UPDATE").use {
                it.setObject(1, id); it.executeQuery().use { rs -> if (rs.next()) Triple(rs.getBytes(1), rs.getBytes(2), rs.getInt(3) to rs.getBoolean(4)) else null }
            } ?: throw reject(c, "anonymous", "RECOVERY_DENIED", ApiException(HttpStatusCode.Unauthorized, "INVALID_RECOVERY_CODE", "Código de recuperación inválido"))
            val (attempts, locked) = row.third
            if (locked) throw reject(c, "citizen", "RECOVERY_DENIED", ApiException(HttpStatusCode.TooManyRequests, "RECOVERY_LOCKED", "Demasiados intentos; intente más tarde"), id)
            if (!MessageDigest.isEqual(recoveryHash(code, row.first), row.second)) {
                val n = attempts + 1
                c.prepareStatement("UPDATE wallet_citizens SET failed_attempts = ?, locked_until = CASE WHEN ? >= ? THEN now() + (? * interval '1 minute') ELSE locked_until END WHERE id = ?").use {
                    it.setInt(1, n); it.setInt(2, n); it.setInt(3, cfg.maxRecoveryAttempts); it.setLong(4, cfg.recoveryLockMinutes); it.setObject(5, id); it.executeUpdate()
                }
                throw reject(c, "citizen", "RECOVERY_DENIED", ApiException(HttpStatusCode.Unauthorized, "INVALID_RECOVERY_CODE", "Código de recuperación inválido"), id)
            }
            c.prepareStatement("UPDATE wallet_citizens SET failed_attempts = 0, locked_until = NULL WHERE id = ?").use { it.setObject(1, id); it.executeUpdate() }
            val tok = token()
            val expires = c.prepareStatement("INSERT INTO wallet_recovery_tokens(token_hash, citizen_id, expires_at) VALUES (?, ?, now() + (? * interval '1 second')) RETURNING expires_at::text").use {
                it.setBytes(1, sha256(tok.toByteArray())); it.setObject(2, id); it.setLong(3, cfg.recoveryTokenTtlSeconds); it.executeQuery().use { rs -> rs.next(); rs.getString(1) }
            }
            audit(c, "citizen", "RECOVERY_STARTED", id)
            buildJsonObject { put("recoveryToken", JsonPrimitive(tok)); put("expiresAt", JsonPrimitive(expires)); put("next", JsonPrimitive("Active una instancia nueva enviando este recoveryToken")) }
        }
    }

    // ------------------------------------------------------------------ desafíos
    suspend fun issueChallenge(purpose: String, citizenRef: String?, instance: InstanceRow?): JsonObject = withContext(Dispatchers.IO) {
        if (purpose != "ACTIVATION" && purpose != "DID_KEY") throw ApiException(HttpStatusCode.BadRequest, "INVALID_PURPOSE", "purpose debe ser ACTIVATION o DID_KEY")
        val citizen = if (purpose == "DID_KEY") instance?.citizenId ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "DID_KEY exige una instancia autenticada")
        else uuid(citizenRef ?: throw ApiException(HttpStatusCode.BadRequest, "MISSING_FIELD", "Falta citizenRef"), "citizenRef")
        val id = UUID.randomUUID(); val nonce = token()
        db.tx { c ->
            val exists = c.prepareStatement("SELECT 1 FROM wallet_citizens WHERE id = ?").use { it.setObject(1, citizen); it.executeQuery().use { rs -> rs.next() } }
            if (!exists) throw ApiException(HttpStatusCode.NotFound, "CITIZEN_NOT_FOUND", "Ciudadano inexistente")
            val expires = c.prepareStatement("INSERT INTO wallet_challenges(id, nonce, purpose, citizen_id, instance_id, expires_at) VALUES (?, ?, ?, ?, ?, now() + (? * interval '1 second')) RETURNING expires_at::text").use {
                it.setObject(1, id); it.setString(2, nonce); it.setString(3, purpose); it.setObject(4, citizen); it.setObject(5, instance?.id); it.setLong(6, cfg.challengeTtlSeconds)
                it.executeQuery().use { rs -> rs.next(); rs.getString(1) }
            }
            audit(c, "citizen", "CHALLENGE_ISSUED", citizen, instance?.id, buildJsonObject { put("purpose", JsonPrimitive(purpose)) })
            buildJsonObject { put("challengeId", JsonPrimitive(id.toString())); put("nonce", JsonPrimitive(nonce)); put("audience", JsonPrimitive(cfg.audience)); put("expiresAt", JsonPrimitive(expires)) }
        }
    }

    private class Challenge(val nonce: String, val purpose: String, val citizenId: UUID, val instanceId: UUID?)

    /** Un desafío se consume UNA vez y en su propia transacción: aunque la solicitud falle, no puede reutilizarse. */
    private fun consumeChallenge(id: UUID): Challenge? = db.tx { c ->
        c.prepareStatement("UPDATE wallet_challenges SET used_at = now() WHERE id = ? AND used_at IS NULL AND expires_at > now() RETURNING nonce, purpose, citizen_id, instance_id").use {
            it.setObject(1, id); it.executeQuery().use { rs -> if (rs.next()) Challenge(rs.getString(1), rs.getString(2), rs.getObject(3, UUID::class.java), rs.getObject(4, UUID::class.java)) else null }
        }
    }

    // ------------------------------------------------------------------ evaluación del nivel de protección (ERSo 001, criterio 3)
    private fun evaluate(c: Connection, chain: List<ByteArray>?, nonce: String, pub: ECPublicKey, citizenId: UUID, instanceId: UUID?): Eval {
        fun fail(code: String, msg: String, vararg details: String) = reject(c, "citizen", "ATTESTATION_REJECTED", ApiException(HttpStatusCode.UnprocessableEntity, code, msg, details.toList()), citizenId, instanceId)
        if (chain.isNullOrEmpty()) {
            return Eval(ProtectionLevel.SOFTWARE, false, buildJsonObject {
                put("present", JsonPrimitive(false)); put("note", JsonPrimitive("Sin evidencia de attestation: se asume SOFTWARE (no se supone ninguna garantía de hardware)"))
            })
        }
        val r = try { verifier.verify(chain, nonce.toByteArray()) } catch (e: AttestationException) { throw fail("ATTESTATION_INVALID", "La attestation no es válida", e.code, e.message ?: "") }
        if (Jwk.thumbprint(r.leafPublicKey) != Jwk.thumbprint(pub)) throw fail("ATTESTATION_KEY_MISMATCH", "La attestation corresponde a otra clave")
        var level = ProtectionLevel.fromAttestationCode(r.effectiveLevelCode)
        val notes = mutableListOf<String>()
        if (level != ProtectionLevel.SOFTWARE) {
            if (!r.generatedInSecureEnvironment) throw fail("KEY_NOT_GENERATED_IN_HARDWARE", "La clave no fue generada dentro del entorno seguro (origen=${r.origin}); no se puede declarar no exportable")
            if (2 !in r.purposes || r.algorithm != 3 || r.ecCurve != 1) throw fail("KEY_PROFILE_INVALID", "La clave debe ser EC P-256 con propósito de firma")
            if (cfg.requireVerifiedBoot && (r.verifiedBootState != 0 || r.deviceLocked != true)) {
                level = ProtectionLevel.SOFTWARE; notes += "El arranque no está verificado/bloqueado: no se confía en las afirmaciones de hardware (nivel reducido a SOFTWARE)"
            }
        }
        return Eval(level, true, buildJsonObject {
            put("present", JsonPrimitive(true)); put("attestationVersion", JsonPrimitive(r.attestationVersion))
            put("attestationSecurityLevel", JsonPrimitive(r.attestationSecurityLevel)); put("keymasterSecurityLevel", JsonPrimitive(r.keymasterSecurityLevel))
            put("origin", r.origin?.let { JsonPrimitive(it) } ?: JsonNull); put("purposes", JsonArray(r.purposes.map(::JsonPrimitive)))
            put("ecCurve", r.ecCurve?.let { JsonPrimitive(it) } ?: JsonNull); put("verifiedBootState", r.verifiedBootState?.let { JsonPrimitive(it) } ?: JsonNull)
            put("deviceLocked", r.deviceLocked?.let { JsonPrimitive(it) } ?: JsonNull)
            put("notes", JsonArray(notes.map(::JsonPrimitive)))
        })
    }

    // ------------------------------------------------------------------ activación (ERSo 001, criterios 1, 3 y 4)
    suspend fun activate(body: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val citizenId = uuid(str(body, "citizenRef"), "citizenRef")
        val challengeId = uuid(str(body, "challengeId"), "challengeId")
        val declared = try { ProtectionLevel.valueOf(str(body, "declaredLevel")) } catch (e: IllegalArgumentException) { throw ApiException(HttpStatusCode.BadRequest, "INVALID_LEVEL", "declaredLevel debe ser SOFTWARE, TEE o STRONGBOX") }
        val jwkObj = (body["publicKey"] as? JsonObject) ?: throw ApiException(HttpStatusCode.BadRequest, "MISSING_FIELD", "Falta publicKey (JWK)")
        val pub = try { Jwk.toPublic(jwkObj) } catch (e: CredentialException) { throw ApiException(HttpStatusCode.BadRequest, "INVALID_KEY", e.message ?: "Clave inválida") }
        val thumb = Jwk.thumbprint(pub)
        val proof = str(body, "proof")
        val chain = ((body["attestation"] as? JsonObject)?.get("chain") as? JsonArray)?.map {
            try { Base64.getDecoder().decode(it.jsonPrimitive.content) } catch (e: IllegalArgumentException) { throw ApiException(HttpStatusCode.BadRequest, "INVALID_ATTESTATION", "La cadena de attestation no es base64") }
        }
        val recoveryToken = (body["recoveryToken"] as? JsonPrimitive)?.contentOrNull
        val deviceInfo = sanitize(body["deviceInfo"])

        val ch = consumeChallenge(challengeId)
        db.tx { c ->
            fun deny(code: String, msg: String, status: HttpStatusCode = HttpStatusCode.Forbidden) = reject(c, "citizen", "ACTIVATION_REJECTED", ApiException(status, code, msg), citizenId)
            if (ch == null) throw deny("CHALLENGE_INVALID", "Desafío inexistente, expirado o ya utilizado")
            if (ch.purpose != "ACTIVATION" || ch.citizenId != citizenId) throw deny("CHALLENGE_MISMATCH", "El desafío no corresponde a esta activación")

            // prueba de posesión: quien pide activar debe controlar la clave que declara
            val expected = buildJsonObject {
                put("aud", JsonPrimitive(cfg.audience)); put("challenge", JsonPrimitive(ch.nonce)); put("citizenRef", JsonPrimitive(citizenId.toString()))
                put("declaredLevel", JsonPrimitive(declared.name)); put("thumbprint", JsonPrimitive(thumb))
            }
            val parsed = try { Jws.parse(proof) } catch (e: JwsException) { throw deny("INVALID_PROOF", "Prueba de posesión mal formada") }
            if (parsed.typ != "wallet-activation+jwt" || !Jws.verify(parsed, pub)) throw deny("INVALID_PROOF", "La prueba de posesión no corresponde a la clave declarada")
            val payload = try { Json.parseToJsonElement(String(parsed.payload)).jsonObject } catch (e: Exception) { throw deny("INVALID_PROOF", "Payload de la prueba inválido") }
            if (payload != expected) throw deny("INVALID_PROOF", "El contenido de la prueba no coincide con el desafío y la clave")

            // nivel REAL (lo que la attestation demuestra), no el que el dispositivo afirma
            val eval = evaluate(c, chain, ch.nonce, pub, citizenId, null)
            fun level(code: String, msg: String, status: HttpStatusCode) = reject(c, "citizen", "ACTIVATION_REJECTED",
                ApiException(status, code, msg, listOf("declared=${declared.name}", "verified=${eval.level.name}")), citizenId)
            if (declared.rank > eval.level.rank) throw level("PROTECTION_LEVEL_OVERSTATED", "El nivel declarado (${declared.name}) supera el que el dispositivo demuestra (${eval.level.name})", HttpStatusCode.UnprocessableEntity)
            if (declared.rank < eval.level.rank) throw level("PROTECTION_LEVEL_UNDERSTATED", "El nivel declarado (${declared.name}) no coincide con el disponible (${eval.level.name})", HttpStatusCode.UnprocessableEntity)
            if (eval.level.rank < cfg.minimumLevel.rank) throw level("PROTECTION_BELOW_POLICY", "La política exige al menos ${cfg.minimumLevel.name}", HttpStatusCode.Forbidden)

            // recuperación: un token de un solo uso autoriza reemplazar la cartera anterior
            var revoked = emptyList<UUID>()
            val newId = UUID.randomUUID()
            if (recoveryToken != null) {
                val ok = c.prepareStatement("UPDATE wallet_recovery_tokens SET used_at = now() WHERE token_hash = ? AND citizen_id = ? AND used_at IS NULL AND expires_at > now() RETURNING 1").use {
                    it.setBytes(1, sha256(recoveryToken.toByteArray())); it.setObject(2, citizenId); it.executeQuery().use { rs -> rs.next() }
                }
                if (!ok) throw deny("RECOVERY_TOKEN_INVALID", "Token de recuperación inexistente, expirado, usado o de otro ciudadano")
                revoked = c.prepareStatement("UPDATE wallet_instances SET status = 'REVOKED', revoked_at = now(), revoked_reason = 'RECOVERY', replaced_by = ? WHERE citizen_id = ? AND status = 'ACTIVE' RETURNING id").use {
                    it.setObject(1, newId); it.setObject(2, citizenId); it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getObject(1, UUID::class.java)) } }
                }
            } else {
                val active = c.prepareStatement("SELECT 1 FROM wallet_instances WHERE citizen_id = ? AND status = 'ACTIVE'").use { it.setObject(1, citizenId); it.executeQuery().use { rs -> rs.next() } }
                if (active) throw deny("ACTIVE_INSTANCE_EXISTS", "El ciudadano ya tiene una cartera activa; use la recuperación para reemplazarla", HttpStatusCode.Conflict)
            }

            val accessToken = token()
            val profile = buildJsonObject {
                put("deviceInfo", deviceInfo); put("attestation", eval.info)
                put("declaredLevel", JsonPrimitive(declared.name)); put("verifiedLevel", JsonPrimitive(eval.level.name)); put("policyMinimumLevel", JsonPrimitive(cfg.minimumLevel.name))
                put("privateKeyHeldByBackend", JsonPrimitive(false))
            }
            c.prepareStatement("INSERT INTO wallet_instances(id, citizen_id, status, public_jwk, thumbprint, declared_level, verified_level, attested, device_profile, token_hash) VALUES (?, ?, 'ACTIVE', ?::jsonb, ?, ?, ?, ?, ?::jsonb, ?)").use {
                it.setObject(1, newId); it.setObject(2, citizenId); it.setString(3, Jwk.fromPublic(pub).toString()); it.setString(4, thumb)
                it.setString(5, declared.name); it.setString(6, eval.level.name); it.setBoolean(7, eval.attested); it.setString(8, profile.toString()); it.setBytes(9, sha256(accessToken.toByteArray())); it.executeUpdate()
            }
            val auditId = audit(c, "citizen", if (revoked.isEmpty()) "INSTANCE_ACTIVATED" else "INSTANCE_ACTIVATED_BY_RECOVERY", citizenId, newId, buildJsonObject {
                put("declaredLevel", JsonPrimitive(declared.name)); put("verifiedLevel", JsonPrimitive(eval.level.name)); put("attested", JsonPrimitive(eval.attested)); put("thumbprint", JsonPrimitive(thumb))
                if (revoked.isNotEmpty()) put("revokedInstances", JsonArray(revoked.map { JsonPrimitive(it.toString()) }))
            })
            val activatedAt = c.prepareStatement("SELECT activated_at::text FROM wallet_instances WHERE id = ?").use { it.setObject(1, newId); it.executeQuery().use { rs -> rs.next(); rs.getString(1) } }
            buildJsonObject {
                put("instanceId", JsonPrimitive(newId.toString())); put("status", JsonPrimitive("ACTIVE")); put("accessToken", JsonPrimitive(accessToken))
                put("protection", buildJsonObject { put("declared", JsonPrimitive(declared.name)); put("verified", JsonPrimitive(eval.level.name)); put("attested", JsonPrimitive(eval.attested)) })
                put("activationRecord", buildJsonObject { put("auditId", JsonPrimitive(auditId)); put("activatedAt", JsonPrimitive(activatedAt)); put("citizenRef", JsonPrimitive(citizenId.toString())) })
                if (revoked.isNotEmpty()) put("recovery", buildJsonObject {
                    put("revokedInstances", JsonArray(revoked.map { JsonPrimitive(it.toString()) }))
                    put("reissueRequired", JsonPrimitive(true))
                    put("note", JsonPrimitive("Las credenciales estaban ligadas a la clave del dispositivo anterior y deben reemitirse; el DID anterior no se puede desactivar sin su clave"))
                })
            }
        }
    }

    // ------------------------------------------------------------------ instancias
    private fun load(c: Connection, id: UUID): InstanceRow? = c.prepareStatement(
        "SELECT id, citizen_id, status, public_jwk::text, thumbprint, declared_level, verified_level, attested, device_profile::text, token_hash, activated_at::text, revoked_at::text, revoked_reason, replaced_by::text FROM wallet_instances WHERE id = ?",
    ).use {
        it.setObject(1, id)
        it.executeQuery().use { rs ->
            if (!rs.next()) null else InstanceRow(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), Json.parseToJsonElement(rs.getString(4)).jsonObject, rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getBoolean(8), Json.parseToJsonElement(rs.getString(9)).jsonObject, rs.getBytes(10), rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14))
        }
    }

    /** Autentica con el token de la instancia (guardado como hash). Una instancia revocada ya no autentica. */
    suspend fun authenticate(instanceId: String, bearer: String?): InstanceRow = withContext(Dispatchers.IO) {
        val id = uuid(instanceId, "instanceId")
        if (bearer.isNullOrBlank()) throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Falta el token de la instancia")
        db.tx { c ->
            val row = load(c, id)
            if (row == null || !MessageDigest.isEqual(row.tokenHash, sha256(bearer.toByteArray()))) throw reject(c, "anonymous", "AUTH_DENIED", ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Token de instancia inválido"), null, if (row != null) id else null)
            if (row.status != "ACTIVE") throw reject(c, "citizen", "AUTH_DENIED", ApiException(HttpStatusCode.Forbidden, "INSTANCE_REVOKED", "La instancia fue revocada (${row.revokedReason})"), row.citizenId, id)
            row
        }
    }

    fun view(r: InstanceRow): JsonObject = buildJsonObject {
        put("instanceId", JsonPrimitive(r.id.toString())); put("citizenRef", JsonPrimitive(r.citizenId.toString())); put("status", JsonPrimitive(r.status))
        put("protection", buildJsonObject { put("declared", JsonPrimitive(r.declared)); put("verified", JsonPrimitive(r.verified)); put("attested", JsonPrimitive(r.attested)); put("policyMinimum", JsonPrimitive(cfg.minimumLevel.name)) })
        put("thumbprint", JsonPrimitive(r.thumbprint)); put("publicKey", r.publicJwk); put("deviceProfile", r.profile)
        put("activatedAt", JsonPrimitive(r.activatedAt)); r.revokedAt?.let { put("revokedAt", JsonPrimitive(it)) }; r.revokedReason?.let { put("revokedReason", JsonPrimitive(it)) }
    }

    suspend fun revoke(inst: InstanceRow): JsonObject = withContext(Dispatchers.IO) {
        db.tx { c ->
            c.prepareStatement("UPDATE wallet_instances SET status = 'REVOKED', revoked_at = now(), revoked_reason = 'USER' WHERE id = ? AND status = 'ACTIVE'").use { it.setObject(1, inst.id); it.executeUpdate() }
            audit(c, "citizen", "INSTANCE_REVOKED", inst.citizenId, inst.id)
            buildJsonObject { put("instanceId", JsonPrimitive(inst.id.toString())); put("status", JsonPrimitive("REVOKED")) }
        }
    }

    suspend fun auditFor(inst: InstanceRow): JsonArray = withContext(Dispatchers.IO) {
        db.tx { c ->
            c.prepareStatement("SELECT id, at::text, actor, action, detail::text FROM wallet_audit WHERE instance_id = ? OR (instance_id IS NULL AND citizen_id = ?) ORDER BY id").use {
                it.setObject(1, inst.id); it.setObject(2, inst.citizenId)
                it.executeQuery().use { rs -> JsonArray(buildList { while (rs.next()) add(buildJsonObject {
                    put("id", JsonPrimitive(rs.getLong(1))); put("at", JsonPrimitive(rs.getString(2))); put("actor", JsonPrimitive(rs.getString(3))); put("action", JsonPrimitive(rs.getString(4)))
                    put("detail", Json.parseToJsonElement(rs.getString(5)))
                }) }) }
            }
        }
    }

    // ------------------------------------------------------------------ DID del titular → VDR (ERSo 003 + diagrama pasos 4 a 6)
    suspend fun beginDid(inst: InstanceRow, body: JsonObject): JsonObject {
        val doc = (body["document"] as? JsonObject) ?: throw ApiException(HttpStatusCode.BadRequest, "MISSING_FIELD", "Falta document")
        val did = (doc["id"] as? JsonPrimitive)?.contentOrNull ?: throw ApiException(HttpStatusCode.BadRequest, "MISSING_FIELD", "El documento no tiene id")
        val id = try { DidWeb.parse(did) } catch (e: InvalidDidException) { throw ApiException(HttpStatusCode.BadRequest, "DID_INVALID", e.message ?: "DID inválido") }
        if (id.host != cfg.domain.lowercase() || id.path.size != 2 || id.path[0] != "titulares") {
            throw ApiException(HttpStatusCode.UnprocessableEntity, "DID_OUT_OF_NAMESPACE", "El backend solo publica DID bajo did:web:${cfg.domain}:titulares:<huella>")
        }
        val multibase = ((doc["verificationMethod"] as? JsonArray)?.singleOrNull() as? JsonObject)?.get("publicKeyMultibase")?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: throw ApiException(HttpStatusCode.UnprocessableEntity, "DID_NOT_CONFORMANT", "El documento debe tener exactamente una clave", listOf("C03"))
        val key = try { Multikey.decodeP256(multibase) } catch (e: InvalidKeyException) { throw ApiException(HttpStatusCode.UnprocessableEntity, "DID_NOT_CONFORMANT", "Clave inválida", listOf("C04")) }
        if (Jwk.thumbprint(key) != id.path[1]) throw ApiException(HttpStatusCode.UnprocessableEntity, "DID_NOT_DERIVED_FROM_KEY", "El identificador no se deriva de la clave pública del documento")
        val report = DidConformance.check(doc, did, multibase)
        if (!report.conformant) throw ApiException(HttpStatusCode.UnprocessableEntity, "DID_NOT_CONFORMANT", "El documento no es conforme; no se publica", report.failed.map { "${it.id}: ${it.detail}" })

        val instLevel = ProtectionLevel.valueOf(inst.verified)
        val keyAtt = body["keyAttestation"] as? JsonObject
        val keyLevel = withContext(Dispatchers.IO) {
            db.tx { c ->
                if (keyAtt == null) {
                    if (instLevel != ProtectionLevel.SOFTWARE) throw reject(c, "citizen", "DID_REJECTED", ApiException(HttpStatusCode.UnprocessableEntity, "KEY_ATTESTATION_REQUIRED",
                        "La cartera es ${instLevel.name}: la clave del DID debe demostrar el mismo nivel con su propia attestation"), inst.citizenId, inst.id)
                    ProtectionLevel.SOFTWARE
                } else {
                    val chId = uuid(str(keyAtt, "challengeId"), "keyAttestation.challengeId")
                    val ch = consumeChallenge(chId)
                    if (ch == null || ch.purpose != "DID_KEY" || ch.instanceId != inst.id) throw reject(c, "citizen", "DID_REJECTED", ApiException(HttpStatusCode.Forbidden, "CHALLENGE_INVALID", "Desafío DID_KEY inválido"), inst.citizenId, inst.id)
                    val chain = (keyAtt["chain"] as? JsonArray)?.map { Base64.getDecoder().decode(it.jsonPrimitive.content) }
                    evaluate(c, chain, ch.nonce, key, inst.citizenId, inst.id).level
                }
            }
        }
        if (keyLevel.rank < instLevel.rank) throw ApiException(HttpStatusCode.UnprocessableEntity, "DID_KEY_BELOW_INSTANCE_LEVEL", "La clave del DID (${keyLevel.name}) es más débil que la cartera (${instLevel.name})")

        withContext(Dispatchers.IO) {
            db.tx { c ->
                val dup = c.prepareStatement("SELECT 1 FROM wallet_did_publications WHERE did = ? AND status IN ('PUBLISHED','PENDING')").use { it.setString(1, did); it.executeQuery().use { rs -> rs.next() } }
                if (dup) throw ApiException(HttpStatusCode.Conflict, "DID_ALREADY_PUBLISHED", "Ese DID ya fue publicado")
            }
        }
        val vch = try { vdr.challenge(did, "CREATE") } catch (e: VdrException) {
            throw ApiException(if (e.status >= 500 || e.status == 0) HttpStatusCode.BadGateway else HttpStatusCode.UnprocessableEntity, vdrCode(e), e.message ?: "Error del VDR", e.details)
        }
        val docHash = CanonicalJson.hash(doc)
        val pubId = UUID.randomUUID()
        withContext(Dispatchers.IO) {
            db.tx { c ->
                c.prepareStatement("INSERT INTO wallet_did_publications(id, instance_id, did, doc_hash, document, key_level, vdr_challenge_id, vdr_nonce, vdr_audience, status) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, 'AWAITING_PROOF')").use {
                    it.setObject(1, pubId); it.setObject(2, inst.id); it.setString(3, did); it.setString(4, docHash); it.setString(5, doc.toString()); it.setString(6, keyLevel.name)
                    it.setString(7, vch.challengeId); it.setString(8, vch.nonce); it.setString(9, vch.audience); it.executeUpdate()
                }
                audit(c, "citizen", "DID_PUBLICATION_STARTED", inst.citizenId, inst.id, buildJsonObject { put("did", JsonPrimitive(did)); put("docHash", JsonPrimitive(docHash)); put("keyLevel", JsonPrimitive(keyLevel.name)) })
            }
        }
        return buildJsonObject {
            put("publicationId", JsonPrimitive(pubId.toString())); put("did", JsonPrimitive(did)); put("kid", JsonPrimitive("$did#key-1"))
            put("conformance", JsonPrimitive("OK")); put("keyLevel", JsonPrimitive(keyLevel.name))
            // Lo que el DISPOSITIVO debe firmar con la clave del DID (el backend no puede: no la tiene).
            put("signing", buildJsonObject {
                put("aud", JsonPrimitive(vch.audience)); put("challenge", JsonPrimitive(vch.nonce)); put("did", JsonPrimitive(did))
                put("docHash", JsonPrimitive(docHash)); put("purpose", JsonPrimitive("CREATE"))
            })
        }
    }

    suspend fun completeDid(inst: InstanceRow, publicationId: String, proof: String): JsonObject {
        val pid = uuid(publicationId, "publicationId")
        class Pub(val did: String, val document: JsonObject, val challengeId: String, val status: String, val version: Int?, val url: String?)
        val pub = withContext(Dispatchers.IO) {
            db.tx { c ->
                c.prepareStatement("SELECT did, document::text, vdr_challenge_id, status, vdr_version, public_url FROM wallet_did_publications WHERE id = ? AND instance_id = ?").use {
                    it.setObject(1, pid); it.setObject(2, inst.id)
                    it.executeQuery().use { rs -> if (rs.next()) Pub(rs.getString(1), Json.parseToJsonElement(rs.getString(2)).jsonObject, rs.getString(3), rs.getString(4), rs.getObject(5) as Int?, rs.getString(6)) else null }
                }
            }
        } ?: throw ApiException(HttpStatusCode.NotFound, "PUBLICATION_NOT_FOUND", "Publicación inexistente")
        fun result(status: String, version: Int?, url: String?, vdrStatus: String?) = buildJsonObject {
            put("publicationId", JsonPrimitive(publicationId)); put("did", JsonPrimitive(pub.did)); put("status", JsonPrimitive(status))
            version?.let { put("version", JsonPrimitive(it)) }; url?.let { put("publicUrl", JsonPrimitive(it)) }; vdrStatus?.let { put("vdrStatus", JsonPrimitive(it)) }
        }
        if (pub.status == "PUBLISHED" || pub.status == "PENDING") return result(pub.status, pub.version, pub.url, null) // idempotente
        if (pub.status != "AWAITING_PROOF") throw ApiException(HttpStatusCode.Conflict, "PUBLICATION_FAILED", "La publicación falló; inicie una nueva")

        val out = try {
            vdr.write(pub.did, "CREATE", 0, publicationId, pub.challengeId, pub.document, proof)
        } catch (e: VdrException) {
            withContext(Dispatchers.IO) {
                db.tx { c ->
                    c.prepareStatement("UPDATE wallet_did_publications SET status = 'FAILED', failure = ?, completed_at = now() WHERE id = ?").use { it.setString(1, "${e.code}: ${e.message}"); it.setObject(2, pid); it.executeUpdate() }
                    audit(c, "citizen", "DID_PUBLICATION_FAILED", inst.citizenId, inst.id, buildJsonObject { put("did", JsonPrimitive(pub.did)); put("vdrError", JsonPrimitive(e.code)) })
                }
            }
            throw ApiException(if (e.status >= 500 || e.status == 0) HttpStatusCode.BadGateway else HttpStatusCode.UnprocessableEntity, vdrCode(e), e.message ?: "El VDR rechazó la publicación", e.details)
        }
        val status = if (out.status == "CONFIRMED") "PUBLISHED" else "PENDING"
        withContext(Dispatchers.IO) {
            db.tx { c ->
                c.prepareStatement("UPDATE wallet_did_publications SET status = ?, vdr_version = ?, public_url = ?, completed_at = now() WHERE id = ?").use {
                    it.setString(1, status); it.setInt(2, out.version); it.setString(3, out.publicUrl); it.setObject(4, pid); it.executeUpdate()
                }
                audit(c, "citizen", if (status == "PUBLISHED") "DID_PUBLISHED" else "DID_PUBLICATION_PENDING", inst.citizenId, inst.id,
                    buildJsonObject { put("did", JsonPrimitive(pub.did)); put("vdrStatus", JsonPrimitive(out.status)); put("version", JsonPrimitive(out.version)); put("hash", JsonPrimitive(out.hash)) })
            }
        }
        return result(status, out.version, out.publicUrl, out.status).let { JsonObject(it + ("hash" to JsonPrimitive(out.hash))) }
    }

    // ------------------------------------------------------------------ respaldo cifrado del DID Document
    suspend fun putBackup(inst: InstanceRow, ciphertextB64: String): JsonObject = withContext(Dispatchers.IO) {
        val bytes = try { b64uDec.decode(ciphertextB64) } catch (e: IllegalArgumentException) { throw ApiException(HttpStatusCode.BadRequest, "INVALID_BACKUP", "ciphertext debe ser base64url") }
        if (bytes.isEmpty() || bytes.size > cfg.maxBackupBytes) throw ApiException(HttpStatusCode.PayloadTooLarge, "BACKUP_SIZE", "El respaldo debe pesar entre 1 y ${cfg.maxBackupBytes} bytes")
        val checksum = "sha256:" + sha256(bytes).joinToString("") { "%02x".format(it) }
        db.tx { c ->
            c.prepareStatement("INSERT INTO wallet_did_backups(instance_id, blob, checksum) VALUES (?, ?, ?) ON CONFLICT (instance_id) DO UPDATE SET blob = EXCLUDED.blob, checksum = EXCLUDED.checksum, updated_at = now()").use {
                it.setObject(1, inst.id); it.setBytes(2, bytes); it.setString(3, checksum); it.executeUpdate()
            }
            audit(c, "citizen", "DID_BACKUP_STORED", inst.citizenId, inst.id, buildJsonObject { put("bytes", JsonPrimitive(bytes.size)); put("checksum", JsonPrimitive(checksum)) })
            buildJsonObject { put("checksum", JsonPrimitive(checksum)); put("bytes", JsonPrimitive(bytes.size)) }
        }
    }

    suspend fun getBackup(inst: InstanceRow): JsonObject = withContext(Dispatchers.IO) {
        db.tx { c ->
            c.prepareStatement("SELECT blob, checksum, updated_at::text FROM wallet_did_backups WHERE instance_id = ?").use {
                it.setObject(1, inst.id)
                it.executeQuery().use { rs ->
                    if (!rs.next()) throw ApiException(HttpStatusCode.NotFound, "NO_BACKUP", "No hay respaldo")
                    buildJsonObject { put("ciphertext", JsonPrimitive(b64u.encodeToString(rs.getBytes(1)))); put("checksum", JsonPrimitive(rs.getString(2))); put("updatedAt", JsonPrimitive(rs.getString(3))) }
                }
            }
        }
    }

    // ------------------------------------------------------------------ utilidades
    private fun vdrCode(e: VdrException) = if (e.code.startsWith("VDR_")) e.code else "VDR_${e.code}"
    private fun str(o: JsonObject, k: String): String = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ApiException(HttpStatusCode.BadRequest, "MISSING_FIELD", "Falta '$k'")
    private fun uuid(s: String, name: String): UUID = try { UUID.fromString(s) } catch (e: IllegalArgumentException) { throw ApiException(HttpStatusCode.BadRequest, "INVALID_ID", "$name inválido") }

    /** La información del dispositivo la declara el propio dispositivo: solo se conservan textos cortos y se rotula como declarada. */
    private fun sanitize(e: JsonElement?): JsonObject = buildJsonObject {
        put("declaredByDevice", JsonPrimitive(true))
        (e as? JsonObject)?.entries?.take(12)?.forEach { (k, v) -> (v as? JsonPrimitive)?.let { if (k.length <= 40) put(k, JsonPrimitive(it.content.take(120))) } }
    }

    /** Solo para pruebas: cuenta filas que contienen un texto (búsqueda de material privado en la base). */
    fun containsText(needle: String): Boolean = db.tx { c ->
        listOf("wallet_citizens", "wallet_challenges", "wallet_instances", "wallet_did_publications", "wallet_did_backups", "wallet_audit").any { t ->
            c.prepareStatement("SELECT count(*) FROM $t x WHERE x::text LIKE ?").use { it.setString(1, "%$needle%"); it.executeQuery().use { rs -> rs.next(); rs.getInt(1) > 0 } }
        }
    }
}
