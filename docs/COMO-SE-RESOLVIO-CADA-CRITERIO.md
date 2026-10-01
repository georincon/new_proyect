# Cómo se planteó la resolución de los criterios de aceptación (ERSo 004 a 008)

**Para qué sirve este documento.** No explica *qué* hace el código, sino *cómo se piensa* un criterio de aceptación para llegar de la frase del PDF a una prueba que otra persona pueda repetir. Si dominas la receta, puedes resolver y defender cualquier criterio, también los de las ERSo 001 a 003.

---

## 1. La receta (siete pasos)

| # | Paso | Pregunta que te haces | Producto |
|---|---|---|---|
| 1 | **Descomponer el criterio** | ¿Cuál es el *verbo*, cuál el *objeto* y qué *evidencia* pide? | Frase partida en 3 |
| 2 | **Convertirlo en una afirmación comprobable** | ¿Qué tendría que ser cierto y cómo lo vería un tercero sin fiarse de mí? | Una oración con "se puede observar que…" |
| 3 | **Buscar el caso negativo** | ¿Cómo intentaría *romperlo* un atacante o un error? | Lista de intentos de ataque |
| 4 | **Elegir el mecanismo** | ¿Qué pieza del sistema lo *garantiza*? ¿Puede ser una capa baja (base de datos, proxy) en vez de código? | Decisión de diseño |
| 5 | **Implementar lo mínimo y escribir la prueba junto con él** | ¿Cómo falla esta prueba si el mecanismo no existe? | Prueba con nombre = criterio |
| 6 | **Doble evidencia** | ¿Está probado rápido y repetible (automático) *y* contra el sistema real (extremo a extremo)? | Prueba automática + línea del log E2E |
| 7 | **Declarar los límites** | ¿Qué *no* se probó o está simulado? | Sección de límites del informe |

### Por qué cada paso importa

* **Paso 1 — el verbo manda.** "Se sirve", "se rechaza", "queda registrada", "opera sin" son verbos distintos y piden pruebas distintas. Un verbo de *rechazo* exige una prueba **negativa**; uno de *registro*, mostrar el **dato guardado**; uno de *operación con la opción apagada*, una **regresión**.
* **Paso 3 — sin caso negativo es una demostración, no una prueba.** Que algo funcione cuando todo está bien no dice nada sobre seguridad. Por eso casi cada criterio tiene "su ataque".
* **Paso 4 — garantía en la capa más baja posible.** El historial inalterable se protege con un *trigger* en PostgreSQL, no solo con código; la separación de canales, en nginx. Así un error de programación no la rompe.
* **Paso 6 — dos evidencias, dos preguntas.** La prueba automática responde "¿la lógica es correcta?"; el recorrido E2E responde "¿funciona por la red real, con TLS y certificados?". Cada una descubre cosas que la otra no.
* **Paso 7 — decir primero lo que no está hecho** protege tu credibilidad ante el grupo.

### Plantilla para resolver un criterio (rellénala tú)

| Campo | Contenido |
|---|---|
| Criterio (texto del PDF) | |
| Verbo / objeto / evidencia que pide | |
| Afirmación comprobable | |
| Casos negativos (≥2) | |
| Mecanismo que lo garantiza (y en qué capa) | |
| Prueba automática (nombre) | |
| Evidencia E2E (qué línea del log) | |
| Límites | |

---

## 2. Decisiones transversales que explican muchos criterios

| Decisión | Qué resuelve | Criterios que la usan |
|---|---|---|
| **JSON canónico** (claves ordenadas, sin espacios) | Lo que se *hashea*, se *firma* y se *sirve* son los mismos bytes; comparar hashes es exacto | 005 C2, 006 C4, 008 C4–C5 |
| **Confirmar leyendo la URL pública** | "Publicado" significa "lo que cualquiera lee es lo que escribí", no "lo guardé en la base" | 005 C1–C2, 006 C1, 008 C5–C6 |
| **Falla cerrada** (*fail-closed*) | Ante la duda, lo riesgoso queda apagado o se rechaza | 004 C3, 008 |
| **Lista blanca de propiedades** | Es más seguro permitir solo lo conocido que intentar prohibir lo peligroso | 005 C3, 006 C3, 008 C3 |
| **Tres capas para escribir** (mTLS + token + firma del desafío) | Robar una sola cosa no basta | 006 C2, 008 C2 |
| **Historial *append-only* con trigger** | La traza no se reescribe ni con acceso directo a la base | 004 C1, 008 C7 |
| **Dos canales** (lectura pública / escritura autenticada) | Leer es libre; cambiar exige demostrar | 004, 006 |

