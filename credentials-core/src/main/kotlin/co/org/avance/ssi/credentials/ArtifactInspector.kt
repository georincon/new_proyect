package co.org.avance.ssi.credentials

import co.org.avance.ssi.didcore.Jws
import com.upokecenter.cbor.CBORObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Lee un artefacto YA PRODUCIDO (no lo que el código dice haber hecho) y devuelve lo que realmente contiene.
 * Alimenta el [ExecutionLog]: la evidencia del criterio 2 de la ERSo 002 sale de aquí.
 */
object ArtifactInspector {
    fun sdJwt(compact: String, role: String): Observed {
        val parts = SdJwtParts.parse(compact)
        val jws = Jws.parse(parts.jwt)
        val payload = Json.parseToJsonElement(String(jws.payload)).jsonObject
        return Observed(role, CredentialProfiles.FORMAT_SD_JWT_VC, jws.typ, jws.header["alg"]!!.jsonPrimitive.content, payload["_sd_alg"]?.jsonPrimitive?.content)
    }

    fun mdoc(issuerSigned: ByteArray, role: String): Observed {
        val auth = CBORObject.DecodeFromBytes(issuerSigned)["issuerAuth"]
        val alg = CBORObject.DecodeFromBytes(auth[0].GetByteString())[CBORObject.FromObject(Mdoc.COSE_ALG)].AsInt32()
        val mso = CBORObject.DecodeFromBytes(CBORObject.DecodeFromBytes(auth[2].GetByteString()).UntagOne().GetByteString())
        return Observed(role, CredentialProfiles.FORMAT_MDOC, null, if (alg == CredentialProfiles.COSE_ALG_ES256) "ES256" else "COSE($alg)", mso["digestAlgorithm"].AsString())
    }
}
