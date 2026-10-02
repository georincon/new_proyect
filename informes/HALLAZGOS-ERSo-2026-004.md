# Hallazgos de análisis externo — ERSo 2026-004
## Despliegue del registro verificable de datos (VDR) de extensión

| | |
|---|---|
| Proyecto propio | Identidad Digital Soberana SSI (`/home/geovani/Descargas/generic/bitacora/new_proyect`) |
| Fecha del análisis | 2 de octubre de 2026 |
| Carpetas analizadas | `/home/geovani/Workspace/Wallet/Wallet`, `…/Verifier`, `…/Issuer`, `…/Trust`, `…/librerias` |
| Versión del informe | Actualizado el 2 de octubre de 2026: se añaden los hallazgos de la carpeta `librerias` y el análisis a fondo del gestor de listas de confianza |
| Cobertura de esta ERSo | **Parcial (un módulo VDR completo en `av-app-android-wallet-ui`); ninguna biblioteca implementa un registro** |
| Método | Lectura de código y documentación; **no se compiló ni se ejecutó** ninguno de los proyectos externos |

---

## 1. Resumen

El único VDR encontrado en todas las carpetas es el módulo `vdr-extension-logic` del proyecto `av-app-android-wallet-ui`, cuyo README dice que implementa la ERSo 2026-004. Se parece a nuestro VDR en lo esencial: está **apagado por defecto**, **no afecta al camino base** y tiene **respaldo y restauración**. Se diferencia en que guarda los documentos en un repositorio Git, no tiene autenticación y no separa canales de lectura y escritura. **Complemento (carpeta `librerias`):** se buscaron registros de DID en las diecisiete carpetas de bibliotecas, servidores y demostraciones. **No se encontró ninguno**: solo hay código de **lectura** de DID (sintaxis en OpenID4VP y búsqueda de claves en SD-JWT). El módulo `vdr-extension-logic` sigue siendo el único VDR externo.

## 2. Proyectos citados en este informe

| Carpeta | Proyecto | Qué es |
|---|---|---|
| Wallet | `av-app-android-wallet-ui` | App Android de verificación de edad (versión derivada de la anterior); incluye el módulo `vdr-extension-logic` |
| librerias | `eudi-lib-jvm-openid4vp-kt` | Biblioteca de OpenID4VP (autenticación del verificador, cifrado de respuesta, DID) |
| librerias | `eudi-lib-jvm-sdjwt-kt` | Biblioteca de SD-JWT y SD-JWT VC (verificación, métodos de clave del emisor) |
| librerias | `eudi-web-recruitment-service-demo` | Demostración de portal de reclutamiento que usa emisor y verificador EUDI |

## 3. Hallazgos

La columna *Criterio* indica a qué criterio de aceptación de la ERSo se aplica el hallazgo (el detalle está en la sección 4).

| # | Proyecto (carpeta/proyecto) | Archivo o lugar | Qué se encontró | Relación con nuestro proyecto | Criterio |
|---|---|---|---|---|---|
| 1 | `Wallet/av-app-android-wallet-ui` | `gradle.properties (línea 26) y settings.gradle.kts (línea 77)` | `vdrExtension.enabled=false` por defecto; con ese valor el módulo ni siquiera se declara en el build. | Equivale a nuestro `VDR_ENABLED` apagado por defecto, pero a nivel de compilación. | C3 |
| 2 | `Wallet/av-app-android-wallet-ui` | `vdr-extension-logic/README.md` | Módulo Kotlin/JVM puro, sin dependencia de Android; no participa en `LibraryModule` ni en `assembly-logic`, por lo que no puede llegar al APK; corre como proceso aparte. | Es la forma de cumplir "sin alterar el camino base" a nivel de estructura. | C3 |
| 3 | `Wallet/av-app-android-wallet-ui` | `vdr-extension-logic/src/main/kotlin/…/Main.kt` | Servidor independiente (Ktor/Netty); puerto 8089 por `VDR_PORT`; datos en `VDR_DATA_DIR` (por defecto `vdr-data`). | Nuestro VDR corre en contenedor con nginx delante. | C1 |
| 4 | `Wallet/av-app-android-wallet-ui` | `…/routes/VdrRoutes.kt` | Rutas: `GET /vdr/v1/health`, `POST/PUT/GET /vdr/v1/identifiers…`, `GET …/history`, `POST /vdr/v1/backup`, `POST /vdr/v1/restore`. | Superficie mínima; nuestro VDR añade desafíos, operaciones, auditoría y administración. | C1 |
| 5 | `Wallet/av-app-android-wallet-ui` | `…/backup/BackupService.kt y src/test/…/BackupRestoreTest.kt` | Respaldo y restauración mediante `git bundle`; la prueba comprueba que el registro restaurado queda idéntico al del momento del respaldo y que restaurar un respaldo inexistente falla con error claro. | Equivale a nuestro respaldo `vdr-backup/1` (con checksum y restauración solo sobre registro vacío). | C2 |
| 6 | `Wallet/av-app-android-wallet-ui` | `…/registry/GitBackedRegistry.kt` | Cada registro o actualización es un commit; el historial de Git es la trazabilidad. Es `@Synchronized`. | Nuestro historial es una tabla PostgreSQL con disparadores que impiden modificarla. | C1 |
| 7 | `Wallet/av-app-android-wallet-ui` | `…/routes/VdrRoutes.kt` | No contiene referencias a autenticación ni a mTLS (cero coincidencias de `authenticate`, `Bearer`, `mtls`). | Todo el que alcance el puerto puede escribir; nuestro VDR separa un canal público de lectura y otro autenticado de escritura. | C1 (límite) |
| 8 | `Wallet/av-app-android-wallet-ui` | `vdr-extension-logic/vdr-data/` | Datos de ejemplo versionados: un `did.json` de `did:web:ejemplo.org` con dos commits, y un backup `.bundle`. | Muestra del resultado de ejecutar el módulo. | C1, C2 |
| 9 | `librerias/eudi-lib-jvm-openid4vp-kt`<br>`librerias/eudi-lib-jvm-sdjwt-kt` | `eudi-lib-jvm-openid4vp-kt (internal/DID.kt) y eudi-lib-jvm-sdjwt-kt (NimbusSdJwtVcVerifierFactory.kt)` | La búsqueda de `did:web`, `DidDocument` y `DIDLookup` en las bibliotecas solo encuentra código de consumo: sintaxis de DID y funciones inyectables para obtener claves. | Confirma que el lado productor (registro, escritura, respaldo) es propio de nuestro proyecto y del módulo `vdr-extension-logic`. | Fuera de los criterios |
| 10 | `librerias/eudi-web-recruitment-service-demo` | `eudi-web-recruitment-service-demo/README.md` | Arquitectura por capas con puertos y PostgreSQL; componentes externos detrás de puertos (`IVerifierPort`, `IIssuerPort`). | Patrón de aislar una dependencia opcional detrás de un puerto, similar a mantener el registro fuera del camino base. | C3 (analogía) |

