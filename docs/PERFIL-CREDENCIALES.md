# Perfil por formato de credencial (ERSo 2026-002, paso 1)

Documento de perfil: **lo que el proyecto declara**. La fuente única en código es `credentials-core/.../Profiles.kt` (`CredentialProfiles`); un registro de ejecución (`ExecutionLog`) lee los artefactos realmente producidos y verifica que coinciden con esto (criterio 2).

> Los detalles marcados con ⚠️ están escritos de memoria del texto de las especificaciones y **deben confirmarse** contra la versión exacta adoptada por el equipo (ver `VERSIONES-NORMATIVAS.md`).

## 1. Formatos y algoritmos admitidos

| | `dc+sd-jwt` (SD-JWT VC) | `mso_mdoc` (mdoc) |
|---|---|---|
| Artefacto | JWS compacto + divulgaciones: `jwt~d1~d2~[kb-jwt]` | CBOR `IssuerSigned` (`nameSpaces` + `issuerAuth` = COSE_Sign1 con el MSO) |
| Algoritmo de firma | **ES256** (ECDSA P-256 + SHA-256) | **ES256** (COSE `alg = -7`) |
| Cabecera | `typ = dc+sd-jwt`, `alg = ES256`, `kid = <DID>#key-1` | protegida `{1: -7}`; no protegida `{4: kid}` |
| Digest de divulgación | `_sd_alg = sha-256` | `digestAlgorithm = SHA-256` |
| Clave del titular | `cnf.jwk` (EC P-256, sin `d`) | `deviceKeyInfo.deviceKey` (COSE_Key EC2 P-256) |
| Prueba del titular al presentar | KB-JWT `typ = kb+jwt`, ES256, `{iat, aud, nonce, sd_hash}` | **no implementada** (DeviceResponse) |
| Identificador del formato en OpenID4VCI | `dc+sd-jwt` | `mso_mdoc` |
| Tipo de credencial | `vct = urn:avance:credential:academic:1` | `docType = org.avance.academic.1` |

Todo lo demás se **rechaza**: otros algoritmos (ES384, EdDSA, RS256…), `typ = vc+sd-jwt` (perfil anterior; solo con `acceptLegacyTyp=true`), `_sd_alg` distinto de sha-256.

## 2. Protocolos (sin protocolos propios)

* Emisión: **OpenID4VCI 1.0**, flujo de **código pre-autorizado** (con `tx_code` opcional). Endpoints: metadatos del emisor, metadatos del servidor de autorización, `token`, `nonce`, `credential`.
* Presentación: **OpenID4VP 1.0**, `response_mode = direct_post`, prefijo de cliente `redirect_uri:`, consulta **DCQL**. ⚠️
* Prueba de posesión en la emisión: `proofs: { jwt: [ … ] }`, JWT `typ = openid4vci-proof+jwt`, `alg = ES256`, clave pública en la cabecera `jwk`, `aud` = identificador del emisor, `nonce` = `c_nonce` de un solo uso. ⚠️
* Respuesta de credencial: `{ "credentials": [ { "credential": "…" } ] }`; en `mso_mdoc`, el `IssuerSigned` en **base64url**. ⚠️

## 3. Roles de clave (matriz)

| Clave | Vive en | Firma | Se publica en |
|---|---|---|---|
| Emisor (`ISSUER_SIGNING`) | Signature Service del emisor | credencial (JWT y COSE_Sign1) | DID Document del emisor en el VDR (`assertionMethod`) |
| Titular (`HOLDER_PROOF`) | Hardware seguro del dispositivo | prueba de posesión (JWT de OpenID4VCI, KB-JWT) | dentro de la credencial (`cnf` / `deviceKeyInfo`) |

Las dos verificaciones son independientes: la clave del titular no valida la firma del emisor y viceversa (`Erso002CredentialsTest › C4`).

## 4. Desviaciones declaradas respecto a los perfiles de referencia

| Tema | Perfil de referencia (EUDI / ISO) | Este proyecto | Consecuencia |
|---|---|---|---|
| Clave del emisor | `x5c` (SD-JWT) / `x5chain` (mdoc) | `kid` = URL DID resuelta en el VDR | Un verificador EUDI/ISO estándar no valida estas credenciales sin adaptación |
| Prueba de posesión | `jwt` con key attestation, o `attestation` | `jwt` sin key attestation | El emisor no comprueba el hardware de la clave del titular al emitir |
| Cifrado JWE | Exigido por algunos emisores | No | Metadatos y respuestas en claro sobre TLS |
| Token | AS externo (Keycloak) + DPoP | AS propio del emisor, sin DPoP | Tokens de portador |
| mdoc en presentación | DeviceResponse / SessionTranscript | No | Solo se emite y recibe mdoc |
