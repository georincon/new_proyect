# Marco conceptual para afrontar las ERSo 001 a 008

**Para qué sirve este documento.** Reúne, en el orden en que se necesitan, los conceptos que hay que dominar para entender, resolver y defender las ERSo. No es para leerlo de corrido: es para **consolidar** con práctica. Cada bloque termina con un laboratorio de pocos minutos y unas preguntas de autocomprobación (respuestas al final).

**Método de trabajo (el mismo de las lecciones):**
1. **Concepto** — qué es, para qué sirve, dónde aparece en el proyecto.
2. **Observación** — ejecutas un comando y ves el concepto funcionando.
3. **Predicción** — antes de ejecutar, dices qué va a pasar.
4. **Correlación** — conectas lo observado con el código, con la ERSo y con los otros conceptos.
5. **Evidencia** — aprendes a demostrar el resultado con una prueba que otro pueda repetir.

---

## 1. Las siete ideas madre

Si solo recuerdas esto, ya entiendes el 70 % del proyecto. Todo lo demás son detalles de estas ideas.

| # | Idea | En una frase |
|---|---|---|
| 1 | **Confianza por verificación, no por autoridad** | En vez de "confío porque lo dice X", "confío porque puedo comprobarlo matemáticamente". |
| 2 | **La pública verifica; la privada firma** | La clave privada nunca sale de su dueño; la pública se reparte a todos. |
| 3 | **Un hash es una huella** | Mismo contenido → misma huella. Cambia un byte → otra huella completamente distinta. |
| 4 | **Leer es libre; cambiar exige demostrar** | Cualquiera puede leer un `did.json`. Escribirlo exige canal seguro, permiso **y** demostrar que se controla la clave. |
| 5 | **Defensa en capas** | Si una falla, quedan las otras: canal (mTLS) → permiso (token) → posesión (firma del desafío) → integridad (hash) → trazabilidad (auditoría). |
| 6 | **Cada rol tiene su clave y su canal** | Emisor, titular, verificador y registro no comparten claves ni puertas. |
| 7 | **Lo opcional está apagado y aislado** | La extensión (VDR) no puede romper el camino base; por defecto está apagada. |

---

## 2. Mapa: qué necesita cada ERSo

Los números de bloque remiten a las secciones 3 a 10.

| ERSo | Tema | Bloques necesarios | Lo que debes poder explicar |
|---|---|---|---|
| **004** | Desplegar el VDR de extensión | 1, 2, 3, 4, 5, 10 | Qué es un VDR, por qué no es blockchain, cómo se aísla (Docker/nginx), cómo se respalda y se restaura, cómo se apaga |
| **005** | Publicar el DID Document (`did:web`) | 1, 2, 4 | Cómo un DID se convierte en URL, qué contiene el documento y qué no |
| **006** | Publicar el DID institucional | 1, 2, 3, 4 | Quién puede escribir y por qué (mTLS + token + namespace) |
| **007** | Consumidor conforme | 1, 2, 4 | Resolver vs dereferenciar, verificar una firma con la clave resuelta, por qué solo lee |
| **008** | Ciclo de vida y trazabilidad | 1, 3, 4, 5 | Desafío, prueba de posesión, versiones, idempotencia, historial inalterable |
| **001** | Cartera y claves en hardware | 1, 3, 7, 8 | Clave no exportable, nivel de protección real vs declarado, recuperación |
| **003** | DID del titular | 1, 4, 7 | Identificador derivado de la clave, informe de conformidad, almacenamiento sellado |
| **002** | Formatos de credencial | 1, 3, 4, 6, 8 | SD-JWT y mdoc, divulgación selectiva, OpenID4VCI/VP, separar firma del emisor y prueba del titular |

Orden de estudio acordado: **004 → 005 → 006 → 008 → 007 → 001 → 003 → 002**.

---

## 2b. Requisitos previos de TODOS los laboratorios (haz esto una vez)

> **Regla de oro de los laboratorios.** Cada línea que ejecutas con `!` en Claude Code (y cada ventana de terminal nueva) es una **shell nueva**: las variables como `D` **no se conservan** entre una y otra. Por eso cada laboratorio es **un bloque que se pega completo de una sola vez**, empieza siempre con un `cd` a la carpeta correcta y define sus propias variables. Si pegas línea por línea, fallará.

### Paso A — Herramientas
Necesitas `openssl`, `curl`, `jq`, `python3` y `docker` (con `docker compose`). Compruébalo:
```bash
for c in openssl curl jq python3 docker; do command -v $c >/dev/null && echo "OK    $c" || echo "FALTA $c"; done
docker compose version
```
Debe decir `OK` en las cinco y mostrar la versión de compose. Si falta alguna, instálala antes de seguir.

### Paso B — Certificados y secretos (se crean con scripts, no a mano)
```bash
cd /home/geovani/Descargas/generic/bitacora/new_proyect
bash scripts/gen-dev-certs.sh     # crea la carpeta deploy/certs con la CA y los certificados de laboratorio
bash scripts/gen-env.sh           # crea deploy/.env con los secretos aleatorios
ls deploy/certs
```
Si ya existen, los scripts lo dicen (`Ya existen certificados…`, `deploy/.env ya está actualizado`) y no los tocan. Esto es lo que contiene `deploy/certs` y para qué sirve cada archivo:

