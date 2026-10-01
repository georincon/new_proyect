# Identidad Digital Soberana SSI — VDR, cartera, credenciales y DID del titular

Implementación de referencia de las ERSo **2026-001 a 2026-008**. Dominio: **civica-desarrollo.avance.org.co**.

| ERSo | Tema | Informe | Piezas principales |
|---|---|---|---|
| 2026-001 | Cartera de identidad y custodia de claves en hardware | [informes/ERSo-2026-001.md](informes/ERSo-2026-001.md) | `wallet-core` (contrato `KeyCustodian`), `wallet-service` (Wallet Backend: instancias, attestation, recuperación) |
| 2026-002 | Suite criptográfica y formatos de credencial (`dc+sd-jwt`, `mso_mdoc`) | [informes/ERSo-2026-002.md](informes/ERSo-2026-002.md) | `credentials-core`, `credential-service` (OpenID4VCI 1.0 + OpenID4VP 1.0) |
| 2026-003 | Creación del DID y DID Document del titular | [informes/ERSo-2026-003.md](informes/ERSo-2026-003.md) | `wallet-core` (DID, almacén sellado), `did-core` (informe de conformidad) |
| 2026-004 | Despliegue del VDR de extensión | [informes/ERSo-2026-004.md](informes/ERSo-2026-004.md) | `deploy/`, `vdr-service` (`RegistryService`, backup/restore) |
| 2026-005 | Publicación del DID Document bajo `did:web` | [informes/ERSo-2026-005.md](informes/ERSo-2026-005.md) | `did-core` (Multikey, DidWeb, validador) |
| 2026-006 | Publicación del DID Document institucional | [informes/ERSo-2026-006.md](informes/ERSo-2026-006.md) | OAuth2 client-credentials + mTLS, namespaces |
| 2026-007 | Resolución y verificación como consumidor conforme | [informes/ERSo-2026-007.md](informes/ERSo-2026-007.md) | `did-resolver` (solo lectura) |
| 2026-008 | Ciclo de vida y trazabilidad de versiones | [informes/ERSo-2026-008.md](informes/ERSo-2026-008.md) | desafío de un solo uso, If-Match, idempotencia, historial |

**Informes finales (manuales didácticos con sesiones de terminal reales):** ERSo 001 → [informes/INFORME-FINAL-ERSo-2026-001.md](informes/INFORME-FINAL-ERSo-2026-001.md) · ERSo 002 → [informes/INFORME-FINAL-ERSo-2026-002.md](informes/INFORME-FINAL-ERSo-2026-002.md) · ERSo 003 → [informes/INFORME-FINAL-ERSo-2026-003.md](informes/INFORME-FINAL-ERSo-2026-003.md) · ERSo 004 → [informes/INFORME-FINAL-ERSo-2026-004.md](informes/INFORME-FINAL-ERSo-2026-004.md) · ERSo 005 → [informes/INFORME-FINAL-ERSo-2026-005.md](informes/INFORME-FINAL-ERSo-2026-005.md) · ERSo 006 → [informes/INFORME-FINAL-ERSo-2026-006.md](informes/INFORME-FINAL-ERSo-2026-006.md) · ERSo 007 → [informes/INFORME-FINAL-ERSo-2026-007.md](informes/INFORME-FINAL-ERSo-2026-007.md) · ERSo 008 → [informes/INFORME-FINAL-ERSo-2026-008.md](informes/INFORME-FINAL-ERSo-2026-008.md) (cada uno también en `.docx` y `.pdf`) · Cómo se resolvió cada criterio: [docs/COMO-SE-RESOLVIO-CADA-CRITERIO.md](docs/COMO-SE-RESOLVIO-CADA-CRITERIO.md) · Marco conceptual: [docs/MARCO-CONCEPTUAL.md](docs/MARCO-CONCEPTUAL.md)

