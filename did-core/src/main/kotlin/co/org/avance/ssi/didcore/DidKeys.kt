package co.org.avance.ssi.didcore

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

object DidKeys {
    fun generateP256(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun multikey(pair: KeyPair): String = Multikey.encodeP256(pair.public as ECPublicKey)
}