| Archivo | Qué es | ¿Secreto? |
|---|---|---|
| `ca.crt` / `ca.key` | Nuestra **CA de laboratorio** (certificado / su clave privada) | `ca.key` sí |
| `server.crt` / `server.key` | Certificado TLS del servidor para `civica-desarrollo.avance.org.co` / su clave | `server.key` sí |
| `vdr-admin.crt` / `.key` / `.p12` | **Certificado cliente** de la cuenta administradora (mTLS). El `.p12` es el mismo par empaquetado con contraseña | `.key` y `.p12` sí |
| `avance-issuer.*`, `lab-operator.*`, `wallet-backend.*` | Certificados cliente de otras entidades | `.key` y `.p12` sí |
| `ca-falsa.crt` / `ca-falsa.key` | Una **CA que no es la nuestra**, para pruebas negativas | — |
| `intruso.*` | Certificado cliente firmado por la CA falsa, para probar que el sistema lo rechaza | — |

### Paso C — Compilar y construir las imágenes (la primera vez, o si cambió el código)
```bash
cd /home/geovani/Descargas/generic/bitacora/new_proyect
./gradlew installDist
cd deploy
docker compose --profile tools build
```
La compilación puede tardar unos minutos la primera vez.

### Paso D — Levantar el proyecto y comprobar que vive
```bash
cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy
docker compose up -d
docker compose ps
curl -s --cacert certs/ca.crt --resolve civica-desarrollo.avance.org.co:8443:127.0.0.1 https://civica-desarrollo.avance.org.co:8443/health
```
**Debes ver** cinco servicios `Up` (`postgres` con `(healthy)`, `vdr`, `wallet`, `credential`, `nginx`) y esta respuesta: `{"status":"UP","vdr":"enabled"}`. Si `credential` se reinicia sin parar, falta `evidencias/work/avance.json` (lo crea el Paso E).

### Paso E — Datos de ejemplo (necesarios para los laboratorios 3, 4 y 5)
Los DID de ejemplo (Avance, laboratorio, un titular…) los publica el recorrido completo:
```bash
cd /home/geovani/Descargas/generic/bitacora/new_proyect
bash scripts/e2e.sh
```
⚠️ **Tarda unos 5 minutos y reinicia el entorno desde cero** (borra los datos y los vuelve a crear). Al terminar debe decir `RESULTADO: … PASS · 0 FAIL` y deja el proyecto levantado. Para saber si ya lo hiciste:
```bash
cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy
curl -s -o /dev/null -w "HTTP %{http_code}\n" --cacert certs/ca.crt --resolve civica-desarrollo.avance.org.co:8443:127.0.0.1 https://civica-desarrollo.avance.org.co:8443/entidades/avance/did.json
ls ../evidencias/work/avance.json
```
`HTTP 200` y que el archivo exista = ya está. `HTTP 404` = falta correr el recorrido.

### Cómo leer los comandos `curl` de los laboratorios
| Parte | Qué hace |
|---|---|
| `-s` | Silencioso: no muestra la barra de progreso |
| `-o /dev/null` | Descarta el cuerpo de la respuesta (solo nos interesa el código) |
| `-w "HTTP %{http_code}\n"` | Al final imprime el código HTTP |
| `--cacert certs/ca.crt` | "Confía en **esta** CA" (la nuestra). Sin esto, `curl` no conoce quién firmó el certificado y se niega |
| `--resolve $D:9443:127.0.0.1` | "Cuando pidas ese dominio en ese puerto, ve a `127.0.0.1`". El DNS real del dominio no apunta a tu máquina, pero el certificado solo es válido para ese **nombre**; con `--resolve` usamos el nombre correcto y llegamos a tu máquina |
| `--cert` / `--key` | El **certificado cliente** y su clave privada (mTLS) |

### Los puertos (en tu máquina)
| Puerto | Canal | Detrás (nginx) |
|---|---|---|
| **8443** | Lectura pública de `did.json` y camino base | 443 |
| **9443** | Escritura y administración, **mTLS obligatorio** | 8443 |
| **8444** | Cartera y credenciales de ciudadanos | 8444 |

---

## 3. Bloque 1 — Criptografía básica (la base de todo)

| Concepto | Qué es | Para qué sirve (objetivo) | Dónde lo ves |
|---|---|---|---|
| **Hash** (SHA-256) | Función que convierte cualquier contenido en una huella de 32 bytes; no se puede invertir | Comprobar que algo **no cambió**; identificar contenido | `sha256:…` en cada versión del DID; checksum del respaldo |
| **Par de claves** | Dos números matemáticamente ligados: una **privada** (secreta) y una **pública** (se reparte) | Identificarse y firmar sin compartir el secreto | Claves P-256 de cada entidad y de cada titular |
| **Firma digital** | Resultado de "firmar" un contenido con la clave privada | Probar **quién** firmó y que el contenido **no cambió** | Prueba de posesión, firma del emisor, KB-JWT |
| **Verificar** | Comprobar una firma con la clave **pública** | Que cualquiera valide sin poder falsificar | Consumidor conforme (ERSo 007) |
| **P-256 / ES256** | Curva elíptica (P-256) y algoritmo de firma (ECDSA + SHA-256) | Firmas cortas (64 bytes) y seguras; es el estándar de EUDI | Todas las firmas del proyecto |
| **Nonce / aleatorio** | Valor aleatorio de un solo uso | Que una firma capturada no se pueda **reutilizar** | El desafío; el `c_nonce` |
| **Codificaciones** (hex, Base64url, Base58) | Formas de escribir bytes como texto | Poder llevar datos binarios en JSON y URLs | JWS, Multikey (`zDn…`) |
| **Multibase / Multikey** | Convención: prefijo (`z` = Base58) + tipo de clave + clave | Escribir claves públicas de forma estándar y autodescriptiva | `publicKeyMultibase` del DID Document |
| **JSON canónico** | Misma información → mismos bytes (claves ordenadas, sin espacios) | Que el **hash** y la **firma** sean reproducibles | `CanonicalJson.kt` |

### Distinciones que se confunden siempre

