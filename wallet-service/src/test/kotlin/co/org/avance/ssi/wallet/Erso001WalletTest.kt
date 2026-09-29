package co.org.avance.ssi.wallet

import co.org.avance.ssi.credentials.Jwk
import co.org.avance.ssi.didcore.Jws
import co.org.avance.ssi.sim.Api
import co.org.avance.ssi.sim.LabAttestationAuthority
import co.org.avance.ssi.wallet.core.KeyCustodian
import co.org.avance.ssi.wallet.core.ProtectionLevel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.KeyPair
import java.security.interfaces.ECPrivateKey
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** ERSo 2026-001 — Cartera de identidad y custodia de claves en hardware (lado Wallet Backend; el lado dispositivo está en wallet-core). */
class Erso001WalletTest {
    private var f: WalletFixture? = null
    private fun fx(min: ProtectionLevel = ProtectionLevel.SOFTWARE, boot: Boolean = true, attempts: Int = 5) = WalletFixture.create(min, boot, attempts).also { f = it }
    @AfterTest fun cleanup() { f?.close() }

    private fun privateForms(kp: KeyPair): List<String> {
        val d = (kp.private as ECPrivateKey).s
        val raw = d.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        return listOf(d.toString(), d.toString(16), Base64.getEncoder().encodeToString(kp.private.encoded), Base64.getUrlEncoder().withoutPadding().encodeToString(kp.private.encoded),
            Base64.getUrlEncoder().withoutPadding().encodeToString(raw), Base64.getEncoder().encodeToString(raw)).filter { it.length >= 20 }
    }

    /** Activación "a mano" para poder manipular la solicitud (pruebas negativas). */
    private suspend fun rawActivate(
        f: WalletFixture, c: KeyCustodian, citizenRef: String, declared: ProtectionLevel, alias: String = "wia-${System.nanoTime()}",
        mutate: (JsonObject) -> JsonObject = { it }, signWith: KeyCustodian = c,
    ): Pair<Api, JsonObject> {
        val ch = f.backend.post("/challenges", buildJsonObject { put("purpose", JsonPrimitive("ACTIVATION")); put("citizenRef", JsonPrimitive(citizenRef)) })
        val nonce = ch.str("nonce")
        val key = c.generate(alias, attestationChallenge = nonce.toByteArray())
        val payload = buildJsonObject {
            put("aud", JsonPrimitive(ch.str("audience"))); put("challenge", JsonPrimitive(nonce)); put("citizenRef", JsonPrimitive(citizenRef))
            put("declaredLevel", JsonPrimitive(declared.name)); put("thumbprint", JsonPrimitive(key.thumbprint))
        }
        val proof = Jws.signWith(null, payload.toString().toByteArray(), typ = "wallet-activation+jwt") { signWith.sign(if (signWith === c) alias else "otra", it) }
        val chain = c.attestation(alias)?.chainDer?.map { Base64.getEncoder().encodeToString(it) }
        val body = mutate(buildJsonObject {
            put("citizenRef", JsonPrimitive(citizenRef)); put("challengeId", ch.json!!["challengeId"]!!); put("declaredLevel", JsonPrimitive(declared.name))
            put("publicKey", Jwk.fromPublic(c.publicKey(alias))); put("proof", JsonPrimitive(proof))
            if (chain != null) put("attestation", buildJsonObject { put("chain", JsonArray(chain.map(::JsonPrimitive))) })
        })
        return f.backend.post("/instances", body) to body
    }

    // ==================== criterio 1: instancia registrada y activa, asociada al ciudadano ====================
    @Test fun `C1 la instancia queda registrada y activa asociada al ciudadano, con registro de activacion`() = runBlocking<Unit> {
        val f = fx()
        val app = f.app(f.softwareDevice())
        app.onboard()
        val r = app.activate()
        assertEquals(201, r.status, r.raw)
        assertEquals("ACTIVE", r.str("status"))
        assertEquals("SOFTWARE", r.obj("protection")["verified"]!!.jsonPrimitive.content)
        val act = app.activation!!
        val view = f.backend.get("/instances/${act.instanceId}", act.token)
        assertEquals(200, view.status)
        assertEquals(app.citizenRef, view.str("citizenRef"))
        assertEquals("ACTIVE", view.str("status"))
        assertEquals(false, view.obj("deviceProfile")["privateKeyHeldByBackend"]!!.jsonPrimitive.content.toBoolean())
        // registro de activación: fila de auditoría con el nivel declarado, el verificado y la huella de la clave
        assertTrue(r.obj("activationRecord")["auditId"]!!.jsonPrimitive.content.toLong() > 0)
        val audit = f.backend.get("/instances/${act.instanceId}/audit", act.token)
        assertContains(audit.raw, "INSTANCE_ACTIVATED")
        assertContains(audit.raw, act.wiaThumbprint)
        // el token de acceso solo se guarda como hash
        assertFalse(f.service.containsText(act.token))
        assertEquals(1, f.count("SELECT count(*) FROM wallet_instances WHERE status = 'ACTIVE'"))
    }

