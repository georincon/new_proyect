package co.org.avance.ssi.vdr

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

class ApiException(
    val status: HttpStatusCode,
    val code: String,
    message: String,
    val details: List<String> = emptyList(),
) : RuntimeException(message)

enum class Purpose {
    CREATE, UPDATE, DEACTIVATE;

    companion object {
        fun parse(value: String?): Purpose? = entries.firstOrNull { it.name == value }
    }
}

data class DidRow(val did: String, val namespace: String, val status: String, val currentVersion: Int, val createdAt: String, val updatedAt: String)

@Serializable
data class VersionRow(
    val did: String,
    val version: Int,
    val operation: String,
    val document: String?,
    val hash: String,
    val actor: String,
    val operationId: String,
    val createdAt: String,
)

data class ChallengeRow(
    val id: String, val nonce: String, val did: String, val purpose: String, val audience: String,
    val clientId: String, val expiresAt: String,
)

@Serializable
data class OperationRow(
    val id: String,
    val did: String,
    val purpose: String,
    val clientId: String,
    val status: String,
    val version: Int,
    val hash: String,
    val publicUrl: String,
    val idempotencyKey: String,
    val requestHash: String,
    val createdAt: String,
    val confirmedAt: String?,
)

@Serializable
data class AuditRow(val id: Long, val at: String, val actor: String, val action: String, val did: String?, val version: Int?, val detail: String)

// ---- vistas de la API ----
@Serializable
data class ChallengeView(val challengeId: String, val nonce: String, val did: String, val purpose: String, val audience: String, val expiresAt: String)

@Serializable
data class OperationView(
    val operationId: String,
    val did: String,
    val purpose: String,
    val status: String,
    val version: Int,
    val hash: String,
    val publicUrl: String,
    val replayed: Boolean = false,
)

@Serializable
data class StateView(val did: String, val at: String, val status: String, val version: Int, val hash: String, val document: String?)

sealed interface PublicDoc {
    data class Found(val bytes: ByteArray, val version: Int, val hash: String) : PublicDoc
    data object Gone : PublicDoc
    data object NotFound : PublicDoc
}