| Operación | Qué hace | ¿Se revierte? | Ejemplo |
|---|---|---|---|
| **Codificar** | Cambia la forma, sin secreto | Sí, cualquiera | Base64 |
| **Hashear** | Saca una huella | **No** | SHA-256 |
| **Cifrar** | Oculta con una clave | Sí, solo con la clave | AES-GCM del almacén sellado |
| **Firmar** | Prueba autoría e integridad | No aplica (se **verifica**) | ES256 |

Regla: **firmar no oculta nada** (el contenido sigue a la vista); **cifrar no prueba quién lo escribió**.

### Laboratorio 1 — hash y firma (5 min)

**Qué vas a demostrar:** (1) un cambio mínimo cambia la huella por completo; (2) una firma solo es válida para el contenido exacto que se firmó.
**Requisito:** solo `openssl` (Paso A). No necesita el proyecto levantado. Crea sus archivos en `/tmp/lab`.

**Antes de ejecutar, predice:** ¿las huellas de `hola` y `hola!` se parecerán? ¿la segunda verificación pasará?

Pega este bloque completo:
```bash
mkdir -p /tmp/lab && cd /tmp/lab

echo "--- 1. Huella de dos textos casi iguales ---"
echo -n "hola"  | sha256sum
echo -n "hola!" | sha256sum

echo "--- 2. Crear un par de claves P-256 (la curva del proyecto) ---"
openssl ecparam -name prime256v1 -genkey -noout -out k.pem          # clave PRIVADA
openssl ec -in k.pem -pubout -out pub.pem 2>/dev/null               # clave PÚBLICA derivada de la privada
ls -l k.pem pub.pem

echo "--- 3. Firmar un mensaje con la privada ---"
echo -n "pago de 100" > m.txt
openssl dgst -sha256 -sign k.pem -out sig.bin m.txt                 # calcula el hash y lo firma
ls -l sig.bin

echo "--- 4. Verificar con la pública (mensaje original) ---"
openssl dgst -sha256 -verify pub.pem -signature sig.bin m.txt

echo "--- 5. Verificar con la pública (mensaje ALTERADO) ---"
echo -n "pago de 900" > m2.txt
openssl dgst -sha256 -verify pub.pem -signature sig.bin m2.txt
```
**Qué hace cada cosa:** `echo -n` escribe el texto **sin salto de línea** (si no, el salto de línea cambiaría el hash); `sha256sum` calcula la huella; `-sign` firma con la clave **privada**; `-verify` comprueba con la **pública**.

**Qué debes ver:**
1. Dos huellas de 64 caracteres hexadecimales **sin parecido alguno**.
2. Los archivos `k.pem` (privada) y `pub.pem` (pública).
3. `sig.bin` de unos 70 bytes (firma en formato DER).
4. `Verified OK`.
5. `Verification failure`: la firma pertenecía a "pago de 100", no a "pago de 900".

> **Es normal que el bloque termine con código de salida 1:** el último comando falla a propósito (es lo que queríamos demostrar). Si tu terminal muestra un aviso de error al final, no es un problema.

**Conclusión para el proyecto:** por eso el `did.json` lleva un hash, y por eso la prueba de posesión firma el **hash del documento**: cambiar cualquier byte invalida todo.

### Autocomprobación
1. ¿Por qué `hola` y `hola!` dan huellas totalmente distintas?  2. ¿Qué clave usas para firmar y cuál para verificar?  3. ¿Firmar oculta el mensaje?  4. ¿Para qué sirve un nonce?

---

## 4. Bloque 2 — Redes y canales seguros

| Concepto | Qué es | Para qué sirve (objetivo) | Dónde lo ves |
|---|---|---|---|
| **Dominio / DNS** | El nombre (`civica-desarrollo.avance.org.co`) y el sistema que lo traduce a una dirección | Que las personas y los DID usen nombres | El DID *es* el dominio |
| **HTTP / HTTPS** | Protocolo web; HTTPS = HTTP dentro de TLS | Pedir y recibir documentos | `GET …/did.json` |
| **Puerto** | Número que distingue "puertas" de una misma máquina | Separar servicios | 443 / 8443 / 8444 / 9443 |
| **TLS** | Capa que da **cifrado**, **integridad** y **autenticación del servidor** | Que nadie escuche ni altere ni suplante al servidor | Todo el tráfico del proyecto |
| **Certificado X.509** | Documento que dice "esta clave pública pertenece a este dominio", firmado por una CA | Que el cliente sepa con quién habla | `deploy/certs/server.crt` |
| **CA y cadena de confianza** | Autoridad que firma certificados; el cliente confía en unas CA y valida la cadena hasta ellas | Delegar la confianza en pocas autoridades | `ca.crt` (CA de **laboratorio**) |
| **mTLS** | TLS **mutuo**: el cliente también presenta certificado | Identificar **qué máquina/entidad** escribe | Canal de escritura `:8443` |
| **Reverse proxy** (nginx) | Servidor que recibe el tráfico y lo reenvía a los servicios internos | Terminar TLS, separar canales, esconder los servicios | `deploy/nginx/nginx.conf` |
| **Docker / red interna** | Contenedores aislados que se hablan por una red privada | Aislar la extensión; que solo nginx publique puertos | `docker-compose.yml` |
| **Terminar TLS** | El proxy descifra y pasa al servicio interno la identidad verificada | La aplicación no maneja certificados | Encabezados `X-SSL-Client-*` |

### Confusiones típicas
* **Certificado ≠ clave privada.** El certificado es público; la clave privada del certificado no sale del servidor.
* **Certificado cliente ≠ token.** El primero identifica la *máquina*; el segundo, los *permisos*.
* **`curl -k` desactiva la verificación**: sirve en laboratorio, es inseguro en producción.
* **CA de laboratorio ≠ CA pública.** Aquí nosotros somos la CA; en producción sería una CA reconocida.

