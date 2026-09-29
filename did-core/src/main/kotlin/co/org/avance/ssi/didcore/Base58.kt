package co.org.avance.ssi.didcore

import java.math.BigInteger

/** Base58 (alfabeto Bitcoin), la codificación que usa multibase con el prefijo 'z'. */
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val BASE = BigInteger.valueOf(58)

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        val leadingZeros = input.takeWhile { it.toInt() == 0 }.size
        var number = BigInteger(1, input)
        val sb = StringBuilder()
        while (number.signum() > 0) {
            val qr = number.divideAndRemainder(BASE)
            sb.append(ALPHABET[qr[1].toInt()])
            number = qr[0]
        }
        repeat(leadingZeros) { sb.append('1') }
        return sb.reverse().toString()
    }

    fun decode(input: String): ByteArray {
        var number = BigInteger.ZERO
        for (c in input) {
            val digit = ALPHABET.indexOf(c)
            require(digit >= 0) { "Carácter no válido en Base58: '$c'" }
            number = number.multiply(BASE).add(BigInteger.valueOf(digit.toLong()))
        }
        var bytes = number.toByteArray()
        if (bytes.size > 1 && bytes[0].toInt() == 0) bytes = bytes.copyOfRange(1, bytes.size)
        if (number.signum() == 0) bytes = ByteArray(0)
        val leadingOnes = input.takeWhile { it == '1' }.length
        return ByteArray(leadingOnes) + bytes
    }
}
