# Versiones normativas y referencias

Las ERSo piden trabajar "contra las versiones normativas fijadas". Este proyecto se apoya en las siguientes especificaciones. **Antes de citarlas en un informe formal, confirme en cada enlace la versión y el estado vigentes (varias son borradores o candidatas a recomendación y cambian).**

| Tema | Especificación | Enlace | Se usa en |
|---|---|---|---|
| Modelo de datos y operaciones DID | W3C DIDs v1.1 | https://www.w3.org/TR/did-1.1/ | 005, 006, 007, 008 |
| Base estable del modelo | W3C DID Core 1.0 | https://www.w3.org/TR/did-core/ | 005, 007 |
| Registros de propiedades y métodos | W3C DID Specification Registries | https://www.w3.org/TR/did-spec-registries/ | 004 |
| Resolver vs. dereferenciar; errores | W3C DID Resolution v1 | https://www.w3.org/TR/did-resolution/ | 007 |
| Binding HTTP(S) de la resolución | DID Resolution — HTTP(S) Binding | https://www.w3.org/TR/did-resolution/#http-binding | 007 |
| Método `did:web` (ruta = dominio + segmentos) | did:web Method Specification (W3C CCG) | https://w3c-ccg.github.io/did-method-web/ | 004, 005, 006, 007 |
| Concepto de VDR | VC Data Model 1.1 / 2.0 — Verifiable Data Registry | https://www.w3.org/TR/vc-data-model/#dfn-verifiable-data-registry · https://www.w3.org/TR/vc-data-model-2.0/#verifiable-data-registries | 004 |
| Clave P-256 como `Multikey` / multibase | W3C Controlled Identifiers (Multikey) | https://www.w3.org/TR/cid-1.0/ (verificar que sea la versión vigente) | 005, 006, 007 |
| API de gestión de DIDs (patrón) | DIF DID Registration | https://identity.foundation/did-registration/ | 004, 006, 008 |
| Resolución vía servicio web (patrón) | DIF Universal Resolver | https://github.com/decentralized-identity/universal-resolver | 007 |
| Firma de pruebas | RFC 7515 (JWS) · RFC 7518 (ES256) | https://www.rfc-editor.org/rfc/rfc7515 | 007, 008 |
| Autenticación entre servicios | RFC 6749 §4.4 (client credentials) · RFC 8705 (mTLS) | https://www.rfc-editor.org/rfc/rfc6749 · https://www.rfc-editor.org/rfc/rfc8705 | 006, 008 |
| Concurrencia optimista | RFC 9110 (`If-Match`, `ETag`) | https://www.rfc-editor.org/rfc/rfc9110 | 008 |
| **Emisión de credenciales** | OpenID for Verifiable Credential Issuance **1.0** | https://openid.net/specs/openid-4-verifiable-credential-issuance-1_0.html | 002 |
| **Presentación de credenciales** | OpenID for Verifiable Presentations **1.0** (incluye DCQL) | https://openid.net/specs/openid-4-verifiable-presentations-1_0.html | 002 |
| Divulgación selectiva | SD-JWT (IETF OAuth WG; **verificar si ya es RFC y su número**) | https://datatracker.ietf.org/doc/draft-ietf-oauth-selective-disclosure-jwt/ | 002 |
| Credenciales SD-JWT | SD-JWT-based Verifiable Credentials (SD-JWT VC; tipo `dc+sd-jwt`) | https://datatracker.ietf.org/doc/draft-ietf-oauth-sd-jwt-vc/ | 002 |
| mdoc / carné móvil | ISO/IEC 18013-5 (de pago; consultar la norma) | https://www.iso.org/standard/69084.html | 002 |
| Firma COSE | RFC 9052 (estructura) · RFC 9053 (algoritmos) | https://www.rfc-editor.org/rfc/rfc9052 | 002 |
| CBOR | RFC 8949 | https://www.rfc-editor.org/rfc/rfc8949 | 002 |
| Huella de una JWK | RFC 7638 (JWK Thumbprint) | https://www.rfc-editor.org/rfc/rfc7638 | 001, 002, 003 |
| Formato de JWK | RFC 7517 | https://www.rfc-editor.org/rfc/rfc7517 | 002 |
| Metadatos del servidor de autorización | RFC 8414 | https://www.rfc-editor.org/rfc/rfc8414 | 002 |
| **Key attestation de Android** | Verify hardware-backed key pairs with Key Attestation (esquema `KeyDescription`, OID `1.3.6.1.4.1.11129.2.1.17`) | https://developer.android.com/privacy-and-security/security-key-attestation · https://source.android.com/docs/security/features/keystore/attestation | 001 |
| Claves en hardware (Android) | Android Keystore · `KeyInfo` · StrongBox | https://developer.android.com/privacy-and-security/keystore | 001 |
| Claves en hardware (iOS) | Secure Enclave | https://developer.apple.com/documentation/security/protecting-keys-with-the-secure-enclave | 001 |
| Autenticación ligada al dispositivo (concepto) | WebAuthn / FIDO2 | https://www.w3.org/TR/webauthn-3/ | 001 |
| Rol del titular | VC Data Model 2.0 — *holder* | https://www.w3.org/TR/vc-data-model-2.0/ | 001, 003 |
| Ley y arquitectura de la cartera europea | Reglamento (UE) 2024/1183 (eIDAS 2) · EUDI Architecture and Reference Framework (**verificar versión vigente**) | https://eur-lex.europa.eu/eli/reg/2024/1183/oj · https://eudi.dev/ | 001, 002 |