### Laboratorio 2 — certificado, cadena de confianza y mTLS (8 min)

**Qué vas a demostrar:** (1) el certificado del servidor lo firmó nuestra CA; (2) con otra CA falla; (3) el canal de escritura **rechaza** a quien no presenta certificado; (4) quien sí lo presenta **pasa nginx**, pero la aplicación le exige un token.
**Requisitos:** Pasos A, B, C y D de la sección 2b (el proyecto debe estar levantado y `deploy/certs` creado). No necesitas el Paso E.

**Qué es `/admin/v1/audit`:** un endpoint del **canal de escritura** que lista los eventos de auditoría. Lo usamos solo como "puerta protegida" para ver **qué capa nos detiene**; no nos interesa lo que devuelva. No tienes que abrirlo en un navegador ni hacer nada con él: el propio `curl` lo consulta.

**Antes de ejecutar, predice** qué código HTTP dará cada `curl` de los pasos 4, 5 y 6, y en cuál de ellos crees que responde nginx y en cuál la aplicación.

Pega este bloque completo:
```bash
cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy
D=civica-desarrollo.avance.org.co

echo "--- 1. Datos del certificado del servidor ---"
openssl x509 -in certs/server.crt -noout -subject -issuer -dates -ext subjectAltName

echo "--- 2. ¿Lo firmó nuestra CA? ---"
openssl verify -CAfile certs/ca.crt certs/server.crt

echo "--- 3. ¿Y una CA que NO lo firmó? ---"
openssl verify -CAfile certs/ca-falsa.crt certs/server.crt

echo "--- 4. Canal de escritura SIN certificado cliente ---"
curl -s -o /dev/null -w "HTTP %{http_code}\n" --cacert certs/ca.crt --resolve $D:9443:127.0.0.1 https://$D:9443/admin/v1/audit

echo "--- 5. Canal de escritura CON certificado cliente, sin token ---"
curl -s -o /dev/null -w "HTTP %{http_code}\n" --cacert certs/ca.crt --cert certs/vdr-admin.crt --key certs/vdr-admin.key --resolve $D:9443:127.0.0.1 https://$D:9443/admin/v1/audit

echo "--- 6. Sin --cacert: el cliente no conoce nuestra CA ---"
curl -s -o /dev/null -w "HTTP %{http_code}\n" --resolve $D:8443:127.0.0.1 https://$D:8443/health; echo "código de salida de curl: $?"
```
**Qué hace cada paso:**
1. Muestra a quién pertenece el certificado (`subject`), quién lo firmó (`issuer`), su vigencia y los nombres para los que vale (`subjectAltName`).
2. y 3. `openssl verify` sigue la **cadena de confianza**: ¿el emisor del certificado es la CA que le indicas?

**Qué debes ver:**
1. `subject=CN = civica-desarrollo.avance.org.co`, `issuer=CN = VDR Laboratorio CA, O = Avance`, fechas de vigencia y en `subjectAltName` el dominio, `localhost` y `127.0.0.1`.
2. `certs/server.crt: OK`.
3. Primero el nombre del certificado (`CN = civica-desarrollo.avance.org.co`), luego `error 20 at 0 depth lookup: unable to get local issuer certificate` y `error certs/server.crt: verification failed`: esa CA **no es quien lo firmó**, así que no puede completar la cadena. *(En pantalla estas líneas de error pueden aparecer un poco desordenadas respecto a los `echo`; es normal.)*
4. `HTTP 400`: **nginx** corta la conexión porque no hay certificado cliente; la petición **nunca llega** a la aplicación.
5. `HTTP 401`: el certificado cliente **sí pasó nginx** (mTLS cumplido) y la **aplicación** contesta "no sé quién eres": falta el token. Son capas distintas.
6. `HTTP 000` y `código de salida de curl: 60`: `curl` no confía en el certificado del servidor porque no conoce la CA que lo firmó, y se niega a continuar. (El `000` significa que no llegó a haber respuesta HTTP.)

**Lo que debes llevarte:** 400 vs 401 vs falla de TLS indican **en qué capa** se detuvo la petición: certificado de servidor (paso 6) → certificado cliente en nginx (paso 4) → token en la aplicación (paso 5).

### Autocomprobación
5. ¿Qué tres cosas da TLS?  6. ¿Quién firmó `server.crt` y qué pasa si validas con otra CA?  7. ¿En qué se diferencia TLS de mTLS?  8. ¿Por qué la aplicación no publica su puerto?

---

## 5. Bloque 3 — Autenticación, permisos y escrituras seguras

| Concepto | Qué es | Para qué sirve (objetivo) | Dónde lo ves |
|---|---|---|---|
| **Autenticación** | Comprobar **quién** eres | Identificar al que escribe | mTLS + secreto del cliente |
| **Autorización** | Decidir **qué puedes hacer** | Limitar cada entidad a su espacio | Namespace propio (`entidades/avance`) |
| **OAuth2 `client_credentials`** | Una máquina cambia `client_id` + `secret` por un token | Autenticar servicios, no personas | `POST /admin/v1/oauth/token` |
| **Token / JWT** | Credencial corta (10 min) firmada por el servidor, con permisos (*scopes*) | No repetir el secreto en cada llamada | `access_token` |
| **JWS** | Formato `cabecera.contenido.firma` en Base64url | Firmar datos de forma portátil | Todas las "pruebas" |
| **Desafío–respuesta** | El servidor da un nonce; el cliente lo firma | Probar que controla la clave **ahora** | `POST /challenges` |
| **Prueba de posesión** | Demostrar que posees la clave privada, no solo que conoces algo | Que un token robado no baste | Firma del desafío |
| **Replay (repetición)** | Reenviar una petición capturada | Es el ataque que el nonce de un solo uso frena | Desafío consumido atómicamente |
| **Idempotencia** | Repetir una petición no duplica el efecto | Reintentar sin miedo | `Idempotency-Key` |
| **Concurrencia optimista** | "Escribo solo si la versión actual es N" | Que dos escrituras no se pisen | `If-Match` → `412` |
| **Códigos HTTP** | Respuesta numérica del servidor | Saber qué falló | tabla de abajo |