    @Test fun `C1 una segunda cartera activa para el mismo ciudadano se rechaza, y la base lo garantiza`() = runBlocking<Unit> {
        val f = fx()
        val a = f.app(f.softwareDevice()); a.onboard(); assertEquals(201, a.activate().status)
        val b = f.app(f.softwareDevice()); b.citizenRef = a.citizenRef
        val r = b.activate()
        assertEquals(409, r.status); assertEquals("ACTIVE_INSTANCE_EXISTS", r.error)
        val e = runCatching { f.exec("INSERT INTO wallet_instances(id, citizen_id, status, public_jwk, thumbprint, declared_level, verified_level, attested, device_profile, token_hash) SELECT gen_random_uuid(), citizen_id, 'ACTIVE', public_jwk, 'x', 'SOFTWARE', 'SOFTWARE', false, '{}', token_hash FROM wallet_instances LIMIT 1") }.exceptionOrNull()
        assertTrue(e != null && "wallet_one_active_per_citizen" in (e.message ?: ""), "el índice único parcial debe impedirlo: ${e?.message}")
    }

    @Test fun `C1 desafio de un solo uso, prueba de posesion obligatoria y autenticacion de instancia`() = runBlocking<Unit> {
        val f = fx()
        val app = f.app(f.softwareDevice()); app.onboard()
        val ref = app.citizenRef!!
        // (a) el contenido de la solicitud se cambió después de firmar
        val (tampered, _) = rawActivate(f, f.softwareDevice(), ref, ProtectionLevel.SOFTWARE, mutate = { JsonObject(it + ("declaredLevel" to JsonPrimitive("TEE"))) })
        assertEquals("INVALID_PROOF", tampered.error)
        // (b) firmada con una clave que no es la declarada
        val (foreign, _) = rawActivate(f, f.softwareDevice(), ref, ProtectionLevel.SOFTWARE, signWith = f.softwareDevice().also { it.generate("otra") })
        assertEquals("INVALID_PROOF", foreign.error)
        // (c) reutilizar el mismo desafío
        val (ok, body) = rawActivate(f, f.softwareDevice(), ref, ProtectionLevel.SOFTWARE)
        assertEquals(201, ok.status, ok.raw)
        val replay = f.backend.post("/instances", body)
        assertEquals("CHALLENGE_INVALID", replay.error)
        // (d) sin token o con token ajeno no se lee la instancia
        val id = ok.str("instanceId")
        assertEquals(401, f.backend.get("/instances/$id").status)
        assertEquals(401, f.backend.get("/instances/$id", "token-inventado").status)
        // (e) una clave JWK que trae material privado se rechaza
        val (withD, _) = rawActivate(f, f.softwareDevice(), ref, ProtectionLevel.SOFTWARE, mutate = { b -> JsonObject(b + ("publicKey" to JsonObject((b["publicKey"] as JsonObject) + ("d" to JsonPrimitive("AAAA"))))) })
        assertEquals("INVALID_KEY", withD.error)
        // (f) ciudadano inexistente
        assertEquals(404, f.backend.post("/challenges", buildJsonObject { put("purpose", JsonPrimitive("ACTIVATION")); put("citizenRef", JsonPrimitive("00000000-0000-0000-0000-000000000000")) }).status)
    }

    @Test fun `C1 el titular puede revocar su instancia y deja de autenticar`() = runBlocking<Unit> {
        val f = fx()
        val app = f.app(f.softwareDevice()); app.onboard(); app.activate()
        val act = app.activation!!
        assertEquals(200, f.backend.post("/instances/${act.instanceId}/revoke", JsonObject(emptyMap()), act.token).status)
        val r = f.backend.get("/instances/${act.instanceId}", act.token)
        assertEquals(403, r.status); assertEquals("INSTANCE_REVOKED", r.error)
    }