## 4. Aplicación a los criterios de aceptación de la ERSo 004

Para cada criterio: lo que exige el PDF de la ERSo, dónde está nuestra evidencia y qué hallazgos externos se le aplican.

### Criterio 1. El registro y la actualización de entradas son operativos.

| | |
|---|---|
| Evidencia que pide la ERSo | Pruebas de registro, actualización y trazabilidad. |
| Nuestra evidencia | `INFORME-FINAL-ERSo-2026-004 §17` |
| Hallazgos aplicables | #3 (`Wallet/av-app-android-wallet-ui`); #4 (`Wallet/av-app-android-wallet-ui`); #6 (`Wallet/av-app-android-wallet-ui`); #7 (`Wallet/av-app-android-wallet-ui`); #8 (`Wallet/av-app-android-wallet-ui`) |
| Qué aporta el análisis | El módulo de la app de verificación de edad registra, actualiza, resuelve por versión y lista el historial con Git, con pruebas propias. |

### Criterio 2. El respaldo y la recuperación del registro se verifican.

| | |
|---|---|
| Evidencia que pide la ERSo | Prueba de respaldo y restauración. |
| Nuestra evidencia | `INFORME-FINAL-ERSo-2026-004 §18` |
| Hallazgos aplicables | #5 (`Wallet/av-app-android-wallet-ui`); #8 (`Wallet/av-app-android-wallet-ui`) |
| Qué aporta el análisis | Respaldo y restauración con `git bundle`, con una prueba que verifica que el registro restaurado es idéntico y que restaurar un respaldo inexistente falla con error claro. |

### Criterio 3. El camino base opera sin el registro desplegado.

| | |
|---|---|
| Evidencia que pide la ERSo | Pruebas de regresión con la opción apagada. |
| Nuestra evidencia | `INFORME-FINAL-ERSo-2026-004 §19` |
| Hallazgos aplicables | #1 (`Wallet/av-app-android-wallet-ui`); #2 (`Wallet/av-app-android-wallet-ui`); #10 (`librerias/eudi-web-recruitment-service-demo`) |
| Qué aporta el análisis | El módulo está apagado por defecto y aislado del build, de modo que no puede llegar a la app. Es una estrategia estructural; no se encontraron pruebas de regresión con la opción apagada. |

## 5. Diferencias de diseño frente a nuestro proyecto

| Tema | Proyectos externos | Nuestro proyecto |
|---|---|---|
| Apagado por defecto | Propiedad de Gradle (a nivel de build) | Variable `VDR_ENABLED` (a nivel de ejecución; solo el valor exacto `true` lo enciende) |
| Almacenamiento | Repositorio Git en disco | PostgreSQL |
| Trazabilidad | Historial de commits | Tabla de versiones y auditoría de solo-agregar |
| Autenticación | Ninguna | mTLS + OAuth2 + prueba de posesión |
| Canales | Un solo servidor | Lectura pública y escritura autenticada, separadas |
| Respaldo | `git bundle` | JSON `vdr-backup/1` con checksum |

## 6. Qué aprender y qué hacer con esto

* Estudiar `Wallet/av-app-android-wallet-ui` `vdr-extension-logic` para explicar la condición "opcional y sin afectar el camino base": la estrategia de aislar el módulo del build es más fuerte que un simple interruptor.
* Idea a evaluar: el `git bundle` como formato alternativo de respaldo, con trazabilidad completa en un solo archivo.
* Contrastar para la defensa: su módulo cubre el criterio de respaldo y restauración, pero no el de ejecución con la extensión apagada comprobada por pruebas negativas.
* Hallazgo negativo útil para la defensa: no existe en el ecosistema de bibliotecas de referencia un registro de DID; la ERSo 004 no se apoya en una pieza estándar.

## 7. Límites de este informe

* Todo proviene de **lectura de código**; el comportamiento real no se verificó ejecutando.
* Se citan archivos y, cuando se anotó, números de línea (con `~` si son aproximados); las versiones de los proyectos son las que había en disco el 2 de octubre de 2026.
* "No encontré" significa que las búsquedas realizadas no lo hallaron, no que no exista en ninguna parte del código.
* Los proyectos de referencia EUDI se declaran a sí mismos como versiones de desarrollo no aptas para producción.
