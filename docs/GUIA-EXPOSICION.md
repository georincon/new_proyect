# Guía para entender y exponer el proyecto

Objetivo: que pueda explicar **qué se construyó, por qué, y cómo se prueba** cada criterio de aceptación, sin leer el código.

---

## 1. La idea en 60 segundos

Una entidad (por ejemplo Avance, como emisor de credenciales) necesita que **cualquiera pueda comprobar sus claves públicas**. Para eso publica un archivo JSON, el **DID Document**, en una dirección web fija y segura:

`did:web:civica-desarrollo.avance.org.co:entidades:avance`  →  `https://civica-desarrollo.avance.org.co/entidades/avance/did.json`

El proyecto construye tres cosas:

1. **Un registro (VDR)** que guarda esos documentos y los sirve por HTTPS — ERSo 004/005/006.
2. **Un ciclo de vida controlado**: crear, actualizar (rotar claves), desactivar, con historial auditable — ERSo 008.
3. **Un consumidor** que, dado un DID, descarga el documento, lo valida y comprueba firmas — ERSo 007.

Todo es **opcional y aislado**: con el registro apagado, el piloto base funciona igual (criterio 3 de la ERSo 004).

## 2. Glosario mínimo

| Término | Qué es, en una frase |
|---|---|
| **DID** | Identificador con forma `did:<método>:<id>`. No depende de una autoridad central. |
| **DID Document** | El JSON que dice: "estas son las claves públicas de este DID y para qué sirven". |
| **did:web** | Método DID cuyo "registro" es un servidor web: el DID se convierte en una URL. |
| **VDR** | Verifiable Data Registry: el lugar donde se publican y consultan los DID Documents. Puede ser blockchain o, como aquí, un servicio web. |
| **P-256 / ES256** | Curva elíptica y algoritmo de firma (ECDSA con SHA-256) que usamos. |
| **Multikey** | Forma estándar de escribir una clave pública como texto. Las P-256 empiezan por `zDn…`. |
| **JWS** | Formato compacto de firma (`cabecera.contenido.firma`). Nuestras "pruebas" son JWS ES256. |
| **Hash (sha256)** | Huella del contenido. Si cambia un solo byte, cambia la huella. |
| **mTLS** | TLS donde también el *cliente* presenta certificado. Identifica qué servicio escribe. |
| **Client credentials** | Forma OAuth2 de que un servicio pida un token con su id y secreto. |
| **Desafío (nonce)** | Valor aleatorio de un solo uso. Firmarlo prueba que se posee la clave *ahora*. |
| **If-Match / versión esperada** | "Escribo solo si la versión actual es N". Evita pisar cambios de otro. |
| **Clave de idempotencia** | Identificador de una petición para poder reintentarla sin duplicar. |
| **Append-only** | Tabla donde solo se agregan filas; nada se edita ni se borra. Es el historial. |

## 3. Dónde encaja en el ecosistema EUDI (con una advertencia)

* **eIDAS 2** (Reglamento UE 2024/1183) es la ley; exige la cartera de identidad europea. No menciona DIDs ni blockchain.
* **ARF** (Architecture and Reference Framework) es la especificación técnica. Su modelo de confianza base se apoya en certificados y listas de confianza (Trusted Lists), con formatos mdoc y SD-JWT VC.
* Los **DID / VDR** de estas ERSo son una **extensión opcional** sobre ese camino base. Por eso todo está apagado por defecto.

> **Advertencia.** El documento interno "Identidad Digital EUDI Wallet" presenta W3C DID como estándar de base del ARF. Mi lectura del ARF es la de arriba (DID como opción, no como base). **Confirmen contra la versión vigente del ARF** antes de afirmarlo ante terceros. Además, ese documento usa `JsonWebKey2020` en su ejemplo, mientras que la ERSo 005 exige `Multikey`: aquí se siguió la ERSo.

## 4. Cómo se relacionan las cinco ERSo