---

## 3. Aplicación a cada ERSo

### ERSo 004 — Despliegue del VDR de extensión

| Criterio | Verbo y evidencia | Caso negativo | Mecanismo | Prueba |
|---|---|---|---|---|
| **1** Registro, actualización y trazabilidad operativos | *Operativos*: mostrar crear + actualizar + historial | Actualizar con la versión equivocada; borrar historial | `PUT` con `If-Match`; tabla de versiones *append-only* | `Erso004RegistryTest › criterio 1` |
| **2** Respaldo y recuperación **verificados** | *Verificados*: hay que restaurar y **comparar** | Restaurar sobre datos vivos; respaldo alterado | `checksum` SHA-256; restaurar solo sobre registro vacío; `documentsVerified` | `… › criterio 2` (respaldo → `TRUNCATE` → restauración → mismos hashes públicos) |
| **3** El camino base opera con la opción apagada | *Opera sin*: **regresión** con `VDR_ENABLED=false` | Que el servicio se conecte a la base o abra el canal de escritura con la opción apagada | Rutas del registro **no se registran**; `Main.kt` ni abre la base | `… › criterio 3` (3 pruebas, sin base de datos) |

**Cómo pensé la 004:** el título dice "despliegue", pero lo difícil son tres *negaciones*: que lo opcional **no** moleste, que lo perdido **sí** vuelva y que lo viejo **no** se pueda reescribir. Por eso el diseño empieza por el interruptor (criterio 3) y por el contenedor de PostgreSQL con historial protegido (criterio 1) antes de pensar en el respaldo.

### ERSo 005 — Publicar el DID Document bajo `did:web`

| Criterio | Verbo y evidencia | Caso negativo | Mecanismo | Prueba |
|---|---|---|---|---|
| **1** Se sirve por HTTPS en la URL calculada | *Se sirve*: leer desde la URL pública | Pedir otra ruta; usar método no permitido | Regla DID→URL (`DidWeb`); nginx con TLS y solo `GET/HEAD` de `*/did.json` | `Erso005DidWebTest › criterio 1 y 2` |
| **2** Contiene la clave pública correcta | *Correcta*: comparar documento y **hash** | Documento servido distinto del escrito | JSON canónico; `ETag`/`X-Content-Hash`; la clave decodifica al mismo punto EC | misma prueba + E2E (hash servido = hash devuelto) |
| **3** No expone claves privadas ni datos civiles | *No expone*: **revisar el contenido publicado** | Intentar subir `d`, `privateKeyMultibase`, `credentialSubject`, `birthDate` | Validador: lista blanca + detección en cualquier profundidad | `criterio 3` (incluso busca la **clave privada real** en el texto servido) |
| **4** Un `id` desajustado se rechaza y el base opera con la extensión apagada | Pruebas **negativas y de regresión** | Documento con `id` ajeno; DID de ciudadano | `ID_MISMATCH` → 422; namespace no reservado → 412 | `criterio 4` + regresión de la 004 C3 |

**Cómo pensé la 005:** es el criterio donde más importa el *paso 3*. "No expone claves privadas" no se puede probar mirando un documento bueno; hay que **fabricar documentos malos** (con cada campo prohibido) y comprobar que ninguno entra.

### ERSo 006 — Publicar el DID Document institucional

| Criterio | Verbo y evidencia | Caso negativo | Mecanismo | Prueba |
|---|---|---|---|---|
| **1** Se sirve y es **resoluble** | Lectura pública y que el cliente consumidor lo resuelva | — | Canal público separado + `did-resolver` | `criterio 1` + E2E `resolve` |
| **2** Escritura **autenticada y trazada**, limitada al namespace | *Autenticada*, *trazada*, *limitada*: tres cosas | Credenciales malas; certificado de **otra** entidad; CA desconocida; escribir en el namespace ajeno; sin certificado | mTLS en nginx + OAuth2 *client credentials* + permiso por namespace + auditoría | `criterio 2` + 5 intentos negativos en el E2E |
| **3** Sin claves privadas ni datos civiles | Revisar contenido | Igual que 005 | Mismo validador | `criterio 3` |
| **4** La evidencia (versión, hash, URL) **queda registrada** | *Queda registrada*: mostrar el registro | — | Tabla `operations` + `GET /operations/{id}` | `criterio 4` |

**Cómo pensé la 006:** el criterio 2 tiene tres adjetivos y cada uno es una capa distinta; por eso hay **cinco** intentos negativos, uno por cada forma de quebrar una de las capas.