### Códigos HTTP que aparecen en el proyecto
| Código | Significado | Ejemplo aquí |
|---|---|---|
| 200 / 201 | OK / creado | escritura confirmada / DID creado |
| 400 | Solicitud mal formada | nginx sin certificado cliente |
| 401 | No autenticado | token ausente o inválido |
| 403 | Autenticado pero **sin permiso** | prueba de posesión inválida |
| 404 | No existe | DID inexistente, o ruta no registrada |
| 409 | Conflicto de estado | crear un DID que ya existe |
| 410 | Ya no existe (a propósito) | DID desactivado |
| 412 | Falla una precondición | `If-Match` con versión equivocada |
| 422 | Entendible pero **inválido** | documento que no cumple el perfil |
| 428 | Falta una precondición | falta `If-Match` o `Idempotency-Key` |

### Las capas de una escritura (ideas madre 4 y 5 en acción)
```
canal (mTLS)  →  token (¿quién y qué permisos?)  →  desafío firmado (¿controlas la clave?)
       →  versión esperada (¿nadie escribió antes?)  →  lectura pública y hash (¿se publicó lo escrito?)  →  auditoría
```

### Laboratorio 3 — abrir una firma JWS (5 min)

**Qué vas a demostrar:** que una firma JWS son tres partes legibles (cabecera, contenido y firma), que **firmar no oculta** el contenido, y que un verificador puede validarla resolviendo el DID por HTTPS.
**Requisitos:** Pasos A, B, C, D **y E** de la sección 2b. Se usa la clave de Avance (`evidencias/work/avance.json`) y su DID publicado.

**Qué ocurre por dentro:** `docker compose run … tools` ejecuta el programa `did-tools` **dentro de un contenedor** que ya tiene montada la carpeta `evidencias/work` como `/work` (por eso `--key avance.json` la encuentra) y los certificados como `/certs` (por eso `--ca /certs/ca.crt` funciona).

**Antes de ejecutar, predice:** ¿podrás leer el contenido "hola" sin ninguna clave? ¿De cuántos bytes será la firma?

Pega este bloque completo:
```bash
cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy
set -a; . ./.env; set +a                       # carga las variables (dominio, contraseñas) de deploy/.env
export LOCAL_UID=$(id -u) LOCAL_GID=$(id -g)  # para que los archivos creados sean tuyos
D=$VDR_DOMAIN

echo "--- 1. Firmar el mensaje 'hola' con la clave privada de Avance ---"
JWS=$(docker compose --progress quiet --profile tools run --rm -T tools sign --key avance.json --kid "did:web:$D:entidades:avance#key-1" --message "hola" 2>/dev/null | tail -1)
echo "$JWS"

echo "--- 2. Abrir las tres partes (solo se decodifica Base64url; no hace falta ninguna clave) ---"
python3 - "$JWS" <<'EOF'
import sys, base64, json
h, p, s = sys.argv[1].split('.')
d = lambda x: base64.urlsafe_b64decode(x + '=' * (-len(x) % 4))
print("cabecera:", json.loads(d(h)))
print("payload :", d(p).decode())
print("firma   :", len(d(s)), "bytes")
EOF

echo "--- 3. Verificar: el verificador resuelve el DID por HTTPS y valida con la clave publicada ---"
docker compose --progress quiet --profile tools run --rm -T tools verify --jws "$JWS" --purpose assertionMethod --ca /certs/ca.crt 2>/dev/null | tail -1
```
**Qué debes ver:**
1. Una cadena larga con **dos puntos** (`xxxx.yyyy.zzzz`).
2. `cabecera: {'alg': 'ES256', 'kid': 'did:web:…:entidades:avance#key-1'}`, `payload : hola` y `firma   : 64 bytes`.
3. Una línea que empieza por `VALIDA` con el DID y el `kid`.

**Qué observar:** la cabecera trae un **DID URL** (`kid`): así el verificador sabe **dónde buscar** la clave pública; el contenido `hola` está **a la vista** (firmar no es cifrar); y `--purpose assertionMethod` le dice que la clave debe estar autorizada para firmar afirmaciones.

### Autocomprobación
9. ¿Diferencia entre 401 y 403?  10. ¿Por qué el token solo no basta para escribir?  11. ¿Qué evita `If-Match`?  12. ¿Qué evita `Idempotency-Key`?

---

## 6. Bloque 4 — Identidad descentralizada (el corazón del tema)