```
                 ERSo 004  ── despliegue aislado, adaptador did:web, backup/restore, "apagado por defecto"
                     │  (la base sobre la que corren las demás)
        ┌────────────┼──────────────┐
   ERSo 005      ERSo 006       ERSo 008
   publicar      publicar       ciclo de vida
   (laboratorio) (institución,  (crear/actualizar/
                 escritura      desactivar + traza)
                 autenticada)
        └────────────┼──────────────┘
                     │  lo que se publica lo lee…
                 ERSo 007  ── consumidor de solo lectura: resolver + verificar
```

Orden natural de estudio: **005** (qué es el documento) → **004** (dónde vive) → **006** (quién puede escribir) → **008** (cómo cambia en el tiempo) → **007** (quién lo consume).

## 5. Recorrido de una publicación (lo más importante para explicar)

Ejemplo: Avance publica su documento por primera vez.

```
Entidad (did-tools)                     nginx                 vdr-service              PostgreSQL
      │ 1. token (id + secreto) ──mTLS──▶ verifica certificado ─▶ valida credenciales
      │ ◀──────────────── access_token (JWT con permiso sobre SU namespace)
      │ 2. "quiero CREAR este DID" ─────────────────────────────▶ ¿precondiciones? (cuenta, namespace propio,
      │                                                            perfil, canal mTLS)  → si falla, NO hay desafío
      │ ◀──────────────── desafío: nonce de un solo uso, tipo=CREATE, audiencia=…
      │ 3. arma el documento (solo claves PÚBLICAS, id = el DID)
      │    firma { nonce, hash(documento), tipo, did } con su clave privada  (prueba de posesión)
      │ 4. PUT documento + prueba   If-Match: 0   Idempotency-Key: <uuid> ──▶ valida perfil del documento
      │                                                            consume el desafío (atómico)
      │                                                            verifica la firma con la clave del documento
      │                                                            escribe versión 1 + auditoría ──────────▶ guarda
      │ ◀──────────────── versión, hash, URL pública (estado PENDING)
      │                                          5. el servicio LEE la URL pública y compara el hash
      │                                             → CONFIRMED  (o sigue PENDING si no responde a tiempo)
```

Frase clave para el grupo: **"no se da por publicado hasta que se lee de vuelta desde la URL pública y el hash coincide"**.

## 6. Ciclo de vida

```
 (no existe) ──CREATE──▶ ACTIVE v1 ──UPDATE──▶ ACTIVE v2 ──UPDATE──▶ … ──DEACTIVATE──▶ DEACTIVATED (terminal)
                                                                                          │
                                                        no vuelve a ACTIVE: requiere una instancia NUEVA (otro namespace)
```

* **Rotar la clave**: el UPDATE lo firma la clave *vigente*; el documento nuevo trae la clave nueva. Desde ese momento solo la nueva puede escribir.
* **Desactivar**: el documento deja de servirse (HTTP 410) pero el historial se conserva.
* **Reconstruir el pasado**: `GET …/state?at=<instante>` devuelve cómo estaba el DID en ese momento.

## 7. Qué ataque bloquea cada control

| Amenaza | Control | Dónde se prueba |
|---|---|---|
| Alguien escribe sin ser una entidad registrada | mTLS obligatorio + token de servicio | ERSo 006 C2 |
| Una entidad escribe en el namespace de otra | Permiso por namespace | ERSo 006 C2 |
| Robo del token sin el certificado | El certificado debe corresponder a la entidad del token | ERSo 006 C2 |
| Repetir una petición capturada | Desafío de un solo uso | ERSo 008 C2 |
| Publicar un documento distinto del firmado | La prueba firma el **hash** del documento | ERSo 008 |
| Tomar el control cambiando la clave | El UPDATE lo firma la clave *vigente* | ERSo 008 |
| Dos escrituras pisándose | `If-Match` (versión esperada) | ERSo 008 C4 |
| Reintento que duplica versiones | Clave de idempotencia | ERSo 008 |
| Publicar claves privadas o datos civiles | Lista blanca de propiedades + detección | ERSo 005 C3 |
| `id` que no corresponde a la URL | Rechazo por `ID_MISMATCH` (productor y consumidor) | ERSo 005 C4, 007 C3 |
| Reescribir el historial | Tabla *append-only* (trigger en PostgreSQL) | ERSo 008 C7 |
| Perder el registro | Respaldo con checksum + restauración verificada | ERSo 004 C2 |
| Que el consumidor escriba | Interfaz de solo lectura + solo GET | ERSo 007 C4 |

