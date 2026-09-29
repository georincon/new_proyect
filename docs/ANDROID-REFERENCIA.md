# Referencia de implementación para Android (ERSo 2026-001) — ⚠️ NO COMPILADA NI PROBADA EN UN DISPOSITIVO

Este documento es un **diseño**, no código entregado. Describe cómo implementar el contrato `KeyCustodian` de `wallet-core` sobre **Android Keystore**, que es lo que falta para que los criterios 2 y 3 de la ERSo 001 dejen de apoyarse en un custodio simulado. Los fragmentos se escribieron a partir de la documentación pública de Android; **hay que compilarlos, ejecutarlos en un dispositivo real y contrastarlos con la API del nivel mínimo que soporte la app** antes de darlos por buenos.

`wallet-core` es Kotlin/JVM puro (sin dependencias de Android), así que un módulo Android puede depender de él e implementar `KeyCustodian`.

## 1. Lo que la implementación real debe garantizar

| Requisito del contrato | Cómo se logra en Android |
|---|---|
| La clave nace dentro del custodio | `KeyPairGenerator.getInstance("EC", "AndroidKeyStore")` con `KeyGenParameterSpec` (no se importa ninguna clave) |
| No exportable | Las claves de `AndroidKeyStore` no exponen el material: `privateKey.encoded == null` |
| Nivel de protección real | Después de crear la clave, leer `KeyInfo` y **no** fiarse de haber "pedido" StrongBox |
| Sin *fallback* silencioso | Si la política exige `STRONGBOX`/`TEE` y el dispositivo ofrece menos, **borrar la clave y fallar** |
| Evidencia para el backend | `setAttestationChallenge(nonce)` y devolver `keyStore.getCertificateChain(alias)` |
| Firma ES256 (r‖s) | Android devuelve la firma ECDSA en **DER**; hay que convertirla a 64 bytes r‖s antes de armar el JWS/COSE |

## 2. Esbozo (sin compilar)

```kotlin
class AndroidKeystoreCustodian(private val context: Context) : KeyCustodian {
    override val name = "android-keystore"
    private val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    // El máximo se determina probando, no suponiendo. Aquí, un valor conservador hasta comprobarlo con KeyInfo.
    override val maxProtection: ProtectionLevel
        get() = if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)) ProtectionLevel.STRONGBOX else ProtectionLevel.TEE

    override fun generate(alias: String, policy: KeyPolicy, attestationChallenge: ByteArray?): KeyDescriptor {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply { attestationChallenge?.let { setAttestationChallenge(it) } }
            .apply { if (strongBox) setIsStrongBoxBacked(true) }        // API 28+
            .build()

        val wantsStrongBox = policy.minimumLevel == ProtectionLevel.STRONGBOX
        try {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec(wantsStrongBox)) }.generateKeyPair()
        } catch (e: StrongBoxUnavailableException) {
            if (wantsStrongBox) throw ProtectionBelowPolicyException(policy.minimumLevel, ProtectionLevel.TEE)
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec(false)) }.generateKeyPair()
        }

        // NIVEL REAL: se lee de la clave creada
        val priv = ks.getKey(alias, null) as PrivateKey
        val info = KeyFactory.getInstance(priv.algorithm, "AndroidKeyStore").getKeySpec(priv, KeyInfo::class.java)
        val level = when {
            Build.VERSION.SDK_INT >= 31 -> when (info.securityLevel) {                    // API 31+
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> ProtectionLevel.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> ProtectionLevel.TEE
                else -> ProtectionLevel.SOFTWARE
            }
            info.isInsideSecureHardware -> ProtectionLevel.TEE                            // API 23–30: no distingue StrongBox
            else -> ProtectionLevel.SOFTWARE
        }
        if (level.rank < policy.minimumLevel.rank) { ks.deleteEntry(alias); throw ProtectionBelowPolicyException(policy.minimumLevel, level) }
        // ... construir KeyDescriptor (thumbprint, Multikey, level, origin = GENERATED, exportable = false)
    }

    override fun sign(alias: String, data: ByteArray): ByteArray {
        val der = Signature.getInstance("SHA256withECDSA").apply { initSign(ks.getKey(alias, null) as PrivateKey); update(data) }.sign()
        return derToRawRs(der)   // hay que implementarlo: DER SEQUENCE{INTEGER r, INTEGER s} -> 32 bytes r || 32 bytes s
    }

    override fun attestation(alias: String) = ks.getCertificateChain(alias)?.let { AttestationEvidence(it.map { c -> c.encoded }) }
    // seal/unseal: clave AES-GCM en Keystore (KeyGenParameterSpec con PURPOSE_ENCRYPT|DECRYPT, BLOCK_MODE_GCM); ver VaultKeyProvider.kt del plugin
}
```

## 3. Prueba de no exportabilidad en dispositivo (plantilla instrumentada, sin ejecutar)

```kotlin
@Test fun claveNoExportable() {
    val c = AndroidKeystoreCustodian(ApplicationProvider.getApplicationContext())
    c.generate("t-noexp", KeyPolicy(ProtectionLevel.TEE), "desafio".toByteArray())
    val priv = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("t-noexp", null) as PrivateKey
    assertNull(priv.encoded)                                            // el material no se puede leer
    assertFalse(priv is ECPrivateKey && priv.s != null)                 // no expone el escalar privado
    val info = KeyFactory.getInstance(priv.algorithm, "AndroidKeyStore").getKeySpec(priv, KeyInfo::class.java)
    assertTrue(info.isInsideSecureHardware)
    assertNotNull(c.attestation("t-noexp"))                             // y hay evidencia para el backend
}
```

Evidencia para el criterio 2: el resultado de esta prueba en al menos un dispositivo con TEE y otro con StrongBox, más la activación exitosa contra el Wallet Backend con la cadena real y las raíces de Google configuradas.

## 4. Puntos de integración con el wallet existente (según el informe de Keystore del equipo)

* La clave de la **Wallet Instance Attestation** ya se crea con `AndroidKeystoreCreateKeySettings.Builder(challenge)` (`GestorClaveDeWallet.kt`): ya incrusta un desafío. Falta que **el servidor verifique la cadena** (lo que hace `KeyAttestationVerifier`) y que el dispositivo **lea y declare** `KeyInfo`.
* Las claves de credencial las crea `eudi-lib-android-wallet-core` con `useStrongboxForKeys = true` (petición). Para cumplir el criterio 3 hay que **leer el nivel resultante** de cada clave y enviarlo al backend, o pasar a una política que falle si no se alcanza el nivel exigido.
* **Wallet Instance Attestation (JWT):** el wallet EUDI espera que un *wallet provider* le entregue un JWT de attestation (`/wallet-instance-attestation/jwk`). El Wallet Backend de este proyecto **no emite ese JWT**: activa la instancia y verifica el nivel, pero la emisión de la WIA queda **pendiente**.
* `userAuthenticationRequired` (biometría/PIN por firma) está en `false` por defecto en el plugin; la ERSo no lo exige, pero conviene decidirlo.