### ERSo 007 — Consumidor conforme

| Criterio | Verbo y evidencia | Caso negativo | Mecanismo | Prueba |
|---|---|---|---|---|
| **1** Prueba firmada por la clave resuelta **se valida** | Prueba **positiva** | — | `ProofVerifier`: resolver → clave → verificar | `CRITERIO 1` |
| **2** Prueba de clave **ajena se rechaza** | Prueba **negativa** | Firma con otra clave pero con el `kid` de Avance | Verificar contra la clave **publicada**, no contra lo que dice la prueba | `CRITERIO 2` (`INVALID_SIGNATURE`) |
| **3** Documento no conforme, `id` desajustado o inexistente se rechaza | Prueba **negativa** | `id` ajeno; DID inexistente; desactivado; DID distinto al esperado | Perfil `CONSUMER` del validador; códigos de error normativos | `CRITERIO 3` |
| **4** **No** escribe ni publica | **Revisión de interfaz** + solo lectura | Que el módulo tenga una operación de escritura | Interfaz solo con `resolve`/`dereference`; el módulo **no depende** del servicio de registro | `CRITERIO 4` (registra todos los métodos HTTP: solo `GET`; reflexión sobre la interfaz) |

**Cómo pensé la 007:** un consumidor se prueba por **lo que se niega a aceptar**. El criterio 4 es el más interesante: "no hace X" se demuestra de dos maneras complementarias, **observando el comportamiento** (solo GET) y **limitando la capacidad** (ni siquiera tiene la dependencia para escribir).

### ERSo 008 — Ciclo de vida y trazabilidad

| Criterio | Verbo y evidencia | Caso negativo | Mecanismo | Prueba |
|---|---|---|---|---|
| **1** Precondiciones; si falla una **no se emite desafío** | *No se emite*: contar filas | 5 fallos distintos (namespace ajeno, sin mTLS, ruta no reservada, cuenta deshabilitada, perfil sin definir) | `preconditionFailures` antes de crear el desafío | `criterio 1` (0 filas en `challenges`) |
| **2** Desafío **de un solo uso**, con tipo y audiencia | Registro de emisión | Reusar; usar un desafío `UPDATE` para `DEACTIVATE`; expirado | Consumo atómico; tipo y audiencia dentro del desafío | `criterio 2` |
| **3** Sin claves privadas; `id` y controladores correctos | Documento de la operación | `id` ajeno; controlador ajeno; clave privada | Validador PUBLISHER | `criterio 3` (0 documentos registrados) |
| **4** Versión esperada; devuelve versión, hash y URL | Respuesta del registro | Sin `If-Match` (428); versión equivocada (412); reintento duplicado | `If-Match` + `Idempotency-Key` bajo bloqueo de la fila | `criterio 4` |
| **5** Confirma por lectura y hash | Comparación | Contenido servido distinto | `confirm()` | `criterio 5 y 6` |
| **6** Sin respuesta en plazo → **pendiente**, no publicada | Estado de la operación | Lector que no responde | `PENDING` + `reconcile` | `criterio 5 y 6` |
| **7** La traza **reconstruye** el estado | Historial | Reescribir el historial | Versiones *append-only* + `state?at=` | `criterio 7` + trigger en el E2E |

**Cómo pensé la 008:** es una **cadena de siete eslabones** que coincide con el recorrido de una escritura; cada criterio es "¿este eslabón falla de forma segura?". La clave fue ordenar la implementación **en el mismo orden que la ERSo** y que cada eslabón rechazara **antes** de gastar el siguiente (por eso las precondiciones van antes del desafío, y el desafío se consume *antes* de escribir).

---

## 4. Tres ejercicios para practicar la receta

1. **Sin mirar este documento**, rellena la plantilla del §1 para el **criterio 4 de la ERSo 005** (id desajustado). Después compara con la fila de la tabla.
2. Elige un criterio de la ERSo 006 y escribe **dos casos negativos adicionales** que no estén en la tabla. ¿Qué capa los frenaría?
3. Toma el **criterio 7 de la ERSo 008** y explica por qué la protección *append-only* debe estar en la base de datos y no en el código.

## 5. Siguiente paso de la capacitación

1. **Cerrar la ERSo 004** (te falta el criterio 2 en práctica y la lección de defensa).
2. **ERSo 005**, aplicando la receta *tú* a cada criterio antes de ver cómo se resolvió.
3. Después **006 → 008 → 007**, y por último **001 → 003 → 002**.