## 8. Guion de demostración (≈10 minutos)

Requisito: stack levantado (`cd deploy && docker compose up -d`).

1. **Camino base**: `curl --cacert deploy/certs/ca.crt --resolve civica-desarrollo.avance.org.co:8443:127.0.0.1 https://civica-desarrollo.avance.org.co:8443/health`
2. **Correr todo**: `bash scripts/e2e.sh` — muestra los 121 PASS (56 de las ERSo 004–008 y 65 de las 001–003). Explique bloque a bloque (cada título es una ERSo).
3. **Mostrar un documento publicado** (`evidencias/work/avance.served.json`) y señalar: `id`, `verificationMethod` (Multikey `zDn…`), `authentication`, `assertionMethod`.
4. **Mostrar la traza** (`evidencias/work/versions.json` y el bloque "Traza de auditoría" del log).
5. **Apagar la extensión** (`VDR_ENABLED=false docker compose up -d --force-recreate vdr`) y repetir el paso 1: el camino base sigue; `did.json` da 404.

## 9. Preguntas que probablemente le harán

**¿Por qué did:web y no blockchain?** Lo fijan las ERSo (no se integra ION ni anclajes) y es lo más simple de operar. A cambio, la confianza depende del DNS, del HTTPS y del operador del dominio.

**¿Por qué no publicar un DID por ciudadano?** *(Respuesta original de las ERSo 004–008; **matizada por la ERSo 003**, ver §11.7.)* Un identificador público y persistente permite correlacionar a la persona entre contextos. Las *entidades* siguen sin poder crear DID de ciudadanos (ERSo 005/006). Pero el diagrama de arquitectura del equipo hace que el **Wallet Backend** publique el DID del titular en el VDR: ese riesgo ahora es una decisión de diseño que hay que asumir y mitigar, no algo que el sistema impide.

**¿Rotar la clave da validez histórica?** No por sí solo (la ERSo 008 lo advierte). Aquí el historial permite *reconstruir* qué clave estaba vigente en cada momento, pero el consumidor de la ERSo 007 valida contra el documento **actual**. Validar una firma antigua contra la clave de su época es trabajo futuro.

**¿Y si se pierde la clave privada?** No hay recuperación automática ni debe inventarse guardando algo público. Se desactiva el historial y se crea una instancia nueva.

**¿Qué pasa si nginx no responde al confirmar?** La operación queda `PENDING` y no se da por publicada; `POST /operations/{id}/reconcile` relee y confirma o marca `SUPERSEDED`.

**¿Es seguro confiar en los encabezados de mTLS?** Solo porque el puerto de la aplicación no se publica y nginx los sobrescribe siempre. Si se expusiera el puerto 8081, dejaría de ser seguro. Está documentado como supuesto.

## 10. Lo que NO se hizo (dígalo usted primero)

* No se desplegó en el dominio real ni se probó desde internet; el TLS es de laboratorio (CA propia).
* No hay validación histórica de firmas ni endpoint público de historial.
* No hay limitación de tasa (rate limiting) ni rotación automática de secretos.
* Multikey/Base58 son implementación propia, sin contrastar contra vectores oficiales.
* Las pruebas de aceptación funcionales formales (plantilla de pruebas y firmas de la tabla de actividades) las hacen las personas responsables; aquí se aportan las evidencias técnicas.


---

# 11. Ampliación: cartera, credenciales y DID del titular (ERSo 001, 002 y 003)

## 11.1 La idea en 60 segundos

Hasta la ERSo 008 solo publicábamos las claves **de las entidades**. Ahora aparece el **ciudadano** (titular) y lo que él hace con sus claves:

1. **Su cartera** guarda una clave privada que **nunca sale del dispositivo** (ERSo 001). El servidor no la custodia: solo **coordina** y comprueba, leyendo la *attestation*, **qué nivel de protección tiene de verdad** esa clave.
2. **Su DID** nace en la cartera, derivado de su clave pública (ERSo 003). Si quiere hacerlo público, el Wallet Backend lo publica en el VDR — pero **la firma la pone el dispositivo**, no el servidor.
3. **Sus credenciales** las emite una entidad (Avance) y quedan **ligadas a la clave del titular**; el titular las presenta revelando solo lo necesario (ERSo 002).

