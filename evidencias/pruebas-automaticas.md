# Evidencia — pruebas automáticas

Generado: 2026-09-29T16:00:58
**Total: 130 pruebas · 0 fallos**


## Módulo `did-core` — 14 pruebas, 0 fallos

| Clase | Prueba | Resultado |
|---|---|---|
| DidCoreTest | multikey P-256 empieza por zDn y hace round trip | PASA |
| DidCoreTest | service http se rechaza al publicar | PASA |
| DidCoreTest | clave privada se detecta en cualquier profundidad | PASA |
| DidCoreTest | documento construido es valido en ambos perfiles | PASA |
| DidCoreTest | ruta a DID es el camino inverso | PASA |
| DidCoreTest | jws firma y verifica, rechaza clave ajena y manipulacion | PASA |
| DidCoreTest | id desajustado se rechaza | PASA |
| DidCoreTest | did web se convierte a URL segun el metodo | PASA |
| DidCoreTest | referencias colgantes, tipo de clave y controlador ajeno | PASA |
| DidCoreTest | multikey rechaza claves ajenas o corruptas | PASA |
| DidCoreTest | canonico es determinista y el hash cambia con el contenido | PASA |
| DidCoreTest | datos civiles y propiedades desconocidas se rechazan al publicar pero no bloquean al consumidor | PASA |
| DidCoreTest | jws rechaza alg none | PASA |
| DidCoreTest | base58 round trip incluye ceros iniciales | PASA |

## Módulo `did-resolver` — 12 pruebas, 0 fallos

| Clase | Prueba | Resultado |
|---|---|---|
| ResolverTest | CRITERIO 3 - did inexistente, invalido o desactivado se rechaza | PASA |
| ResolverTest | clave que no existe o no esta autorizada para la relacion se rechaza | PASA |
| ResolverTest | CRITERIO 4 - el consumidor solo emite GET | PASA |
| ResolverTest | CRITERIO 2 - prueba firmada por clave ajena se rechaza | PASA |
| ResolverTest | resuelve un did web por https y devuelve metadatos | PASA |
| ResolverTest | pruebas malformadas se rechazan | PASA |
| ResolverTest | no sigue redirecciones ni acepta content-type ajeno ni documentos gigantes | PASA |
| ResolverTest | guarda - todo metodo de prueba devuelve void para que JUnit lo ejecute | PASA |
| ResolverTest | dereferenciar devuelve el metodo de verificacion concreto | PASA |
| ResolverTest | CRITERIO 3 - documento con id desajustado se rechaza | PASA |
| ResolverTest | CRITERIO 1 - prueba firmada por la clave resuelta se valida | PASA |
| ResolverTest | CRITERIO 4 - la interfaz publica no expone operaciones de escritura | PASA |

## Módulo `vdr-service` — 29 pruebas, 0 fallos