    // ==================== criterio 3: el nivel declarado coincide con el realmente disponible ====================
    @Test fun `C3 un dispositivo TEE con attestation valida se activa con el nivel verificado y ficha tecnica`() = runBlocking<Unit> {
        val f = fx()
        val app = f.app(f.attestingDevice(ProtectionLevel.TEE)); app.onboard()
        val r = app.activate()
        assertEquals(201, r.status, r.raw)
        assertEquals("TEE", r.obj("protection")["declared"]!!.jsonPrimitive.content)
        assertEquals("TEE", r.obj("protection")["verified"]!!.jsonPrimitive.content)
        assertEquals("true", r.obj("protection")["attested"]!!.jsonPrimitive.content)
        val ficha = f.backend.get("/instances/${app.activation!!.instanceId}", app.activation!!.token).obj("deviceProfile")
        val att = ficha["attestation"] as JsonObject
        assertEquals("1", att["attestationSecurityLevel"]!!.jsonPrimitive.content)
        assertEquals("0", att["origin"]!!.jsonPrimitive.content)          // GENERATED: nació dentro del entorno seguro
        assertEquals("0", att["verifiedBootState"]!!.jsonPrimitive.content)
        assertEquals("TEE", ficha["verifiedLevel"]!!.jsonPrimitive.content)
        // StrongBox también
        val sb = f.app(f.attestingDevice(ProtectionLevel.STRONGBOX)); sb.onboard()
        assertEquals("STRONGBOX", sb.activate().obj("protection")["verified"]!!.jsonPrimitive.content)
    }

    @Test fun `C3 un dispositivo que exagera su nivel es rechazado - sin evidencia, con evidencia de software o con arranque no verificado`() = runBlocking<Unit> {
        val f = fx()
        // (1) software puro, sin evidencia, dice TEE
        val a = f.app(f.softwareDevice()); a.onboard()
        val r1 = a.activate(declared = ProtectionLevel.TEE)
        assertEquals("PROTECTION_LEVEL_OVERSTATED", r1.error); assertEquals(422, r1.status); assertContains(r1.details, "verified=SOFTWARE")
        // (2) la propia attestation dice Software, pero el dispositivo dice StrongBox
        val b = f.app(f.attestingDevice(ProtectionLevel.SOFTWARE)); b.onboard()
        assertEquals("PROTECTION_LEVEL_OVERSTATED", b.activate(declared = ProtectionLevel.STRONGBOX).error)
        // (3) attestation TEE, pero dice StrongBox
        val c = f.app(f.attestingDevice(ProtectionLevel.TEE)); c.onboard()
        assertEquals("PROTECTION_LEVEL_OVERSTATED", c.activate(declared = ProtectionLevel.STRONGBOX).error)
        // (4) bootloader desbloqueado / arranque no verificado: no se cree en el hardware
        val d = f.app(f.attestingDevice(ProtectionLevel.TEE, boot = LabAttestationAuthority.BOOT_UNVERIFIED)); d.onboard()
        val r4 = d.activate(declared = ProtectionLevel.TEE)
        assertEquals("PROTECTION_LEVEL_OVERSTATED", r4.error); assertContains(r4.details, "verified=SOFTWARE")
        assertEquals(201, d.activate(declared = ProtectionLevel.SOFTWARE).status, "declarar la verdad sí se acepta")
        // ninguna quedó activa por error
        assertEquals(1, f.count("SELECT count(*) FROM wallet_instances"))
        assertTrue(f.count("SELECT count(*) FROM wallet_audit WHERE action = 'ACTIVATION_REJECTED'") >= 4, "los rechazos quedaron auditados")
    }

    @Test fun `C3 declarar menos de lo que se tiene tampoco coincide`() = runBlocking<Unit> {
        val f = fx()
        val a = f.app(f.attestingDevice(ProtectionLevel.TEE)); a.onboard()
        assertEquals("PROTECTION_LEVEL_UNDERSTATED", a.activate(declared = ProtectionLevel.SOFTWARE).error)
    }