```
   DISPOSITIVO DEL TITULAR                 SERVIDORES DEL PROYECTO                                    VDR
 ┌───────────────────────────┐
 │ Holder App                │  TLS  ┌───────────────┐  mTLS+OAuth2   ┌───────────────┐
 │  ├ SSI SDK  (wallet-core) │──────▶│ Wallet Backend│───────────────▶│  vdr-service  │──▶ did.json público
 │  └ Secure Hardware        │       │ (001, 003)    │                │  (004–008)    │
 │     (clave NO exportable) │       └───────────────┘                └───────────────┘
 │                           │  TLS  ┌───────────────┐   resuelve el DID del emisor ──────────────▲
 │                           │──────▶│ credential-   │──────────────────────────────────────────────┘
 └───────────────────────────┘       │ service (002) │   Emisor (OpenID4VCI) + Verificador (OpenID4VP)
                                     └───────────────┘
```

## 11.2 Glosario adicional

| Término | Qué es, en una frase |
|---|---|
| **Keystore / Secure Enclave** | Almacén del sistema operativo/chip donde se crean y usan claves sin poder leerlas |
| **TEE** | *Trusted Execution Environment*: zona aislada del procesador principal |
| **StrongBox** | Chip de seguridad dedicado (más fuerte que el TEE) |
| **No exportable** | La clave se puede *usar* para firmar, pero no *leer* |
| **Key attestation** | Cadena de certificados que el Keystore firma para demostrar el nivel, el origen y el propósito de una clave |
| **Nivel declarado vs. verificado** | Lo que el dispositivo *dice* vs. lo que la attestation *demuestra*; deben coincidir |
| **Instancia de cartera** | Una instalación concreta de la cartera, ligada a un dispositivo y a un ciudadano |
| **SD-JWT VC (`dc+sd-jwt`)** | Credencial en JWT con datos ocultos tras *digests*; el titular decide cuáles divulgar |
| **Divulgación selectiva** | Revelar solo algunos datos de la credencial |
| **`cnf`** | Campo de la credencial con la **clave pública del titular** a la que queda ligada |
| **KB-JWT** | Firma del titular al presentar: prueba que posee la clave de `cnf`, para *este* verificador y *esta* solicitud |
| **mdoc (`mso_mdoc`)** | Credencial en CBOR de ISO 18013-5 (la del carné de conducir móvil) |
| **MSO / COSE_Sign1** | Objeto con los digests de los datos, y el formato de firma que lo protege |
| **OpenID4VCI** | Estándar de **emisión**: oferta → token → nonce → credencial |
| **OpenID4VP** | Estándar de **presentación**: solicitud → respuesta del titular |
| **DCQL** | Lenguaje con el que el verificador dice qué credencial y qué datos quiere |
| **Perfil** | Combinación concreta de formato, cabeceras y algoritmos; interoperar es coincidir en el perfil |

## 11.3 Cómo se relacionan las ocho ERSo

```
 ERSo 001  cartera + clave no exportable + nivel real + recuperación
     │ (la clave nace aquí)
 ERSo 003  DID del titular  ───publica vía Wallet Backend──▶  ERSo 004…008  (VDR: registro, ciclo de vida, consumidor)
     │                                                               ▲
     └──── el titular presenta credenciales ligadas a su clave       │ el verificador resuelve el DID del EMISOR aquí
 ERSo 002  formatos dc+sd-jwt / mso_mdoc + OpenID4VCI/VP ────────────┘
```

Orden natural de estudio: **001** (dónde vive la clave) → **003** (qué identificador tiene) → **002** (qué credenciales lleva y cómo las presenta).

## 11.4 Recorrido: activar una cartera y comprobar su nivel real (ERSo 001)

