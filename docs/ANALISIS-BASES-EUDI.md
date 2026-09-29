# Análisis de las bases EUDI disponibles en la máquina (para las ERSo 001, 002 y 003)

Fuente: exploración **de solo lectura** hecha el 29-sep-2026 sobre `/home/geovani/Descargas/generic/`. Fue automatizada y **no se verificó cada afirmación línea por línea**; las rutas `archivo:línea` sirven para comprobarlas.

## Qué hay

| Carpeta | Qué es |
|---|---|
| `eudi-app-android-wallet-ui` | Capa de UI del wallet Android de referencia; delega la criptografía a `eudi-lib-android-wallet-core` **0.30.2** (multipaz) |
| `eudi-srv-pid-issuer` | Emisor de referencia (Spring Boot 4.1.0, Kotlin 2.4.0): PID y otras credenciales |
| `eudi-generic-issuer-local-build` | Fork del anterior con el perfil `uis` (credencial académica, Keycloak) |
| `lissi` | **No es código**: capturas de pantalla (13 PNG) y una foto de una prueba de cartera y de la credencial UIS |

## Wallet Android — brechas frente a las ERSo 001 y 003

| Tema | Hallazgo |
|---|---|
| Generación de claves | No se generan en este repo. `core-logic/src/{demo,dev}/.../WalletCoreConfigImpl.kt:60-63` → `configureDocumentKeyCreation(userAuthenticationRequired = false, …, useStrongBoxForKeys = true)` |
| Nivel de protección | **No se detecta ni se declara.** 0 coincidencias de `KeyInfo`, `isInsideSecureHardware`, `securityLevel`, Play Integrity. `useStrongBoxForKeys` es solo una **petición**; el otro uso de Keystore (`KeystoreController.kt:129-133`) cae a TEE/software **sin avisar** |
| Attestation | Delegada a un backend: `WalletCoreAttestationProvider.kt:27-50`; `WalletAttestationRepository.kt:53-61` (`/wallet-instance-attestation/jwk`, `/key-attestation/jwk-set`). **No hay verificación de la cadena X.509** en el repo |
| Instancia de cartera | Solo el endpoint de attestation. **No hay** registro, activación, revocación, recuperación ni respaldo propios |
| Formatos | `mso_mdoc` y `dc+sd-jwt` (`RegistrationCertificateExtensions.kt:39-40`); no `vc+sd-jwt` |
| Algoritmos | Solo `ES256` en OpenID4VP (`WalletCoreConfigImpl.kt:80`) |
| **DID** | **Ninguno.** 0 coincidencias de `did:`, `DidDocument`, `Multikey` |

Informe de Keystore del equipo (`informe-almacenamiento-keystore.docx`, plugin `tauri-plugin-wallet`): tres usos del Keystore (claves de credencial vía multipaz, clave de la Wallet Instance Attestation, clave AES de sellado de Stronghold), `useStrongboxForKeys = true` por defecto con caída silenciosa a TEE, `userAuthenticationRequired = false` por defecto. Indica que el nivel real "se verifica del lado del servidor leyendo la key attestation" — exactamente lo que implementa el Wallet Backend de este proyecto.

## Emisores EUDI — diferencias de perfil frente a este proyecto (ERSo 002)

| Tema | Emisores EUDI | Este proyecto |
|---|---|---|
| Versión OpenID4VCI | 1.0 final (`OpenId4VciSpec.VERSION = "v1"`); `nonce_endpoint`; JWE de solicitud/respuesta; metadata firmada | 1.0; `nonce_endpoint`; sin JWE |
| Librería | **No usan** `eudi-lib-jvm-openid4vci-kt`; implementan a mano con Nimbus JOSE 10.9.1, nimbus-oauth2 y `eudi-lib-jvm-sdjwt-kt` 0.20.1 | Implementación propia sobre JDK + `com.upokecenter:cbor` |
| Formato SD-JWT VC | `dc+sd-jwt` (typ `dc+sd-jwt`), sin `vc+sd-jwt` | igual; `vc+sd-jwt` rechazado por defecto |
| Endpoint `token` | No lo exponen: son *resource server* con tokens opacos + **DPoP**, introspección contra Keycloak | Token propio, sin DPoP |
| Algoritmos | Prueba del titular: `ES256`; el emisor firma con ES256/384/512 según la curva | Solo ES256 |
| Pruebas de posesión | `jwt` (con key attestation) y `attestation` | `jwt` |
| Clave del emisor | Keystore JKS (`issuer-keys.jks`), `kid` + cadena **`x5c`**; JWKS y `/.well-known/jwt-vc-issuer` | Archivo de laboratorio; **DID** en el VDR |
| DID | Ninguno | Descubrimiento por DID |
| Endpoints no estándar | Solo administración y UI (`/issuer/credentialsOffer/create`, `/generate`, `/login`, …) | Solo administración (`/admin/offers`, `/admin/verifier/*`) |

## Conclusión práctica

1. Las ERSo 001 y 003 **no tienen base en el wallet Android**: ni nivel de protección, ni instancia, ni DID. Todo eso es trabajo nuevo, y la parte del dispositivo (Android Keystore, key attestation) **sigue pendiente de implementarse y probarse en un dispositivo** (ver `ANDROID-REFERENCIA.md`).
2. Para la ERSo 002, lo más valioso que falta es una **prueba cruzada real** de nuestro cliente contra `eudi-srv-pid-issuer`. Para lograrlo habría que añadir soporte de `x5c`, DPoP y, según la configuración, JWE.