    @Test fun `C3 attestation ilegitima - clave importada, raiz desconocida y desafio reutilizado`() = runBlocking<Unit> {
        val f = fx()
        val imported = f.app(f.attestingDevice(ProtectionLevel.TEE, origin = LabAttestationAuthority.ORIGIN_IMPORTED)); imported.onboard()
        assertEquals("KEY_NOT_GENERATED_IN_HARDWARE", imported.activate().error)
        val foreign = f.app(f.attestingDevice(ProtectionLevel.STRONGBOX, a = LabAttestationAuthority.create("Falsa"))); foreign.onboard()
        val rf = foreign.activate()
        assertEquals("ATTESTATION_INVALID", rf.error); assertContains(rf.details, "UNTRUSTED_CHAIN")
        val replay = f.app(f.attestingDevice(ProtectionLevel.TEE)); replay.onboard()
        val rr = replay.activate(attestationChallengeOverride = "desafio-viejo".toByteArray())
        assertEquals("ATTESTATION_INVALID", rr.error); assertContains(rr.details, "CHALLENGE_MISMATCH")
    }

    @Test fun `C3 la politica minima del backend rechaza carteras por debajo del nivel exigido`() = runBlocking<Unit> {
        val f = fx(min = ProtectionLevel.TEE)
        val sw = f.app(f.softwareDevice()); sw.onboard()
        val r = sw.activate()
        assertEquals(403, r.status); assertEquals("PROTECTION_BELOW_POLICY", r.error)
        val tee = f.app(f.attestingDevice(ProtectionLevel.TEE)); tee.onboard()
        assertEquals(201, tee.activate().status)
    }

    // ==================== criterio 4: recuperación desde el backend sin acceso a la clave privada ====================
    @Test fun `C4 la recuperacion se completa desde el backend sin acceder a ninguna clave privada`() = runBlocking<Unit> {
        val f = fx()
        val known = mutableListOf<KeyPair>()
        val oldApp = f.app(f.attestingDevice(ProtectionLevel.TEE, capture = { known += it }))
        val code = oldApp.onboard(); oldApp.activate()
        val oldAct = oldApp.activation!!
        assertEquals(200, f.backend.get("/instances/${oldAct.instanceId}", oldAct.token).status)

        // el dispositivo se perdió: un dispositivo NUEVO recupera con el código
        val newApp = f.app(f.attestingDevice(ProtectionLevel.TEE, capture = { known += it }))
        newApp.citizenRef = oldApp.citizenRef
        val start = f.backend.post("/recovery/start", buildJsonObject { put("citizenRef", JsonPrimitive(oldApp.citizenRef!!)); put("recoveryCode", JsonPrimitive(code)) })
        assertEquals(200, start.status, start.raw)
        val token = start.str("recoveryToken")
        val r = newApp.activate(recoveryToken = token)
        assertEquals(201, r.status, r.raw)
        assertEquals("true", r.obj("recovery")["reissueRequired"]!!.jsonPrimitive.content)
        assertContains(r.obj("recovery")["revokedInstances"].toString(), oldAct.instanceId)

        // la cartera anterior quedó revocada y la nueva activa; solo una activa
        assertEquals("INSTANCE_REVOKED", f.backend.get("/instances/${oldAct.instanceId}", oldAct.token).error)
        assertEquals(200, f.backend.get("/instances/${newApp.activation!!.instanceId}", newApp.activation!!.token).status)
        assertEquals(1, f.count("SELECT count(*) FROM wallet_instances WHERE status = 'ACTIVE'"))
        assertEquals(1, f.count("SELECT count(*) FROM wallet_instances WHERE revoked_reason = 'RECOVERY'"))
        assertContains(f.backend.get("/instances/${newApp.activation!!.instanceId}/audit", newApp.activation!!.token).raw, "INSTANCE_ACTIVATED_BY_RECOVERY")

        // EVIDENCIA: el backend jamás recibió ni guardó una clave privada (ninguna forma textual aparece en NINGUNA tabla)
        assertEquals(2, known.size)
        assertTrue(f.service.containsText(oldApp.citizenRef!!), "control positivo: la búsqueda sí encuentra lo que está en la base")
        assertTrue(f.service.containsText(oldAct.wiaThumbprint), "control positivo: la huella (pública) sí está guardada")
        known.forEach { kp -> privateForms(kp).forEach { assertFalse(f.service.containsText(it), "la base contiene material de la clave privada ($it)") } }
    }

