package co.org.avance.ssi.didcore

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec

class InvalidKeyException(message: String) : IllegalArgumentException(message)

/**
 * Multikey para P-256: multibase('z' = base58btc) de  multicodec(p256-pub = 0x1200, varint 0x80 0x24) || clave comprimida (33 bytes).
 * Por eso toda clave pública P-256 en Multikey empieza por "zDn".
 */
object Multikey {
    private val P256_PUB_PREFIX = byteArrayOf(0x80.toByte(), 0x24)
    private val spec: ECParameterSpec = AlgorithmParameters.getInstance("EC")
        .apply { init(ECGenParameterSpec("secp256r1")) }
        .getParameterSpec(ECParameterSpec::class.java)
    private val p: BigInteger = (spec.curve.field as ECFieldFp).p

    fun encodeP256(key: ECPublicKey): String {
        val x = key.w.affineX.toFixed32()
        val prefix: Byte = if (key.w.affineY.testBit(0)) 0x03 else 0x02
        return "z" + Base58.encode(P256_PUB_PREFIX + byteArrayOf(prefix) + x)
    }

    fun decodeP256(multibase: String): ECPublicKey {
        if (!multibase.startsWith("z")) throw InvalidKeyException("Multibase no soportado: se esperaba prefijo 'z' (base58btc)")
        val raw = try {
            Base58.decode(multibase.substring(1))
        } catch (e: IllegalArgumentException) {
            throw InvalidKeyException(e.message ?: "Base58 inválido")
        }
        if (raw.size != 35 || raw[0] != P256_PUB_PREFIX[0] || raw[1] != P256_PUB_PREFIX[1]) {
            throw InvalidKeyException("La clave no es una clave pública P-256 en Multikey (multicodec 0x1200, 33 bytes comprimidos)")
        }
        val parity = raw[2].toInt()
        if (parity != 2 && parity != 3) throw InvalidKeyException("Punto comprimido inválido")
        val x = BigInteger(1, raw.copyOfRange(3, 35))
        if (x >= p) throw InvalidKeyException("Coordenada x fuera del campo")
        val rhs = x.pow(3).add(spec.curve.a.multiply(x)).add(spec.curve.b).mod(p)
        var y = rhs.modPow(p.add(BigInteger.ONE).shiftRight(2), p) // p ≡ 3 (mod 4)
        if (y.multiply(y).mod(p) != rhs) throw InvalidKeyException("El punto no pertenece a la curva P-256")
        if (y.testBit(0) != (parity == 3)) y = p.subtract(y)
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), spec)) as ECPublicKey
    }

    private fun BigInteger.toFixed32(): ByteArray {
        val b = toByteArray().let { if (it.size > 1 && it[0].toInt() == 0) it.copyOfRange(1, it.size) else it }
        return ByteArray(32 - b.size) + b
    }
}