```
Dispositivo                                             Wallet Backend                       PostgreSQL
   │ 1. "quiero activar" (ciudadano)  ───────────────▶  desafío: nonce de un solo uso ─────▶ guarda
   │ ◀──────────────────────────────────────────────── nonce
   │ 2. el Keystore CREA la clave con el nonce incrustado (attestation) — la privada no sale
   │ 3. firma { aud, nonce, ciudadano, nivel declarado, huella } con esa clave (prueba de posesión)
   │ 4. envía: clave PÚBLICA + declaración + cadena de attestation + prueba ────▶
   │                                                      ¿desafío válido y no usado?
   │                                                      ¿la firma corresponde a la clave declarada?
   │                                                      ¿la cadena llega a una raíz de confianza?
   │                                                      ¿el nonce de la cadena es el emitido?
   │                                                      ¿origen = GENERADA dentro del entorno seguro?
   │                                                      nivel VERIFICADO ── ¿= nivel declarado?  ¿≥ política mínima?
   │ ◀──────────────────────────────── ACTIVA (o rechazo con el motivo) ─────────────────────▶ instancia + auditoría
```

Frase clave: **"el servidor no le cree al dispositivo lo que dice; lee lo que el hardware demuestra"**.

## 11.5 Recorrido: emitir y presentar una credencial (ERSo 002)

```
Portal admin del emisor        Emisor (OpenID4VCI)                Titular (cartera)             Verificador (OpenID4VP)
  crea oferta + tx_code ─────▶ guarda la oferta
                                                  ◀── canjea código pre-autorizado + tx_code ── token
                                                  ◀── pide nonce ──────────────────────────────  c_nonce
                                                  ◀── credential + PRUEBA DE POSESIÓN (firma con la clave que quedará en cnf)
                               firma la credencial con la clave del EMISOR ───────────────────▶ recibe
                                                              la cartera VERIFICA la firma del emisor resolviendo su DID
                                                                            ◀── solicitud (DCQL, nonce, state) ── portal del verificador
                                                              elige qué revelar; firma KB-JWT (aud, nonce, sd_hash) con SU clave
                                                                            ── vp_token (direct_post) ──▶ verifica: firma del EMISOR (DID)
                                                                                                          + divulgaciones + KB-JWT + consulta
```

Frase clave: **"dos firmas, dos claves: la del emisor dice *qué* es la credencial; la del titular dice *quién* la presenta"**.

## 11.6 Qué ataque bloquea cada control (ampliación de §7)

| Amenaza | Control | Dónde se prueba |
|---|---|---|
| Un dispositivo de software finge tener hardware | El nivel se **lee de la attestation**; declarado ≠ verificado ⇒ rechazo | ERSo 001 C3 |
| Reutilizar una attestation de otro dispositivo o de otro momento | La attestation lleva el nonce del backend; un solo uso | ERSo 001 C3 |
| Presentar una clave **importada** como si fuera de hardware | El origen de la clave (`GENERATED`) viaja en la attestation | ERSo 001 C2 |
| Activar con la clave de otro | Prueba de posesión (firma del desafío) | ERSo 001 C1 |
| Adivinar el código de recuperación | PBKDF2, bloqueo tras 5 intentos, respuesta idéntica si el ciudadano no existe | ERSo 001 C4 |
| Robar el token de recuperación | Un solo uso, ligado al ciudadano, vence en 10 min | ERSo 001 C4 |
| Que el backend robe o custodie claves | No existe endpoint ni columna que las reciba; se busca la clave real en todas las tablas | ERSo 001 C2/C4, E2E `pg_dump` |
| Publicar un DID de otra persona o de una entidad | El backend solo publica bajo `titulares/<huella de la clave>` | ERSo 003 |
| Publicar un DID con datos civiles o claves privadas | Informe de conformidad **antes** de tocar el VDR | ERSo 003 C2 |
| Publicar sin controlar la clave | El VDR exige la firma del desafío con la clave del DID; el backend no la tiene | ERSo 003 |
| Reutilizar un código de oferta | Un solo uso; 3 fallos de `tx_code` queman la oferta | ERSo 002 C1 |
| Pedir una credencial sin poseer la clave | Prueba de posesión con nonce de un solo uso, audiencia y frescura | ERSo 002 C1 |
| Reenviar una presentación a otro verificador | El KB-JWT lleva `aud` del verificador y `nonce` de la solicitud | ERSo 002 C1 |
| Revelar más de lo que el titular eligió | `sd_hash` cubre exactamente las divulgaciones presentadas | ERSo 002 C1 |
| Falsificar la prueba del titular con la clave del emisor | Las claves son distintas y se verifican por separado | ERSo 002 C4 |
| Proponer un protocolo propio paralelo | Inventario de endpoints + nginx solo publica los normativos | ERSo 002 C3 |