| Clase | Prueba | Resultado |
|---|---|---|
| ConsumerInteropTest | el consumidor resuelve y verifica lo publicado, rechaza clave ajena y respeta rotacion y desactivacion | PASA |
| Erso004RegistryTest | criterio 2 - respaldo, perdida total y restauracion dejan el registro identico | PASA |
| Erso004RegistryTest | criterio 1 - registro, actualizacion y trazabilidad son operativos | PASA |
| Erso004RegistryTest | criterio 3 - con el registro encendido el camino base sigue igual | PASA |
| Erso004RegistryTest | criterio 3 - con el registro apagado el camino base responde y los endpoints del VDR no existen | PASA |
| Erso004RegistryTest | solo el administrador puede respaldar y restaurar | PASA |
| Erso004RegistryTest | criterio 3 - por defecto la extension esta apagada | PASA |
| Erso004RegistryTest | paso 2 - el adaptador traduce DID a la forma del registro segun el metodo | PASA |
| Erso005DidWebTest | criterio 3 - el documento publicado solo contiene material publico | PASA |
| Erso005DidWebTest | paso 1 - el identificador did web sale del dominio configurado | PASA |
| Erso005DidWebTest | criterio 4 - un id desajustado se rechaza | PASA |
| Erso005DidWebTest | criterio 1 y 2 - se sirve en la URL calculada, con la clave publica correcta y hash igual | PASA |
| Erso005DidWebTest | no se publica por defecto ningun DID de ciudadano - solo namespaces reservados | PASA |
| Erso006InstitutionalTest | criterio 3 - el documento publicado no expone claves privadas ni datos civiles | PASA |
| Erso006InstitutionalTest | criterio 2 - la escritura esta autenticada, trazada y limitada al namespace de la entidad | PASA |
| Erso006InstitutionalTest | canales separados - el canal publico no escribe y el de escritura no sirve documentos | PASA |
| Erso006InstitutionalTest | una entidad no puede colisionar con el namespace de otra | PASA |
| Erso006InstitutionalTest | criterio 4 - la evidencia de publicacion version hash y URL queda registrada | PASA |
| Erso006InstitutionalTest | criterio 1 - el documento institucional se sirve por el canal publico y es resoluble | PASA |
| Erso008LifecycleTest | criterio 4 - la escritura exige version esperada y devuelve version hash y URL publica | PASA |
| Erso008LifecycleTest | prueba de posesion - clave ajena, hash distinto y rotacion | PASA |
| Erso008LifecycleTest | criterio 1 - si falla una precondicion no se emite desafio | PASA |
| Erso008LifecycleTest | criterio 3 - el documento no incluye claves privadas y id y controladores corresponden al recurso | PASA |
| Erso008LifecycleTest | criterio 5 y 6 - confirmacion por lectura y hash, y operacion pendiente si no hay respuesta | PASA |
| Erso008LifecycleTest | idempotencia - reintentar con la misma clave no duplica la version | PASA |
| Erso008LifecycleTest | criterio 7 - la traza permite reconstruir el estado en cada momento | PASA |
| Erso008LifecycleTest | criterio 2 - el desafio es de un solo uso con tipo y audiencia especificos | PASA |
| WildcardNamespaceTest | el backend de cartera crea el DID de un titular sin reservar su namespace uno por uno | PASA |
| WildcardNamespaceTest | el comodin cubre un solo nivel y no otorga permisos a otras cuentas | PASA |

## Módulo `credentials-core` — 12 pruebas, 0 fallos

| Clase | Prueba | Resultado |
|---|---|---|
| CredentialsCoreTest | jwk - no acepta material privado ni puntos fuera de la curva | PASA |
| CredentialsCoreTest | mdoc - un elemento alterado, una firma ajena o un algoritmo distinto se rechazan | PASA |
| CredentialsCoreTest | criterio 4 - matriz de claves por rol y rechazo de claves compartidas | PASA |
| CredentialsCoreTest | sd-jwt - una divulgacion alterada o ajena se rechaza | PASA |
| CredentialsCoreTest | sd-jwt - perfil - typ vc+sd-jwt (perfil anterior) se rechaza salvo habilitarlo | PASA |
| CredentialsCoreTest | mdoc - emision y recepcion verificada | PASA |
| CredentialsCoreTest | sd-jwt - prueba del titular (KB-JWT) - clave ajena, audiencia, nonce y repeticion | PASA |
| CredentialsCoreTest | criterio 2 - el registro de ejecucion detecta un algoritmo distinto al declarado | PASA |
| CredentialsCoreTest | sd-jwt - la prueba del titular sola no basta - verificacion independiente de la firma del emisor | PASA |
| CredentialsCoreTest | sd-jwt - la firma del emisor no se puede reemplazar por otra clave | PASA |
| CredentialsCoreTest | sd-jwt - emision, divulgacion selectiva y presentacion validas | PASA |
| CredentialsCoreTest | sd-jwt - expiracion | PASA |

## Módulo `wallet-core` — 15 pruebas, 0 fallos