| Concepto | Qué es | Para qué sirve (objetivo) | Dónde lo ves |
|---|---|---|---|
| **Identificador** | Nombre único de algo | Referirse a una entidad sin ambigüedad | — |
| **DID** | Identificador con forma `did:<método>:<id>` que su dueño controla sin autoridad central | Identidad que no depende de un tercero | `did:web:civica-desarrollo.avance.org.co:entidades:avance` |
| **Método DID** | La "receta": cómo se crea, lee, actualiza y desactiva un DID en cierto tipo de registro | Que cada tecnología (web, blockchain…) defina su forma | `web` |
| **`did:web`** | Método cuyo registro es un servidor web: `:` → `/` y se añade `did.json` | Simplicidad operativa | `DidWeb.kt` |
| **DID Document** | JSON público con las claves del DID y para qué sirve cada una | Que cualquiera **encuentre la clave pública** de un DID | `…/entidades/avance/did.json` |
| **`verificationMethod`** | Lista de claves públicas del documento | Ofrecer la clave que verifica | `type: Multikey` |
| **`authentication`** | Claves para **identificarse** | Login / pruebas de identidad | — |
| **`assertionMethod`** | Claves para **emitir afirmaciones** (firmar credenciales) | Que el emisor firme credenciales | — |
| **`controller`** | Quién controla el DID | Gobernanza del documento | El propio DID |
| **DID URL / fragmento** | DID + `#algo` que apunta a una parte | Referirse a una clave concreta | `…#key-1` |
| **Resolver** | Dado un DID, obtener su DID Document | Encontrar la clave | `did-resolver` |
| **Dereferenciar** | Dado un DID URL, obtener la parte concreta | Sacar la clave `#key-1` | `dereference()` |
| **VDR** | *Verifiable Data Registry*: el lugar donde se publican y leen los DID Documents. **Un rol, no una tecnología** | Ser la fuente pública de las claves | `vdr-service` |
| **Camino base vs extensión "O"** | Lo obligatorio frente a lo opcional apagado por defecto | Que el piloto funcione sin el VDR | `VDR_ENABLED=false` |

### Confusiones típicas
* **DID ≠ blockchain.** Un DID puede vivir en un servidor web.
* **DID Document ≠ credencial.** El documento dice *qué claves tiene* un identificador; una credencial dice *algo sobre una persona* (edad, título…).
* **Poseer una clave ≠ ser una persona.** El DID demuestra control de una clave, no identidad civil.
* **`authentication` ≠ `assertionMethod`.** Una es para identificarse; la otra, para firmar afirmaciones.

### Laboratorio 4 — leer un DID Document (5 min)

**Qué vas a demostrar:** qué contiene un DID Document y qué no, y cómo se relaciona un `did:web` con su URL.
**Requisitos:** Pasos A, B, C, D **y E** de la sección 2b (el DID de Avance debe existir).

**Antes de ejecutar, predice:** ¿aparecerá alguna clave privada? ¿cuántas claves habrá? ¿qué tipo de clave (`type`)?

Pega este bloque completo:
```bash
cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy
D=civica-desarrollo.avance.org.co

echo "--- 1. Descargar el DID Document de Avance (es público: no hace falta ni token ni certificado cliente) ---"
curl -s --cacert certs/ca.crt --resolve $D:8443:127.0.0.1 https://$D:8443/entidades/avance/did.json | jq .

echo "--- 2. Resumen: lo esencial en una línea ---"
curl -s --cacert certs/ca.crt --resolve $D:8443:127.0.0.1 https://$D:8443/entidades/avance/did.json | jq -c '{id, claves: (.verificationMethod | length), tipo: .verificationMethod[0].type, prefijo: .verificationMethod[0].publicKeyMultibase[0:3], authentication, assertionMethod}'

echo "--- 3. Comprobación: ¿algún campo de clave privada? (debe imprimir 0) ---"
curl -s --cacert certs/ca.crt --resolve $D:8443:127.0.0.1 https://$D:8443/entidades/avance/did.json | grep -c -Ei 'private|secret|"d":'
```
**Qué debes ver:**
1. El JSON completo. Localiza: el `id` (el DID); en `verificationMethod`, **una** clave con `type: "Multikey"` y `publicKeyMultibase` que empieza por `zDn`; y las listas `authentication` y `assertionMethod`, que apuntan a `…#key-1`.
2. Una línea resumen con `"claves":1`, `"tipo":"Multikey"` y `"prefijo":"zDn"`.
3. `0`: no hay ninguna clave privada (`grep -c` cuenta líneas que coinciden). *(Es normal que este bloque termine con código de salida 1: `grep` devuelve 1 cuando no encuentra nada, y eso es justo lo que queremos.)*

**Ejercicio de correlación (hazlo tú antes de mirar el resultado):** escribe a mano la URL que corresponde al DID `did:web:civica-desarrollo.avance.org.co:entidades:avance` aplicando la regla (los `:` después del dominio pasan a `/` y se añade `did.json`). Luego compara con lo que calcula el proyecto:
```bash
cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy
export LOCAL_UID=$(id -u) LOCAL_GID=$(id -g)
docker compose --progress quiet --profile tools run --rm -T tools did --domain civica-desarrollo.avance.org.co --namespace entidades/avance
```
Debe imprimir el DID y debajo `https://civica-desarrollo.avance.org.co/entidades/avance/did.json`. Observa que esa URL **no lleva puerto**, mientras que tú consultaste `:8443`: en tu máquina el puerto 443 del servidor real está publicado como 8443, y por eso usamos `--resolve`.

### Autocomprobación
13. ¿Qué información contiene un DID Document y cuál no debe contener jamás?  14. ¿Cómo se convierte un `did:web` en URL?  15. ¿Diferencia entre resolver y dereferenciar?  16. ¿Por qué decimos que el VDR es un rol?

---

## 7. Bloque 5 — Datos, integridad y recuperación

