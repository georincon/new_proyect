package co.org.avance.ssi.credentials

import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * mdoc (ISO/IEC 18013-5) en su forma `IssuerSigned`, transportado por OpenID4VCI (`mso_mdoc`).
 *
 * DESVIACIÓN DECLARADA DEL PERFIL ISO: la clave del emisor se identifica con `kid` (COSE etiqueta 4, URL DID) resuelto vía el VDR
 * del proyecto, y no con la cadena de certificados `x5chain` (etiqueta 33) que exige ISO 18013-5. Un lector ISO estricto lo rechazaría.
 * Alcance de la ERSo 002: EMITIR (para poder probar) y RECIBIR/VERIFICAR/ALMACENAR. La presentación mdoc (DeviceResponse) no se implementa.
 */
object Mdoc {
    const val TAG_ENCODED_CBOR = 24
    const val COSE_ALG = 1
    const val COSE_KID = 4
    private fun cbor(v: Any): CBORObject = CBORObject.FromObject(v)

    fun encodeItem(inner: ByteArray): ByteArray = CBORObject.FromObjectAndTag(cbor(inner), TAG_ENCODED_CBOR).EncodeToBytes()

    /** Estructura a firmar de COSE_Sign1 (RFC 9052 §4.4). */
    fun sigStructure(protectedBytes: ByteArray, payload: ByteArray): ByteArray =
        CBORObject.NewArray().apply {
            Add("Signature1"); Add(cbor(protectedBytes)); Add(cbor(ByteArray(0))); Add(cbor(payload))
        }.EncodeToBytes()

    fun coseKey(key: ECPublicKey): CBORObject {
        val j = Jwk.fromPublic(key)
        return CBORObject.NewMap().apply {
            Add(cbor(1), cbor(2)) // kty = EC2
            Add(cbor(-1), cbor(1)) // crv = P-256
            Add(cbor(-2), cbor(B64.decode((j["x"] as kotlinx.serialization.json.JsonPrimitive).content)))
            Add(cbor(-3), cbor(B64.decode((j["y"] as kotlinx.serialization.json.JsonPrimitive).content)))
        }
    }

    fun publicFromCose(k: CBORObject): ECPublicKey {
        if (k[cbor(1)]?.AsInt32() != 2 || k[cbor(-1)]?.AsInt32() != 1) throw CredentialException("UNSUPPORTED_KEY", "COSE_Key debe ser EC2 P-256")
        val x = k[cbor(-2)].GetByteString(); val y = k[cbor(-3)].GetByteString()
        return Jwk.toPublic(kotlinx.serialization.json.buildJsonObject {
            put("kty", kotlinx.serialization.json.JsonPrimitive("EC")); put("crv", kotlinx.serialization.json.JsonPrimitive("P-256"))
            put("x", kotlinx.serialization.json.JsonPrimitive(B64.encode(x))); put("y", kotlinx.serialization.json.JsonPrimitive(B64.encode(y)))
        })
    }
}

