package co.org.avance.ssi.vdr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.util.UUID

/** Timestamp de PostgreSQL en ISO-8601 UTC con microsegundos (parseable con Instant.parse). */
private fun ts(col: String) = "to_char($col AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')"

/** Acceso SQL puro a las tablas del VDR. Todas las funciones reciben la conexión de la transacción. */
class RegistryStore {
    // ---------- cuentas y namespaces ----------
    fun upsertAccount(c: Connection, clientId: String, displayName: String) = c.prepareStatement(
        "INSERT INTO entity_accounts(client_id, display_name) VALUES (?, ?) ON CONFLICT (client_id) DO UPDATE SET display_name = EXCLUDED.display_name",
    ).use { it.setString(1, clientId); it.setString(2, displayName); it.executeUpdate() }

    fun accountEnabled(c: Connection, clientId: String): Boolean? = c.prepareStatement("SELECT enabled FROM entity_accounts WHERE client_id = ?").use {
        it.setString(1, clientId); it.executeQuery().use { rs -> if (rs.next()) rs.getBoolean(1) else null }
    }

    /** @return true si se reservó ahora; lanza si el namespace ya pertenece a otra cuenta. */
    fun reserveNamespace(c: Connection, path: String, owner: String, profileJson: String): Boolean {
        val existing = namespaceOwner(c, path)
        if (existing != null) {
            check(existing == owner) { "El namespace '$path' ya está reservado por '$existing'" }
            return false
        }
        c.prepareStatement("INSERT INTO namespaces(path, owner_client_id, profile) VALUES (?, ?, ?::jsonb)").use {
            it.setString(1, path); it.setString(2, owner); it.setString(3, profileJson); it.executeUpdate()
        }
        return true
    }

    // Reserva comodín de UN nivel: la entrada "titulares" + barra + asterisco cubre "titulares/<x>", pero no "titulares/<x>/<y>".
    private fun wildcardOf(path: String): String? = if ('/' in path) path.substringBeforeLast('/') + "/*" else null

    private fun exactOwner(c: Connection, path: String): String? = c.prepareStatement("SELECT owner_client_id FROM namespaces WHERE path = ?").use {
        it.setString(1, path); it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
    }

    private fun exactProfile(c: Connection, path: String): JsonObject? = c.prepareStatement("SELECT profile::text FROM namespaces WHERE path = ?").use {
        it.setString(1, path)
        it.executeQuery().use { rs -> if (rs.next()) Json.parseToJsonElement(rs.getString(1)).jsonObject else null }
    }

    fun namespaceOwner(c: Connection, path: String): String? = exactOwner(c, path) ?: wildcardOf(path)?.let { exactOwner(c, it) }

    fun namespaceProfile(c: Connection, path: String): JsonObject? = exactProfile(c, path) ?: wildcardOf(path)?.let { exactProfile(c, it) }

    /**
     * Si `path` está cubierto por un comodín y aún no tiene fila propia, la crea con el mismo dueño y perfil.
     * Así `did_documents.namespace` (clave foránea) sigue apuntando a un namespace concreto.
     */
    fun materializeNamespace(c: Connection, path: String) {
        if (exactOwner(c, path) != null) return
        val wildcard = wildcardOf(path) ?: return
        val owner = exactOwner(c, wildcard) ?: return
        val profile = exactProfile(c, wildcard) ?: return
        c.prepareStatement("INSERT INTO namespaces(path, owner_client_id, profile) VALUES (?, ?, ?::jsonb) ON CONFLICT (path) DO NOTHING").use {
            it.setString(1, path); it.setString(2, owner); it.setString(3, profile.toString()); it.executeUpdate()
        }
    }