    @Test fun `C4 el token de recuperacion es de un solo uso y solo sirve para su ciudadano`() = runBlocking<Unit> {
        val f = fx()
        val a = f.app(f.softwareDevice()); val codeA = a.onboard(); a.activate()
        val other = f.app(f.softwareDevice()); other.onboard()
        val tokenA = f.backend.post("/recovery/start", buildJsonObject { put("citizenRef", JsonPrimitive(a.citizenRef!!)); put("recoveryCode", JsonPrimitive(codeA)) }).str("recoveryToken")
        // otro ciudadano intenta usar el token ajeno
        val steal = other.activate(recoveryToken = tokenA)
        assertEquals("RECOVERY_TOKEN_INVALID", steal.error)
        // el legítimo lo usa una vez
        val n1 = f.app(f.softwareDevice()); n1.citizenRef = a.citizenRef
        assertEquals(201, n1.activate(recoveryToken = tokenA).status)
        val n2 = f.app(f.softwareDevice()); n2.citizenRef = a.citizenRef
        assertEquals("RECOVERY_TOKEN_INVALID", n2.activate(recoveryToken = tokenA).error)
    }

    @Test fun `C4 el codigo de recuperacion se protege contra fuerza bruta`() = runBlocking<Unit> {
        val f = fx(attempts = 3)
        val a = f.app(f.softwareDevice()); val code = a.onboard(); a.activate()
        fun bad() = buildJsonObject { put("citizenRef", JsonPrimitive(a.citizenRef!!)); put("recoveryCode", JsonPrimitive("AAAA-BBBB-CCCC-DDDD-EEEE")) }
        repeat(3) { assertEquals(401, f.backend.post("/recovery/start", bad()).status) }
        // ya bloqueado: ni siquiera el código correcto sirve
        val locked = f.backend.post("/recovery/start", buildJsonObject { put("citizenRef", JsonPrimitive(a.citizenRef!!)); put("recoveryCode", JsonPrimitive(code)) })
        assertEquals(429, locked.status); assertEquals("RECOVERY_LOCKED", locked.error)
        assertTrue(f.count("SELECT count(*) FROM wallet_audit WHERE action = 'RECOVERY_DENIED'") >= 4)
        // el código nunca se guarda en claro
        assertFalse(f.service.containsText(code.replace("-", "")))
        // ciudadano inexistente: misma respuesta genérica (no revela qué existe)
        assertEquals("INVALID_RECOVERY_CODE", f.backend.post("/recovery/start", buildJsonObject { put("citizenRef", JsonPrimitive("11111111-1111-1111-1111-111111111111")); put("recoveryCode", JsonPrimitive("X")) }).error)
    }

    // ==================== transversal ====================
    @Test fun `la auditoria del backend es inalterable`() = runBlocking<Unit> {
        val f = fx()
        val a = f.app(f.softwareDevice()); a.onboard(); a.activate()
        val e = runCatching { f.exec("DELETE FROM wallet_audit") }.exceptionOrNull()
        assertTrue(e != null && "append-only" in (e.message ?: ""))
        val e2 = runCatching { f.exec("UPDATE wallet_audit SET action = 'X'") }.exceptionOrNull()
        assertTrue(e2 != null && "append-only" in (e2.message ?: ""))
    }

    @Test fun `el inventario de endpoints del backend solo contiene gestion de cartera y de DID, sin protocolos de credenciales`() {
        val f = fx()
        val kinds = f.catalog.entries.map { it.kind }.toSet()
        assertEquals(setOf("WALLET_MANAGEMENT", "DID_MANAGEMENT", "OPS"), kinds)
        val words = listOf("credential", "present", "issue", "oid4", "vp_token", "offer")
        assertTrue(f.catalog.entries.none { e -> words.any { it in e.path.lowercase() } }, "el backend de cartera no debe exponer emisión ni presentación: ${f.catalog.entries.map { it.path }}")
        assertNotEquals(0, f.catalog.entries.size)
    }

    @Test fun `guarda - todo metodo de prueba devuelve void para que JUnit lo ejecute`() {
        val bad = Erso001WalletTest::class.java.declaredMethods.filter { it.isAnnotationPresent(Test::class.java) && it.returnType != Void.TYPE }.map { it.name }
        assertTrue(bad.isEmpty(), "Estas pruebas NO se ejecutarían: $bad")
    }
}
