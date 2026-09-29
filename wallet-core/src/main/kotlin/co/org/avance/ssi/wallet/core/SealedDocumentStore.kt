package co.org.avance.ssi.wallet.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

class StoreAccessException(message: String) : RuntimeException(message)

/**
 * Almacén local NO EXPORTABLE del DID Document y su asociación a la instancia de cartera (ERSo 003, paso 5 y criterio 4).
 * En disco solo hay texto cifrado con una clave que no sale del custodio; la instancia y el DID van como datos autenticados (AAD),
 * de modo que mover el archivo a otra instancia o a otro dispositivo lo vuelve ilegible.
 */
class SealedDocumentStore(private val custodian: KeyCustodian, private val dir: Path) {
    private val magic = "WDS1".toByteArray()

    init { Files.createDirectories(dir) }

    private fun file(instanceId: String): Path {
        require(instanceId.matches(Regex("^[A-Za-z0-9-]{1,64}$"))) { "instanceId inválido" }
        return dir.resolve("$instanceId.wds")
    }

    fun save(instanceId: String, did: String, document: JsonObject) {
        val clear = buildJsonObject { put("instanceId", JsonPrimitive(instanceId)); put("did", JsonPrimitive(did)); put("document", document) }.toString().toByteArray()
        Files.write(file(instanceId), magic + custodian.seal(clear, aad(instanceId)))
    }

    /** Devuelve el documento SOLO si el archivo pertenece a `instanceId` y este custodio lo selló. */
    fun load(instanceId: String): Pair<String, JsonObject> {
        val bytes = try { Files.readAllBytes(file(instanceId)) } catch (e: java.nio.file.NoSuchFileException) { throw StoreAccessException("No hay documento para la instancia") }
        if (bytes.size < magic.size || !bytes.copyOfRange(0, magic.size).contentEquals(magic)) throw StoreAccessException("Formato de almacén desconocido")
        val clear = try { custodian.unseal(bytes.copyOfRange(magic.size, bytes.size), aad(instanceId)) } catch (e: Exception) {
            throw StoreAccessException("No se puede abrir: el contenido fue alterado, pertenece a otra instancia o a otro dispositivo")
        }
        val o = Json.parseToJsonElement(String(clear)).jsonObject
        if (o["instanceId"]!!.jsonPrimitive.content != instanceId) throw StoreAccessException("El documento no está asociado a esta instancia")
        return o["did"]!!.jsonPrimitive.content to o["document"]!!.jsonObject
    }

    /** Lo que realmente queda en disco (para la prueba de almacenamiento). */
    fun rawBytes(instanceId: String): ByteArray = Files.readAllBytes(file(instanceId))

    private fun aad(instanceId: String) = "wallet-instance:$instanceId".toByteArray()
}