**Para estudiar y exponer:** [docs/GUIA-EXPOSICION.md](docs/GUIA-EXPOSICION.md) (el capítulo 11 cubre 001–003) · Perfil de credenciales: [docs/PERFIL-CREDENCIALES.md](docs/PERFIL-CREDENCIALES.md) · Referencias normativas: [docs/VERSIONES-NORMATIVAS.md](docs/VERSIONES-NORMATIVAS.md) · Análisis de las bases EUDI: [docs/ANALISIS-BASES-EUDI.md](docs/ANALISIS-BASES-EUDI.md) · Diseño para Android (sin compilar): [docs/ANDROID-REFERENCIA.md](docs/ANDROID-REFERENCIA.md) · Evidencias: [evidencias/](evidencias/)

## Arquitectura

```
   Dispositivo del titular (SIMULADO en este proyecto)          Internet / red institucional
   ┌──────────────────────────────┐        :443 lectura pública   :8443 escritura (mTLS)   :8444 cartera y credenciales
   │ Holder App (wallet-sim)      │             │                          │                       │
   │  SSI SDK (wallet-core)       │──TLS────────┼──────────────────────────┼───────────────────────┤
   │  Secure Hardware (simulado)  │             ▼                          ▼                       ▼
   └──────────────────────────────┘        ┌──────────────────────────────────────────────────────────┐
                                           │ nginx  (única pieza con puertos publicados)              │
                                           └───────┬──────────────────┬───────────────────┬───────────┘
                                             :8080 │ :8081            │ :8090             │ :8100      (red interna Docker)
                                        ┌──────────┴───────┐   ┌──────┴───────┐   ┌───────┴────────────┐
                                        │ vdr-service      │◀──│ wallet-      │   │ credential-service │
                                        │ (004–008)        │mTLS│ service     │   │ Emisor OpenID4VCI  │
                                        │ lectura + escrit.│   │ (001, 003)   │   │ Verificador OID4VP │
                                        └──────────┬───────┘   └──────┬───────┘   └───────┬────────────┘
                                                   └────── PostgreSQL ┘  (historial y auditoría append-only)   │
                                                                          resuelve el DID del emisor ◀──────────┘
```

Módulos Gradle:

| Módulo | Rol | ERSo |
|---|---|---|
| `did-core` | Base58, Multikey, `did:web`, JSON canónico, JWS ES256, validador, **informe de conformidad** | 005, 003 |
| `did-resolver` | Consumidor de solo lectura (resolver, dereferenciar, verificar) y **resolución de claves por DID** | 007, 002 |
| `vdr-service` | Registro: canal de lectura, canal de escritura, desafíos, versiones, respaldo. Soporta namespace comodín de un nivel (`titulares/*`) | 004, 006, 008 |
| `credentials-core` | SD-JWT VC, mdoc (CBOR/COSE), perfiles, registro de ejecución, matriz de claves | 002 |
| `wallet-core` | Lado titular: `KeyCustodian`, custodio simulado, DID del titular, almacén sellado | 001, 003 |
| `wallet-sim` | **Dispositivo simulado**: autoridad de attestation de laboratorio, Holder App, cartera OpenID4VCI/VP y escenarios por CLI | 001, 002, 003 |
| `wallet-service` | **Wallet Backend**: instancias, verificación de key attestation, recuperación, respaldo cifrado, publicación del DID | 001, 003 |
| `credential-service` | **Emisor** OpenID4VCI 1.0 y **verificador** OpenID4VP 1.0 | 002 |
| `did-tools` | CLI de la "entidad" (keygen, build-doc, write, admin, resolve, sign, verify) | 006, 008 |

## Puesta en marcha

```bash
./gradlew installDist                                       # compila todos los módulos (JDK 21)
bash scripts/gen-dev-certs.sh                               # CA + TLS + certificados cliente (solo laboratorio); idempotente
bash scripts/gen-env.sh                                     # secretos aleatorios en deploy/.env; actualiza un .env existente
cd deploy && docker compose up -d                           # postgres + vdr + wallet + credential + nginx
```