**Respaldo/restauración de estado:** no está estandarizado por W3C; depende del registro. Aquí se define un formato propio (`vdr-backup/1`, JSON con checksum SHA-256 del contenido canónico).

## Lo que estas especificaciones NO dicen (y por eso lo decidió el proyecto)

* Cómo se protege el canal de escritura → decisión del proyecto: OAuth2 client-credentials + mTLS.
* Cómo se prueba la posesión de la clave al publicar → decisión: desafío de un solo uso firmado (JWS ES256) sobre `(desafío, hash del documento)`.
* Cómo se conserva el historial → decisión: tabla de versiones *append-only*. `did:web` en sí **no** guarda historial.
* Recuperación ante pérdida de clave → **no resuelta** (la ERSo 008 lo advierte): un historial desactivado exige una instancia nueva.


## Detalles de las ERSo 001, 002 y 003 escritos de memoria del texto de la especificación (CONFIRMAR)

El proyecto implementó estos detalles a partir de lo que se recordaba del texto final de cada especificación. No se contrastaron línea a línea:

| Detalle | Dónde se usa | Qué confirmar |
|---|---|---|
| Respuesta de `credential`: `{ "credentials": [ { "credential": … } ] }` | `IssuerService.credential` | Estructura exacta en OpenID4VCI 1.0 §8.3 |
| Solicitud con `proofs: { "jwt": [ … ] }` (plural) y `credential_configuration_id` | `IssuerService.credential` | §8.2 |
| `nonce_endpoint` (POST) y respuesta `{ "c_nonce": … }` | `IssuerService.newNonce` | §7 |
| Cabecera de la prueba: `typ = openid4vci-proof+jwt`, clave en `jwk`, `aud` = identificador del emisor | `verifyProof` | Anexo F |
| `mso_mdoc`: la credencial es el `IssuerSigned` CBOR en base64url | `IssuerService`, `CredentialWallet` | Anexo A.2 |
| Metadatos: `credential_metadata.display`, `cryptographic_binding_methods_supported = ["jwk"]` / `["cose_key"]`, algoritmos COSE como enteros | `IssuerService.metadata` | §12.2 |
| Oferta por valor `openid-credential-offer://?credential_offer=…`; `tx_code` con `input_mode` y `length` | `createOffer` | §4 |
| Prefijo de cliente `redirect_uri:`; `aud` del KB-JWT = identificador de cliente completo | `CredentialConfig.verifierClientId` | OpenID4VP 1.0 §5.9 y §14 |
| `vp_token` como objeto `{ <id de consulta>: [presentaciones] }` | `VerifierService.receive` | OpenID4VP 1.0 §8 |
| SD-JWT: divulgación `[sal, nombre, valor]`, `_sd_alg`, `sd_hash`, KB-JWT `typ = kb+jwt` | `SdJwtVc.kt` | SD-JWT y SD-JWT VC |
| COSE_Sign1: `Sig_structure = ["Signature1", protegida, aad, payload]`, ES256 = −7, `kid` = etiqueta 4 | `Mdoc.kt` | RFC 9052 §4.4 |
| Digest de mdoc sobre `#6.24(bstr .cbor IssuerSignedItem)` | `Mdoc.kt` | ISO/IEC 18013-5 §9.1.2 |
| Contexto DID `https://www.w3.org/ns/did/v1` (el validador acepta también `…/v1.1`) | `DidDocumentBuilder` | Contexto que corresponde a DIDs v1.1 |
| KeyDescription: campos y números de etiqueta (`purpose` 1, `algorithm` 2, `ecCurve` 10, `origin` 702, `rootOfTrust` 704) | `KeyAttestation.kt` | Esquema vigente de Android Key Attestation |

## Lo que estas especificaciones NO dicen (y por eso lo decidió el proyecto) — ampliación

* Cómo se **deriva el identificador** de un `did:web` del titular → decisión: huella RFC 7638 de la clave pública bajo `titulares/`.
* Cómo se **recupera una cuenta** sin recuperar la clave → decisión: código de recuperación + token de un solo uso + revocación de la cartera anterior.
* Cómo se descubre la clave del emisor de un SD-JWT/mdoc → decisión del proyecto: por **DID** (EUDI usa `x5c`).
* Qué nivel de protección mínimo se acepta → política configurable (`WALLET_MIN_LEVEL`).