| Clase | Prueba | Resultado |
|---|---|---|
| WalletCoreTest | 001-C3 la politica minima permite el nivel real y lo refleja | PASA |
| WalletCoreTest | 001-C2 el contrato del custodio no expone ningun metodo que devuelva material privado | PASA |
| WalletCoreTest | 001-C2 la clave sirve para firmar y la firma se verifica con la publica, sin salir del custodio | PASA |
| WalletCoreTest | 003-C2 el DID Document es conforme y produce un informe de conformidad | PASA |
| WalletCoreTest | 001-C2 la clave privada real no aparece en ninguna salida del custodio, ni en descriptores, ni en firmas | PASA |
| WalletCoreTest | 003 la validacion es un requisito previo - un documento no conforme no se entrega | PASA |
| WalletCoreTest | 003 el SDK firma con la clave del DID usando el custodio | PASA |
| WalletCoreTest | 003-C3 authentication y assertionMethod apuntan a la clave Multikey del titular | PASA |
| WalletCoreTest | guarda - todo metodo de prueba devuelve void para que JUnit lo ejecute | PASA |
| WalletCoreTest | 001-C3 un custodio de software declara SOFTWARE y no puede pasar por hardware | PASA |
| WalletCoreTest | 001-C2 no se puede sobrescribir una clave existente ni obtener una inexistente | PASA |
| WalletCoreTest | 003-C4 el documento se almacena sellado y asociado a la instancia de cartera | PASA |
| WalletCoreTest | 003-C1 el DID se genera en el dispositivo sin exponer la clave privada (registro de generacion) | PASA |
| WalletCoreTest | 003-C2 el informe detecta documentos no conformes | PASA |
| WalletCoreTest | 003 el identificador se deriva de la clave publica y es determinista | PASA |

## Módulo `wallet-service` — 32 pruebas, 0 fallos

| Clase | Prueba | Resultado |
|---|---|---|
| Erso001WalletTest | C1 el titular puede revocar su instancia y deja de autenticar | PASA |
| Erso001WalletTest | C3 la politica minima del backend rechaza carteras por debajo del nivel exigido | PASA |
| Erso001WalletTest | C1 una segunda cartera activa para el mismo ciudadano se rechaza, y la base lo garantiza | PASA |
| Erso001WalletTest | C3 attestation ilegitima - clave importada, raiz desconocida y desafio reutilizado | PASA |
| Erso001WalletTest | C1 la instancia queda registrada y activa asociada al ciudadano, con registro de activacion | PASA |
| Erso001WalletTest | C4 la recuperacion se completa desde el backend sin acceder a ninguna clave privada | PASA |
| Erso001WalletTest | la auditoria del backend es inalterable | PASA |
| Erso001WalletTest | el inventario de endpoints del backend solo contiene gestion de cartera y de DID, sin protocolos de credenciales | PASA |
| Erso001WalletTest | C4 el codigo de recuperacion se protege contra fuerza bruta | PASA |
| Erso001WalletTest | C3 un dispositivo que exagera su nivel es rechazado - sin evidencia, con evidencia de software o con arranque no verificado | PASA |
| Erso001WalletTest | guarda - todo metodo de prueba devuelve void para que JUnit lo ejecute | PASA |
| Erso001WalletTest | C4 el token de recuperacion es de un solo uso y solo sirve para su ciudadano | PASA |
| Erso001WalletTest | C3 declarar menos de lo que se tiene tampoco coincide | PASA |
| Erso001WalletTest | C1 desafio de un solo uso, prueba de posesion obligatoria y autenticacion de instancia | PASA |
| Erso001WalletTest | C3 un dispositivo TEE con attestation valida se activa con el nivel verificado y ficha tecnica | PASA |
| Erso003HolderDidTest | flujo completo - el DID nace en el dispositivo, el backend lo publica en el VDR y queda resoluble | PASA |
| Erso003HolderDidTest | publicar dos veces el mismo DID se rechaza y repetir la prueba es idempotente | PASA |
| Erso003HolderDidTest | el backend rechaza DID fuera del namespace de titulares, no derivados de la clave y no conformes | PASA |
| Erso003HolderDidTest | una cartera SOFTWARE tambien publica su DID (la clave declara SOFTWARE, sin fingir hardware) | PASA |
| Erso003HolderDidTest | guarda - todo metodo de prueba devuelve void para que JUnit lo ejecute | PASA |
| Erso003HolderDidTest | una cartera de hardware exige que la clave del DID demuestre el mismo nivel | PASA |
| Erso003HolderDidTest | el DID se deriva de la clave - mismo formato que el documento y sin datos civiles | PASA |
| Erso003HolderDidTest | una instancia no puede completar la publicacion de otra ni usar un token revocado | PASA |
| Erso003HolderDidTest | firmar el desafio con otra clave hace fallar la publicacion y no deja rastro publico | PASA |
| Erso003HolderDidTest | el respaldo cifrado tiene limites y solo lo lee su dueno | PASA |
| KeyAttestationTest | una cadena firmada por una raiz desconocida se rechaza | PASA |
| KeyAttestationTest | una cadena TEE legitima se verifica y entrega el nivel, el origen y la clave | PASA |
| KeyAttestationTest | guarda - todo metodo de prueba devuelve void para que JUnit lo ejecute | PASA |
| KeyAttestationTest | StrongBox y Software se distinguen | PASA |
| KeyAttestationTest | una clave importada queda visible en el origen | PASA |
| KeyAttestationTest | un desafio distinto se rechaza (evita reutilizar una attestation vieja) | PASA |
| KeyAttestationTest | entradas invalidas se rechazan sin excepciones inesperadas | PASA |

