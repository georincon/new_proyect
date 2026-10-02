# Hallazgos de análisis externo — ERSo 2026-005
## Publicación del DID Document bajo did:web

| | |
|---|---|
| Proyecto propio | Identidad Digital Soberana SSI (`/home/geovani/Descargas/generic/bitacora/new_proyect`) |
| Fecha del análisis | 2 de octubre de 2026 |
| Carpetas analizadas | `/home/geovani/Workspace/Wallet/Wallet`, `…/Verifier`, `…/Issuer`, `…/Trust`, `…/librerias` |
| Versión del informe | Actualizado el 2 de octubre de 2026: se añaden los hallazgos de la carpeta `librerias` y el análisis a fondo del gestor de listas de confianza |
| Cobertura de esta ERSo | **Parcial (regla DID → ruta y sintaxis de DID)** |
| Método | Lectura de código y documentación; **no se compiló ni se ejecutó** ninguno de los proyectos externos |

---

## 1. Resumen

La ERSo 005 trata de publicar un DID Document bajo `did:web`. En los proyectos externos solo el módulo VDR de `av-app-android-wallet-ui` toca el tema, y lo hace de forma mínima: convierte el DID en una ruta de archivo y trata el documento como JSON opaco. No hay servidor HTTPS que sirva `did.json`, ni validación del contenido. **Complemento (carpeta `librerias`):** la biblioteca de OpenID4VP trae la sintaxis formal de DID y DID URL, y las de OpenID4VP y SD-JWT definen el **contrato de búsqueda de claves** que consumiría un documento publicado bajo `did:web`.

## 2. Proyectos citados en este informe

| Carpeta | Proyecto | Qué es |
|---|---|---|
| Wallet | `av-app-android-wallet-ui` | App Android de verificación de edad (versión derivada de la anterior); incluye el módulo `vdr-extension-logic` |
| Verifier | `eudi-wallet-rfcs` | Especificaciones EWC (RFC 001 a 013) |
| librerias | `eudi-lib-jvm-openid4vp-kt` | Biblioteca de OpenID4VP (autenticación del verificador, cifrado de respuesta, DID) |
| librerias | `eudi-lib-jvm-sdjwt-kt` | Biblioteca de SD-JWT y SD-JWT VC (verificación, métodos de clave del emisor) |

## 3. Hallazgos

La columna *Criterio* indica a qué criterio de aceptación de la ERSo se aplica el hallazgo (el detalle está en la sección 4).

| # | Proyecto (carpeta/proyecto) | Archivo o lugar | Qué se encontró | Relación con nuestro proyecto | Criterio |
|---|---|---|---|---|---|
| 1 | `Wallet/av-app-android-wallet-ui` | `vdr-extension-logic/…/registry/DidWebAdapter.kt` | `toRelativePath(did)` quita el prefijo `did:web:`, separa por `:` y construye `web/<partes>/did.json`; exige que ninguna parte esté vacía. | Equivale a nuestro `DidMethodAdapter`/`DidWebAdapter`; no se vio tratamiento del puerto codificado (`%3A`) ni la validación de segmentos de ruta que hace el nuestro. | C1 |
| 2 | `Wallet/av-app-android-wallet-ui` | `…/registry/DidMethodAdapter.kt` | Interfaz por método DID (`method`, `toRelativePath`); el registro elige el adaptador según el método. | La misma idea de adaptador por método que usa nuestro registro. | C1 |
| 3 | `Wallet/av-app-android-wallet-ui` | `…/model/DidDocument.kt` | El registro trata el documento como JSON **opaco**; solo exige el campo `id` para saber dónde archivarlo. | Nuestro registro valida con lista blanca (sin claves privadas ni datos civiles, `id` igual al DID). | C3, C4 |
| 4 | `Wallet/av-app-android-wallet-ui` | `vdr-extension-logic/vdr-data/registry/web/ejemplo.org/did.json` | Documento de ejemplo con `id`, `verificationMethod: []` y un campo libre `nota`. | Un documento con una propiedad no estándar se acepta; en el nuestro se rechazaría (`UNKNOWN_PROPERTY`). | C3 |
| 5 | `Wallet/av-app-android-wallet-ui` | `vdr-extension-logic/README.md` | Declara explícitamente fuera de alcance "publicar el DID Document en un dominio público real vía did:web (ERSo 2026-005/006)". | Confirma que ese módulo no cubre la ERSo 005. | C1 |
| 6 | `Verifier/eudi-wallet-rfcs` | `ewc-rfc012-trust-mechanism.md (§4.2.2)` | Describe cómo resolver un `kid` que sea DID: analizar el DID, elegir resolutor, obtener el documento y verificarlo. | Respalda la regla DID → documento que implementa nuestra ERSo 005. | C1, C2 |
| 7 | `librerias/eudi-lib-jvm-openid4vp-kt` | `src/main/kotlin/…/openid4vp/internal/DID.kt` | `DID.parse` valida con la expresión `did:<método>:(segmento:)*segmento`, donde cada segmento admite letras, dígitos, `.`, `-`, `_` o `%XX`. `AbsoluteDIDUrl` añade ruta, consulta y fragmento. | Equivale a la regla de segmentos de nuestro `DidWeb`; sirve para contrastar casos como `..` o espacios. | C4 |
| 8 | `librerias/eudi-lib-jvm-openid4vp-kt` | `…/openid4vp/Config.kt (línea ~62, `LookupPublicKeyByDIDUrl`)` | Contrato de resolución: dado un DID URL devuelve la clave pública o falla. | Es el contrato que cumple nuestro `VerificationKeyResolver`. | C1, C2 |
| 9 | `librerias/eudi-lib-jvm-sdjwt-kt` | `…/sdjwt/vc/SdJwtVcVerifierFactory.kt (`LookupPublicKeysFromDIDDocument`)` | Contrato de resolución: dado un DID y un DID URL opcional devuelve las claves públicas o `null`. | El documento publicado debe ser consumible por este contrato. | C1, C2 |