* La extensión VDR está **apagada por defecto** (`VDR_ENABLED=false` si no se define). `gen-env.sh` la enciende en `deploy/.env` para el laboratorio.
* El servicio `credential` necesita `evidencias/work/avance.json` (la clave del emisor de laboratorio, generada por el recorrido E2E o con `did-tools keygen`); sin ella se reinicia hasta que exista. El servicio `wallet` necesita `evidencias/work/lab-attestation-root.pem` para aceptar carteras con hardware (sin ella solo acepta `SOFTWARE` sin evidencia).
* Puertos en el host: `8443` (lectura pública), `9443` (escritura mTLS y portales de administración), `8444` (cartera y credenciales).

## Pruebas

```bash
# Unitarias + integración (PostgreSQL real). Levanta una BD desechable con dos bases:
docker run -d --name vdr-test-pg -e POSTGRES_USER=vdr -e POSTGRES_PASSWORD=vdr -e POSTGRES_DB=vdr -p 127.0.0.1:55432:5432 postgres:16-alpine
docker exec vdr-test-pg psql -U vdr -d vdr -c "CREATE DATABASE wallet_test"
TEST_DB_URL=jdbc:postgresql://127.0.0.1:55432/wallet_test ./gradlew test     # las pruebas vacían las tablas: use una base propia
python3 scripts/summarize-tests.py .                        # evidencias/pruebas-automaticas.md

# Recorrido extremo a extremo por los canales reales (TLS + mTLS), un bloque por ERSo:
bash scripts/e2e.sh                                         # compila, reconstruye imágenes, reinicia desde cero y deja el log en evidencias/e2e-*.log
```

Sin `TEST_DB_URL` las pruebas de integración se **omiten** (no fallan); las de `did-core`, `did-resolver`, `credentials-core`, `wallet-core`, `credential-service` y las de "registro apagado" corren igual. Cada clase de prueba incluye una **guarda** que falla si algún método de prueba devuelve valor (JUnit lo ignoraría en silencio).

## Estado y límites honestos

* **130/130** pruebas automáticas y **121/121** comprobaciones E2E en verde (ver `evidencias/`; el último log es `evidencias/e2e-20260929-155349.log`). Los informes de las ERSo 004–008 citan el log del 28-sep (56 comprobaciones), cuyos bloques siguen incluidos en el recorrido actual.
* **No hay hardware seguro real** en ninguna parte: el custodio es una simulación en software (declara `SOFTWARE`) y las *attestations* las firma una autoridad de **laboratorio**. La parte Android está solo **diseñada** ([docs/ANDROID-REFERENCIA.md](docs/ANDROID-REFERENCIA.md)).
* **No hay interoperabilidad probada con terceros:** las pruebas cruzadas OpenID4VCI/VP son entre componentes de este proyecto. El perfil difiere del de EUDI en la clave del emisor (DID en vez de `x5c`), y faltan DPoP, JWE y la presentación de mdoc.
* **Se publican DID de ciudadanos** vía el Wallet Backend (decisión del diagrama de arquitectura): riesgo de correlación documentado en el informe de la ERSo 003.
* TLS con **CA de laboratorio**. Para el dominio real hace falta DNS + certificado público en el canal de lectura y una CA privada para las entidades. **No se desplegó en el dominio real ni se probó desde internet.**
* Base58/Multikey son implementación propia, verificados por ida y vuelta y por el prefijo `zDn`; conviene contrastarlos con una biblioteca o vectores de terceros antes de producción. Lo mismo aplica a la implementación de SD-JWT VC y mdoc, escrita sobre el JDK y `com.upokecenter:cbor`.
* Los secretos y claves de laboratorio (`deploy/.env`, `deploy/certs`, `evidencias/work/*.json`) no son para producción: allí, gestor de secretos y KMS/HSM.