class MdocIssuer(
    private val kid: String,
    private val signer: (ByteArray) -> ByteArray,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
) {
    /** Devuelve los bytes CBOR de `IssuerSigned`. `namespaces`: espacio de nombres → (identificador → valor). */
    fun issue(docType: String, namespaces: Map<String, Map<String, Any>>, deviceKey: ECPublicKey, validity: Duration = Duration.ofDays(30)): ByteArray {
        val valueDigests = CBORObject.NewMap()
        val nameSpaces = CBORObject.NewMap()
        for ((ns, elements) in namespaces) {
            val digests = CBORObject.NewMap()
            val items = CBORObject.NewArray()
            elements.entries.forEachIndexed { id, (name, value) ->
                val item = CBORObject.NewMap().apply {
                    Add("digestID", id)
                    Add("random", CBORObject.FromObject(ByteArray(16).also(random::nextBytes)))
                    Add("elementIdentifier", name)
                    Add("elementValue", CBORObject.FromObject(value))
                }
                val itemBytes = Mdoc.encodeItem(item.EncodeToBytes())
                digests.Add(CBORObject.FromObject(id), CBORObject.FromObject(B64.sha256(itemBytes)))
                items.Add(CBORObject.DecodeFromBytes(itemBytes))
            }
            valueDigests.Add(ns, digests)
            nameSpaces.Add(ns, items)
        }
        val now = clock.instant()
        fun tdate(i: Instant) = CBORObject.FromObjectAndTag(i.toString(), 0)
        val mso = CBORObject.NewMap().apply {
            Add("version", "1.0")
            Add("digestAlgorithm", CredentialProfiles.MDOC_DIGEST_ALG)
            Add("valueDigests", valueDigests)
            Add("deviceKeyInfo", CBORObject.NewMap().apply { Add("deviceKey", Mdoc.coseKey(deviceKey)) })
            Add("docType", docType)
            Add("validityInfo", CBORObject.NewMap().apply { Add("signed", tdate(now)); Add("validFrom", tdate(now)); Add("validUntil", tdate(now.plus(validity))) })
        }
        val payload = Mdoc.encodeItem(mso.EncodeToBytes())
        val protectedBytes = CBORObject.NewMap().apply { Add(CBORObject.FromObject(Mdoc.COSE_ALG), CBORObject.FromObject(CredentialProfiles.COSE_ALG_ES256)) }.EncodeToBytes()
        val signature = signer(Mdoc.sigStructure(protectedBytes, payload))
        require(signature.size == 64) { "COSE ES256 exige r||s de 64 bytes" }
        val issuerAuth = CBORObject.NewArray().apply {
            Add(CBORObject.FromObject(protectedBytes))
            Add(CBORObject.NewMap().apply { Add(CBORObject.FromObject(Mdoc.COSE_KID), CBORObject.FromObject(kid.toByteArray())) })
            Add(CBORObject.FromObject(payload))
            Add(CBORObject.FromObject(signature))
        }
        return CBORObject.NewMap().apply { Add("nameSpaces", nameSpaces); Add("issuerAuth", issuerAuth) }.EncodeToBytes()
    }
}

class VerifiedMdoc(
    val docType: String,
    val issuerKid: String,
    val elements: Map<String, Map<String, String>>,
    val deviceKey: ECPublicKey,
    val validFrom: Instant,
    val validUntil: Instant,
)