## 11.7 Preguntas que probablemente le harán (ampliación de §9)

**¿Esto usa hardware seguro de verdad?** No. En esta JVM el hardware seguro es una **simulación** (declara `SOFTWARE`). Lo que se prueba es el contrato y que el servidor **no se deja engañar**. La implementación Android está diseñada (`docs/ANDROID-REFERENCIA.md`) pero no compilada ni probada.

**¿Las attestations son reales?** Son de **laboratorio**: las firma una autoridad simulada con la misma estructura que Android Key Attestation. No se ha probado con cadenas reales de Google.

**¿Publicar el DID del ciudadano no permite rastrearlo?** Sí, puede: un identificador público y persistente correlaciona. Es una consecuencia del diseño del equipo (el Wallet Backend publica el DID del titular). Mitigaciones: publicación opcional, sin datos civiles, y credenciales ligadas a una clave distinta de la del DID. Falta un DID por propósito con rotación.

**¿Qué pasa si pierdo el teléfono?** La cartera nueva se activa con el código de recuperación y la anterior queda revocada. **La clave no se recupera** (por diseño): las credenciales hay que **reemitirlas** y el DID anterior no se puede desactivar (haría falta esa clave).

**¿Las credenciales funcionan en el wallet de la UE?** **No está probado.** El emisor EUDI descubre la clave por certificado (`x5c`); aquí es por DID. La prueba cruzada real contra `eudi-srv-pid-issuer` es el siguiente paso.

**¿Por qué dos claves por dispositivo?** Una identifica la **cartera** (instancia, attestation); otra es la **identidad del titular** (DID). Separarlas evita usar la clave del dispositivo como identificador estable.

**¿Por qué SD-JWT y mdoc?** Son los dos formatos que ya soporta el ecosistema EUDI. mdoc solo se emite y recibe aquí; su presentación no está implementada.

## 11.8 Guion de demostración (≈12 minutos)

1. Levante el stack y corra `bash scripts/e2e.sh`; señale los bloques **ERSo 001**, **003** y **002** del log.
2. **Nivel real:** muestre el rechazo `PROTECTION_LEVEL_OVERSTATED` de un dispositivo de software que dice ser TEE, y la ficha técnica con `origin = GENERATED`.
3. **Recuperación:** muestre que la cartera anterior queda revocada y que el backend no tiene ninguna clave privada (`pg_dump` sin coincidencias).
4. **DID del titular:** abra `https://civica-desarrollo.avance.org.co:8443/titulares/<huella>/did.json` y el informe de conformidad (10 comprobaciones).
5. **Credencial:** muestre la presentación en la que el verificador recibe **solo el programa** y no el nombre ni el promedio, y la **matriz de claves por rol**.
6. Termine con la lista de §11.9: diga usted primero lo que no está hecho.

## 11.9 Lo que NO se hizo (dígalo usted primero)

* **No hay hardware seguro real ni attestation real**: custodio y autoridad de attestation son simulaciones de laboratorio.
* **La implementación Android no existe todavía** (solo su diseño); la prueba de no exportabilidad en un dispositivo está pendiente.
* **No hay interoperabilidad probada con terceros** (emisor/wallet EUDI): las pruebas cruzadas son entre componentes del propio proyecto.
* **Perfil distinto al de EUDI/ISO:** clave del emisor por DID en vez de `x5c`/`x5chain`; sin DPoP ni JWE; sin presentación de mdoc; sin revocación (status list).
* **Emisión de la Wallet Instance Attestation (JWT) no implementada.**
* **La recuperación no recupera la clave**; el DID huérfano no se desactiva; no hay rotación del DID del titular vía backend.
* **Estado del emisor y del verificador en memoria.**
* **Alta de ciudadano abierta en laboratorio**; la verificación de identidad no está en alcance.
* **Se publican DID de ciudadanos** (decisión del diagrama del equipo): riesgo de correlación, ver §11.7.