| Concepto | Qué es | Para qué sirve (objetivo) | Dónde lo ves |
|---|---|---|---|
| **PostgreSQL** | Base de datos relacional | Guardar el estado del registro | contenedor `postgres` |
| **Tabla / fila / clave primaria / foránea** | Estructura de los datos y sus relaciones | Ordenar y relacionar la información | `V1__init.sql` |
| **Transacción** | Conjunto de cambios "todo o nada" | Nunca dejar el registro a medias | `db.tx { … }` |
| **Estado actual vs historial** | La "foto de hoy" frente al "álbum" | Consultar rápido y conservar la historia | `did_documents` / `did_document_versions` |
| **Append-only + trigger** | Tabla en la que solo se agrega; una regla en la base **impide** modificar o borrar | Historial **inalterable** | error `tabla append-only` |
| **Auditoría** | Registro de quién hizo qué y cuándo, incluidos los rechazos | Trazabilidad y rendición de cuentas | `audit_log` |
| **Versión** | Número que crece con cada cambio | Reconstruir el pasado | `version 1, 2, 3…` |
| **Respaldo** | Foto completa del estado guardada fuera | Recuperarse de una pérdida | `POST /backup` |
| **Checksum** | Hash del respaldo | Detectar que fue alterado | `sha256:…` |
| **Restauración verificada** | Recargar y **comprobar** que el estado quedó idéntico | Que el respaldo sea una garantía, no una esperanza | `documentsVerified` |
| **`TRUNCATE`** | Vaciar una tabla completa | (Aquí) simular la pérdida total | prueba del criterio 2 |

### Laboratorio 5 — el historial que no se puede borrar (3 min)

**Qué vas a demostrar:** que el historial de versiones está protegido **dentro de la base de datos**, no solo por la aplicación.
**Requisitos:** Pasos A, B, C, D **y E** de la sección 2b (deben existir DID con versiones).

**Qué ocurre por dentro:** `docker compose exec -T postgres psql …` ejecuta el cliente `psql` **dentro del contenedor** de PostgreSQL. Las variables `POSTGRES_USER` y `POSTGRES_DB` salen de `deploy/.env`.

**Antes de ejecutar, predice** qué pasará con el `delete` y con el `update`.

Pega este bloque completo:
```bash
cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy
set -a; . ./.env; set +a
PSQL="docker compose exec -T postgres psql -U $POSTGRES_USER -d $POSTGRES_DB"

echo "--- 1. El historial: una fila por versión de cada DID ---"
$PSQL -c "select did, version, operation, left(hash,18) as hash from did_document_versions order by did, version"

echo "--- 2. Intentar BORRAR el historial ---"
$PSQL -c "delete from did_document_versions" 2>&1 | head -2

echo "--- 3. Intentar MODIFICAR una versión ---"
$PSQL -c "update did_document_versions set hash = 'x'" 2>&1 | head -2

echo "--- 4. Comprobar que no se perdió nada ---"
$PSQL -c "select count(*) as versiones from did_document_versions"
```
**Qué debes ver:**
1. Una tabla con varias filas: cada DID con sus versiones y la operación (`CREATE`, `UPDATE`, `DEACTIVATE`).
2. `ERROR:  tabla append-only: DELETE no permitido sobre did_document_versions`.
3. `ERROR:  tabla append-only: UPDATE no permitido sobre did_document_versions`.
4. El mismo número de versiones que en el paso 1.

**Pregunta de fondo:** ¿por qué la protección está **en la base** (un *trigger*) y no solo en el código de la aplicación? *(Pista: piensa en un error de programación, o en alguien con acceso directo a la base.)*

### Autocomprobación
17. ¿Qué garantiza una transacción?  18. ¿Qué diferencia hay entre `did_documents` y `did_document_versions`?  19. ¿Para qué el checksum del respaldo?  20. ¿Por qué restaurar solo sobre un registro vacío?

---

## 8. Bloque 6 — Credenciales verificables (ERSo 002)

| Concepto | Qué es | Para qué sirve (objetivo) |
|---|---|---|
| **Emisor / Titular / Verificador** | Quien emite / quien guarda y presenta / quien comprueba | Los tres roles del modelo |
| **Credencial verificable** | Afirmaciones sobre un sujeto, firmadas por el emisor | Probar algo sin llamar al emisor cada vez |
| **SD-JWT VC** (`dc+sd-jwt`) | Credencial en JWT con datos ocultos tras digests | Divulgación selectiva |
| **mdoc** (`mso_mdoc`) | Credencial en CBOR (carné de conducir móvil, ISO 18013-5) | El otro formato del ecosistema EUDI |
| **Divulgación selectiva** | Revelar solo algunos datos | Privacidad |
| **`cnf`** | Clave pública del titular dentro de la credencial | Ligar la credencial a su dueño |
| **KB-JWT** | Firma del titular al presentar (`aud`, `nonce`, `sd_hash`) | Probar que quien presenta posee la clave de `cnf` |
| **OpenID4VCI** | Estándar de **emisión** (oferta → token → nonce → credencial) | No inventar protocolos propios |
| **OpenID4VP** | Estándar de **presentación** (solicitud → respuesta) | Ídem |
| **DCQL** | Lenguaje con el que el verificador pide datos | Solicitar solo lo necesario |
| **Perfil** | Combinación concreta de formato, cabeceras y algoritmos | Interoperar de verdad |

**Idea clave:** *dos firmas, dos claves.* La del **emisor** dice *qué* es la credencial; la del **titular** dice *quién* la presenta. Si se mezclaran, el emisor podría fabricar presentaciones.

*(El laboratorio de este bloque es el bloque "ERSo 2026-002" de `scripts/e2e.sh`; lo haremos cuando lleguemos a esa ERSo.)*

---

## 9. Bloque 7 — Cartera, hardware y niveles de protección (ERSo 001 y 003)