    // ---------- documentos y versiones ----------
    fun findDid(c: Connection, did: String, lock: Boolean = false): DidRow? = c.prepareStatement(
        "SELECT did, namespace, status, current_version, "+ts("created_at")+", "+ts("updated_at")+" FROM did_documents WHERE did = ?" + if (lock) " FOR UPDATE" else "",
    ).use {
        it.setString(1, did)
        it.executeQuery().use { rs -> if (rs.next()) DidRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5), rs.getString(6)) else null }
    }

    fun insertDid(c: Connection, did: String, namespace: String) = c.prepareStatement(
        "INSERT INTO did_documents(did, namespace, status, current_version) VALUES (?, ?, 'ACTIVE', 1)",
    ).use { it.setString(1, did); it.setString(2, namespace); it.executeUpdate() }

    fun updateDid(c: Connection, did: String, status: String, version: Int) = c.prepareStatement(
        "UPDATE did_documents SET status = ?, current_version = ?, updated_at = now() WHERE did = ?",
    ).use { it.setString(1, status); it.setInt(2, version); it.setString(3, did); it.executeUpdate() }

    fun insertVersion(c: Connection, v: VersionRow) = c.prepareStatement(
        "INSERT INTO did_document_versions(did, version, operation, document, hash, actor, operation_id) VALUES (?, ?, ?, ?, ?, ?, ?::uuid)",
    ).use {
        it.setString(1, v.did); it.setInt(2, v.version); it.setString(3, v.operation)
        if (v.document == null) it.setNull(4, Types.VARCHAR) else it.setString(4, v.document)
        it.setString(5, v.hash); it.setString(6, v.actor); it.setString(7, v.operationId); it.executeUpdate()
    }

    private fun version(rs: ResultSet) = VersionRow(
        rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
    )

    private val versionCols = "did, version, operation, document, hash, actor, operation_id::text, "+ts("created_at")+""

    fun version(c: Connection, did: String, version: Int): VersionRow? =
        c.prepareStatement("SELECT $versionCols FROM did_document_versions WHERE did = ? AND version = ?").use {
            it.setString(1, did); it.setInt(2, version); it.executeQuery().use { rs -> if (rs.next()) version(rs) else null }
        }

    fun versions(c: Connection, did: String): List<VersionRow> =
        c.prepareStatement("SELECT $versionCols FROM did_document_versions WHERE did = ? ORDER BY version").use {
            it.setString(1, did); it.executeQuery().use { rs -> buildList { while (rs.next()) add(version(rs)) } }
        }

    /** Última versión creada en o antes del instante `at` (reconstrucción histórica). */
    fun versionAt(c: Connection, did: String, at: Instant): VersionRow? =
        c.prepareStatement("SELECT $versionCols FROM did_document_versions WHERE did = ? AND created_at <= ?::timestamptz ORDER BY version DESC LIMIT 1").use {
            it.setString(1, did); it.setString(2, at.toString()); it.executeQuery().use { rs -> if (rs.next()) version(rs) else null }
        }

    // ---------- desafíos ----------
    fun insertChallenge(c: Connection, id: UUID, nonce: String, did: String, purpose: String, audience: String, clientId: String, ttlSeconds: Long): String =
        c.prepareStatement(
            "INSERT INTO challenges(id, nonce, did, purpose, audience, client_id, expires_at) VALUES (?::uuid, ?, ?, ?, ?, ?, now() + (? * interval '1 second')) RETURNING "+ts("expires_at")+"",
        ).use {
            it.setString(1, id.toString()); it.setString(2, nonce); it.setString(3, did); it.setString(4, purpose)
            it.setString(5, audience); it.setString(6, clientId); it.setLong(7, ttlSeconds)
            it.executeQuery().use { rs -> rs.next(); rs.getString(1) }
        }

    /** Consumo atómico: solo el primero que llega gana (un solo uso). null si no existe, ya se usó o expiró. */
    fun consumeChallenge(c: Connection, id: UUID): ChallengeRow? = c.prepareStatement(
        "UPDATE challenges SET used_at = now() WHERE id = ?::uuid AND used_at IS NULL AND expires_at > now() " +
            "RETURNING id::text, nonce, did, purpose, audience, client_id, "+ts("expires_at")+"",
    ).use {
        it.setString(1, id.toString())
        it.executeQuery().use { rs ->
            if (rs.next()) ChallengeRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)) else null
        }
    }

    // ---------- operaciones ----------
    private val opCols = "id::text, did, purpose, client_id, status, version, hash, public_url, idempotency_key, request_hash, "+ts("created_at")+", "+ts("confirmed_at")+""

    private fun op(rs: ResultSet) = OperationRow(
        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6), rs.getString(7),
        rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12),
    )

    fun insertOperation(c: Connection, o: OperationRow) = c.prepareStatement(
        "INSERT INTO operations(id, did, purpose, client_id, status, version, hash, public_url, idempotency_key, request_hash) VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
    ).use {
        it.setString(1, o.id); it.setString(2, o.did); it.setString(3, o.purpose); it.setString(4, o.clientId); it.setString(5, o.status)
        it.setInt(6, o.version); it.setString(7, o.hash); it.setString(8, o.publicUrl); it.setString(9, o.idempotencyKey); it.setString(10, o.requestHash)
        it.executeUpdate()
    }

    fun setOperationStatus(c: Connection, id: String, status: String) = c.prepareStatement(
        "UPDATE operations SET status = ?, confirmed_at = CASE WHEN ? = 'CONFIRMED' THEN now() ELSE confirmed_at END WHERE id = ?::uuid",
    ).use { it.setString(1, status); it.setString(2, status); it.setString(3, id); it.executeUpdate() }

    fun operation(c: Connection, id: String): OperationRow? = c.prepareStatement("SELECT $opCols FROM operations WHERE id = ?::uuid").use {
        it.setString(1, id); it.executeQuery().use { rs -> if (rs.next()) op(rs) else null }
    }

    fun operationByIdempotency(c: Connection, did: String, key: String): OperationRow? =
        c.prepareStatement("SELECT $opCols FROM operations WHERE did = ? AND idempotency_key = ?").use {
            it.setString(1, did); it.setString(2, key); it.executeQuery().use { rs -> if (rs.next()) op(rs) else null }
        }

    // ---------- auditoría ----------
    fun audit(c: Connection, actor: String, action: String, did: String? = null, version: Int? = null, detail: JsonObject = JsonObject(emptyMap())) =
        c.prepareStatement("INSERT INTO audit_log(actor, action, did, version, detail) VALUES (?, ?, ?, ?, ?::jsonb)").use {
            it.setString(1, actor); it.setString(2, action)
            if (did == null) it.setNull(3, Types.VARCHAR) else it.setString(3, did)
            if (version == null) it.setNull(4, Types.INTEGER) else it.setInt(4, version)
            it.setString(5, detail.toString()); it.executeUpdate()
        }

    fun auditFor(c: Connection, did: String?): List<AuditRow> = c.prepareStatement(
        "SELECT id, "+ts("audit_log.at")+", actor, action, did, version, detail::text FROM audit_log " + (if (did != null) "WHERE did = ? " else "") + "ORDER BY id",
    ).use { ps ->
        if (did != null) ps.setString(1, did)
        ps.executeQuery().use { rs ->
            buildList { while (rs.next()) add(AuditRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6).takeUnless { rs.wasNull() }, rs.getString(7))) }
        }
    }

    // ---------- respaldo / restauración (ERSo 004) ----------
    companion object {
        /** Orden respeta las llaves foráneas. */
        val BACKUP_TABLES = listOf("entity_accounts", "namespaces", "did_documents", "did_document_versions", "operations", "audit_log")
    }

    private val exportOrder = mapOf(
        "entity_accounts" to "client_id", "namespaces" to "path", "did_documents" to "did",
        "did_document_versions" to "did, version", "operations" to "created_at, id", "audit_log" to "id",
    )

    fun exportTable(c: Connection, table: String): JsonArray = c.createStatement().use { st ->
        st.executeQuery("SELECT * FROM $table ORDER BY ${exportOrder.getValue(table)}").use { rs ->
            val md = rs.metaData
            JsonArray(buildList {
                while (rs.next()) add(JsonObject((1..md.columnCount).associate { i -> md.getColumnName(i) to (rs.getString(i)?.let(::JsonPrimitive) ?: JsonNull) }))
            })
        }
    }

    fun isEmpty(c: Connection, table: String): Boolean = c.createStatement().use { st ->
        st.executeQuery("SELECT NOT EXISTS (SELECT 1 FROM $table)").use { rs -> rs.next(); rs.getBoolean(1) }
    }

    /** JDBC reporta bigserial/serial para columnas autoincrementales; para castear valores hay que usar el tipo real. */
    private fun castType(name: String) = when (name) { "bigserial" -> "int8"; "serial" -> "int4"; else -> name }

    /** Inserta filas exportadas; los tipos se recuperan de los metadatos de la tabla (todo viaja como texto y se castea). */
    fun importTable(c: Connection, table: String, rows: JsonArray): Int {
        if (rows.isEmpty()) return 0
        val types = c.createStatement().use { st ->
            st.executeQuery("SELECT * FROM $table LIMIT 0").use { rs -> (1..rs.metaData.columnCount).associate { rs.metaData.getColumnName(it) to castType(rs.metaData.getColumnTypeName(it)) } }
        }
        val cols = types.keys.toList()
        val sql = "INSERT INTO $table (${cols.joinToString()}) VALUES (${cols.joinToString { "?::" + types.getValue(it) }}) ON CONFLICT DO NOTHING"
        var n = 0
        c.prepareStatement(sql).use { ps ->
            for (row in rows) {
                val o = row.jsonObject
                cols.forEachIndexed { i, col ->
                    val v: JsonElement? = o[col]
                    if (v == null || v is JsonNull) ps.setNull(i + 1, Types.VARCHAR) else ps.setString(i + 1, (v as JsonPrimitive).contentOrNull)
                }
                n += ps.executeUpdate()
            }
        }
        if (table == "audit_log") c.createStatement().use { it.execute("SELECT setval(pg_get_serial_sequence('audit_log','id'), COALESCE((SELECT max(id) FROM audit_log), 1))") }
        return n
    }
}