/** RECEPCIÓN de un mdoc: verifica la firma COSE del emisor, los digests de cada elemento y la vigencia. */
class MdocVerifier(
    private val resolver: IssuerKeyResolver,
    private val clock: Clock = Clock.systemUTC(),
    private val log: ExecutionLog? = null,
) {
    suspend fun verify(issuerSigned: ByteArray): VerifiedMdoc {
        val top = try { CBORObject.DecodeFromBytes(issuerSigned) } catch (e: Exception) { throw CredentialException("INVALID_MDOC", "CBOR mal formado") }
        if (top.type != CBORType.Map || top["issuerAuth"] == null || top["nameSpaces"] == null) throw CredentialException("INVALID_MDOC", "Falta issuerAuth o nameSpaces")
        val auth = top["issuerAuth"]
        if (auth.type != CBORType.Array || auth.size() != 4) throw CredentialException("INVALID_MDOC", "issuerAuth debe ser un COSE_Sign1")
        val protectedBytes = auth[0].GetByteString()
        val alg = CBORObject.DecodeFromBytes(protectedBytes)[CBORObject.FromObject(Mdoc.COSE_ALG)]?.AsInt32()
        if (alg != CredentialProfiles.COSE_ALG_ES256) throw CredentialException("UNSUPPORTED_ALG", "Solo se admite COSE ES256 (alg=-7); recibido $alg")
        val kid = auth[1][CBORObject.FromObject(Mdoc.COSE_KID)]?.GetByteString()?.let { String(it) }
            ?: throw CredentialException("MISSING_KID", "El COSE_Sign1 no lleva kid")
        val payload = auth[2].GetByteString()
        val signature = auth[3].GetByteString()

        val issuerKey = try { resolver.resolve(kid) } catch (e: CredentialException) { throw e } catch (e: Exception) {
            throw CredentialException("ISSUER_KEY_UNRESOLVED", "No se pudo resolver la clave del emisor: ${e.message}")
        }
        val ok = try {
            Signature.getInstance("SHA256withECDSAinP1363Format").apply { initVerify(issuerKey); update(Mdoc.sigStructure(protectedBytes, payload)) }.verify(signature)
        } catch (e: Exception) { false }
        if (!ok) throw CredentialException("INVALID_ISSUER_SIGNATURE", "La firma COSE del emisor no es válida")

        val msoTagged = CBORObject.DecodeFromBytes(payload)
        if (!msoTagged.HasMostOuterTag(Mdoc.TAG_ENCODED_CBOR)) throw CredentialException("INVALID_MSO", "El payload debe ser #6.24(bstr)")
        val mso = CBORObject.DecodeFromBytes(msoTagged.UntagOne().GetByteString())
        if (mso["digestAlgorithm"]?.AsString() != CredentialProfiles.MDOC_DIGEST_ALG) throw CredentialException("UNSUPPORTED_DIGEST", "Solo se admite ${CredentialProfiles.MDOC_DIGEST_ALG}")
        val docType = mso["docType"]?.AsString() ?: throw CredentialException("INVALID_MSO", "Falta docType")
        val validity = mso["validityInfo"] ?: throw CredentialException("INVALID_MSO", "Falta validityInfo")
        val from = Instant.parse(validity["validFrom"].AsString()); val until = Instant.parse(validity["validUntil"].AsString())
        val now = clock.instant()
        if (now.isBefore(from.minusSeconds(60))) throw CredentialException("NOT_YET_VALID", "El mdoc aún no es válido")
        if (now.isAfter(until)) throw CredentialException("EXPIRED", "El mdoc expiró")
        val deviceKey = Mdoc.publicFromCose(mso["deviceKeyInfo"]["deviceKey"])

        val digests = mso["valueDigests"]
        val elements = linkedMapOf<String, Map<String, String>>()
        for (ns in top["nameSpaces"].keys) {
            val nsName = ns.AsString()
            val expected = digests[nsName] ?: throw CredentialException("DIGEST_MISSING", "El MSO no tiene digests para '$nsName'")
            val out = linkedMapOf<String, String>()
            val seenIds = mutableSetOf<Int>()
            for (tagged in top["nameSpaces"][ns].values) {
                if (!tagged.HasMostOuterTag(Mdoc.TAG_ENCODED_CBOR)) throw CredentialException("INVALID_ITEM", "Cada elemento debe ser #6.24(bstr)")
                val inner = tagged.UntagOne().GetByteString()
                val item = CBORObject.DecodeFromBytes(inner)
                val id = item["digestID"].AsInt32()
                if (!seenIds.add(id)) throw CredentialException("DUPLICATE_DIGEST_ID", "digestID repetido: $id")
                val wanted = expected[CBORObject.FromObject(id)]?.GetByteString() ?: throw CredentialException("DIGEST_MISSING", "Sin digest para el elemento $id")
                // El digest cubre los bytes exactos del elemento con su etiqueta 24 (se reconstruyen de forma determinista).
                if (!MessageDigest.isEqual(B64.sha256(Mdoc.encodeItem(inner)), wanted)) {
                    throw CredentialException("DIGEST_MISMATCH", "El elemento '${item["elementIdentifier"].AsString()}' fue alterado")
                }
                out[item["elementIdentifier"].AsString()] = item["elementValue"].let { if (it.type == CBORType.TextString) it.AsString() else it.ToJSONString() }
            }
            elements[nsName] = out
        }
        log?.record(Observed("mso_mdoc emisor", CredentialProfiles.FORMAT_MDOC, null, "ES256", mso["digestAlgorithm"].AsString()))
        return VerifiedMdoc(docType, kid, elements, deviceKey, from, until)
    }
}