| Concepto | Qué es | Para qué sirve (objetivo) |
|---|---|---|
| **Cartera (wallet)** | Aplicación del ciudadano donde viven sus claves y credenciales | Que el control esté del lado del usuario |
| **Hardware seguro** | Chip/zona aislada que crea y usa claves sin poder leerlas | Proteger la clave privada |
| **Keystore / Secure Enclave** | Almacén de claves de Android / iOS | Es como se accede al hardware seguro |
| **TEE / StrongBox** | Zona aislada del procesador / chip de seguridad dedicado | Dos niveles de hardware (StrongBox > TEE > Software) |
| **No exportable** | La clave se **usa** (firma) pero no se **lee** | Que ni la app ni el servidor la puedan copiar |
| **Key attestation** | Cadena de certificados que demuestra el nivel y origen de una clave | Que el servidor **compruebe** en vez de creer |
| **Nivel declarado vs verificado** | Lo que el dispositivo dice vs lo que demuestra | Evitar que alguien finja tener hardware |
| **Instancia de cartera** | Una instalación concreta, ligada a un dispositivo y un ciudadano | Administrar y revocar carteras |
| **Wallet Backend** | Servidor que coordina sin custodiar claves | Activar, recuperar, publicar el DID |
| **Recuperación** | Nueva cartera con claves nuevas y revocación de la anterior | La clave perdida **no** se recupera |
| **Simulado vs real** | En este proyecto el hardware es una simulación | Saber qué está probado y qué no |

---

## 10. Bloque 8 — Contexto y cómo leer una ERSo

| Concepto | Qué es |
|---|---|
| **eIDAS 2** (Reglamento UE 2024/1183) | La ley europea: exige una cartera de identidad digital. No menciona DID ni blockchain |
| **ARF** | La arquitectura técnica de referencia de la cartera europea (certificados, mdoc, SD-JWT VC) |
| **W3C** | Organismo que define DIDs y el modelo de credenciales |
| **OpenID Foundation** | Define OpenID4VCI y OpenID4VP |
| **IETF / ISO** | RFC de JWS, JWT, SD-JWT, COSE, CBOR / norma del mdoc |
| **ERSo** | Especificación de requisitos de software: capacidades, condiciones, "qué debe hacer", criterios de aceptación y tabla de actividades |
| **Marca "O"** | Opción fuera del alcance base, desactivada por defecto |
| **Criterio de aceptación** | Condición que debe cumplirse y **la evidencia** que lo demuestra |

### Cómo leer un criterio de aceptación
1. Subraya el **verbo** ("operativos", "se verifican", "opera sin…").
2. Identifica la **evidencia** que pide ("pruebas de…", "informe de…").
3. Decide **cómo lo demostrarías** con un comando o una prueba que otra persona pueda repetir.
4. Redáctalo así: **afirmación + prueba + dónde verla**.

---

## 11. Glosario de bolsillo (A–Z)

| Término | Una frase |
|---|---|
| Append-only | Solo se agrega; nada se edita ni se borra |
| Attestation | Evidencia firmada de qué clave es y dónde vive |
| Auditoría | Diario de quién hizo qué |
| Base58 / Base64url | Formas de escribir bytes como texto |
| CA | Autoridad que firma certificados |
| Certificado | "Esta clave pública es de este dominio", firmado por una CA |
| Checksum | Hash de un archivo completo |
| `cnf` | Clave del titular dentro de la credencial |
| Desafío | Nonce que el servidor pide firmar |
| DID / DID Document | Identificador / JSON con sus claves públicas |
| Dereferenciar | De un DID URL sacar una parte concreta |
| Firma | Prueba de autoría e integridad hecha con la privada |
| Hash | Huella del contenido |
| Idempotencia | Repetir no duplica |
| JWS / JWT | Firma compacta / token firmado |
| KB-JWT | Firma del titular al presentar |
| Multikey | Forma estándar de escribir una clave pública |
| mTLS | TLS en el que también el cliente se identifica |
| Namespace | Espacio del DID reservado a una entidad |
| Nonce | Aleatorio de un solo uso |
| OAuth2 | Estándar de tokens |
| Perfil | Combinación concreta de formato y algoritmos |
| Resolver | De un DID obtener su documento |
| Replay | Reenviar algo capturado |
| TLS | Cifrado + integridad + identidad del servidor |
| VDR | Lugar público donde viven los DID Documents |

---

## 12. Respuestas de autocomprobación

1. Cambia un solo byte de la entrada y el hash cambia por completo (efecto avalancha); por eso sirve para detectar alteraciones.
2. Firmas con la **privada**; verificas con la **pública**.
3. No. Firmar no oculta el contenido, solo prueba autoría e integridad.
4. Para que una firma o una petición capturada no se pueda reutilizar.
5. Cifrado, integridad y autenticación del servidor.
6. La CA de laboratorio. Con otra CA la validación falla porque no encuentra al emisor del certificado.
7. En TLS solo el servidor se identifica; en mTLS también el cliente presenta certificado.
8. Porque se confía en encabezados que pone nginx; si el puerto estuviera publicado, alguien podría inventarlos.
9. 401: no sabemos quién eres. 403: sabemos quién eres pero no tienes permiso.
10. Porque el token prueba que conoces un secreto, no que controlas la clave del DID; por eso se exige firmar el desafío.
11. Que dos escrituras concurrentes se pisen: la segunda recibe 412.
12. Que un reintento duplique versiones: devuelve el resultado original.
13. Contiene claves públicas, para qué sirve cada una, controlador y opcionalmente servicios. No debe contener claves privadas ni datos civiles.
14. Los `:` después del dominio pasan a `/` y se añade `did.json`.
15. Resolver: DID → documento completo. Dereferenciar: DID URL (con `#`) → una parte concreta, como una clave.
16. Porque describe una función (registro público de documentos), que puede cumplir una blockchain, un servidor web u otra cosa.
17. Que los cambios se aplican todos o ninguno; nunca queda el registro a medias.
18. La primera es el estado actual (una fila por DID); la segunda, el historial de versiones.
19. Detectar que el respaldo fue alterado antes de restaurarlo.
20. Para no mezclar historiales ni pisar datos vivos.