## 4. Aplicación a los criterios de aceptación de la ERSo 005

Para cada criterio: lo que exige el PDF de la ERSo, dónde está nuestra evidencia y qué hallazgos externos se le aplican.

### Criterio 1. El documento se sirve por HTTPS en la URL calculada.

| | |
|---|---|
| Evidencia que pide la ERSo | Lectura desde la URL pública. |
| Nuestra evidencia | `INFORME-FINAL-ERSo-2026-005 §18` |
| Hallazgos aplicables | #1 (`Wallet/av-app-android-wallet-ui`); #2 (`Wallet/av-app-android-wallet-ui`); #5 (`Wallet/av-app-android-wallet-ui`); #6 (`Verifier/eudi-wallet-rfcs`); #8 (`librerias/eudi-lib-jvm-openid4vp-kt`); #9 (`librerias/eudi-lib-jvm-sdjwt-kt`) |
| Qué aporta el análisis | Solo se encontró la regla DID → ruta de archivo; ningún proyecto externo sirve el documento por HTTPS. |

### Criterio 2. El documento contiene la clave pública correcta.

| | |
|---|---|
| Evidencia que pide la ERSo | Documento publicado y comparación de hash. |
| Nuestra evidencia | `INFORME-FINAL-ERSo-2026-005 §19` |
| Hallazgos aplicables | #6 (`Verifier/eudi-wallet-rfcs`); #8 (`librerias/eudi-lib-jvm-openid4vp-kt`); #9 (`librerias/eudi-lib-jvm-sdjwt-kt`) |
| Qué aporta el análisis | No se halló comparación de hash del documento. La especificación de confianza describe el paso de resolver y verificar el documento. Dos bibliotecas definen el contrato que consumiría la clave publicada en el documento. |

### Criterio 3. El documento no expone claves privadas ni datos civiles.

| | |
|---|---|
| Evidencia que pide la ERSo | Revisión del contenido publicado. |
| Nuestra evidencia | `INFORME-FINAL-ERSo-2026-005 §20` |
| Hallazgos aplicables | #3 (`Wallet/av-app-android-wallet-ui`); #4 (`Wallet/av-app-android-wallet-ui`) |
| Qué aporta el análisis | El único registro externo acepta cualquier JSON con `id`: sin validación del contenido. Es un contraste con nuestra lista blanca. |

### Criterio 4. Un id desajustado se rechaza y el camino base opera con la extensión desactivada.

| | |
|---|---|
| Evidencia que pide la ERSo | Pruebas negativas y de regresión. |
| Nuestra evidencia | `INFORME-FINAL-ERSo-2026-005 §21` |
| Hallazgos aplicables | #3 (`Wallet/av-app-android-wallet-ui`); #7 (`librerias/eudi-lib-jvm-openid4vp-kt`) |
| Qué aporta el análisis | El registro externo no compara el `id` con el DID de la ruta; tampoco se halló una prueba negativa equivalente. La biblioteca de OpenID4VP valida la sintaxis del DID antes de resolver, igual que nuestro validador de `id`. |

## 5. Diferencias de diseño frente a nuestro proyecto

| Tema | Proyectos externos | Nuestro proyecto |
|---|---|---|
| Servir el documento | No existe (solo archivo en disco) | nginx con HTTPS, `Content-Type: application/did+json`, `ETag` y `X-Content-Hash` |
| Contenido admitido | Cualquier JSON con `id` | Lista blanca; sin claves privadas ni datos civiles |
| Ruta | `web/<dominio>/<ruta>/did.json` | `<ruta>/did.json` bajo el dominio, con reglas de segmento |
| Clave | No se trata | Multikey P-256 (`zDn…`) |

## 6. Qué aprender y qué hacer con esto

* Hallazgo claro: ninguno de los proyectos externos cubre la publicación HTTPS ni la validación de contenido; esa parte es original de nuestro proyecto.
* La regla de ruta de `DidWebAdapter` sirve como comparación sencilla para explicar la regla DID → URL.
* Recordar que `did:web` no aparece en los emisores, verificadores ni listas de confianza de referencia: usan certificados (ver informes de las ERSo 006 y 007).
* Prueba de interoperabilidad sencilla a proponer: usar `LookupPublicKeyByDIDUrl`/`LookupPublicKeysFromDIDDocument` con un adaptador sobre nuestro `did-resolver` y verificar contra un documento publicado por nuestro registro.

## 7. Límites de este informe

* Todo proviene de **lectura de código**; el comportamiento real no se verificó ejecutando.
* Se citan archivos y, cuando se anotó, números de línea (con `~` si son aproximados); las versiones de los proyectos son las que había en disco el 2 de octubre de 2026.
* "No encontré" significa que las búsquedas realizadas no lo hallaron, no que no exista en ninguna parte del código.
* Los proyectos de referencia EUDI se declaran a sí mismos como versiones de desarrollo no aptas para producción.