## Módulo `credential-service` — 16 pruebas, 0 fallos

| Clase | Prueba | Resultado |
|---|---|---|
| Erso002CredentialsTest | C1 metadatos del emisor OpenID4VCI 1_0 anuncian los dos formatos y el perfil | PASA |
| Erso002CredentialsTest | C1 el verificador rechaza repeticion, nonce o audiencia ajenos, claims faltantes y formatos no soportados | PASA |
| Erso002CredentialsTest | C1 dc+sd-jwt - emision con oferta y tx_code, recepcion verificada y presentacion con divulgacion selectiva | PASA |
| Erso002CredentialsTest | C1 la oferta vence | PASA |
| Erso002CredentialsTest | C3 inventario de endpoints - solo OpenID4VCI, OpenID4VP, administracion y operacion | PASA |
| Erso002CredentialsTest | C1 una credencial alterada o de otro emisor se rechaza al presentarla | PASA |
| Erso002CredentialsTest | C2 el registro de ejecucion coincide con el perfil declarado y con los metadatos anunciados | PASA |
| Erso002CredentialsTest | C1 una oferta puede pedir los dos formatos y se emiten ambos | PASA |
| Erso002CredentialsTest | guarda - todo metodo de prueba devuelve void para que JUnit lo ejecute | PASA |
| Erso002CredentialsTest | C1 el portal de administracion exige su token | PASA |
| Erso002CredentialsTest | C1 mso_mdoc - el titular recibe, verifica y almacena la credencial | PASA |
| Erso002CredentialsTest | C4 matriz de claves por rol construida con las claves reales de la ejecucion | PASA |
| Erso002CredentialsTest | C4 verificaciones independientes - la del emisor no usa la clave del titular y la del titular no usa la del emisor | PASA |
| Erso002CredentialsTest | C1 el endpoint de credencial exige token y respeta lo autorizado por la oferta | PASA |
| Erso002CredentialsTest | C1 la prueba de posesion es obligatoria y de un solo uso - nonce, audiencia, firma y frescura | PASA |
| Erso002CredentialsTest | C1 el codigo pre-autorizado es de un solo uso, el tx_code protege y 3 fallos queman la oferta | PASA |
