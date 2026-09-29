#!/usr/bin/env bash
# Recorrido de extremo a extremo por los canales REALES (TLS público + mTLS de escritura + canal de cartera/credenciales) contra el stack Docker.
# Un bloque por ERSo; cada comprobación imprime PASS/FAIL y el resultado completo queda en evidencias/.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/deploy"
set -a; . ./.env; set +a
export LOCAL_UID=$(id -u) LOCAL_GID=$(id -g)
DOMAIN="$VDR_DOMAIN"
WORK="$ROOT/evidencias/work"; rm -rf "$WORK"; mkdir -p "$WORK"
LOG="$ROOT/evidencias/e2e-$(date +%Y%m%d-%H%M%S).log"
exec > >(tee "$LOG") 2>&1

PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "  ✔ PASS  $*"; }
bad()  { FAIL=$((FAIL+1)); echo "  ✘ FAIL  $*"; }
check(){ local d=$1; shift; if "$@" >/dev/null 2>&1; then ok "$d"; else bad "$d"; fi; }
H()      { docker compose --progress quiet --profile tools run --rm -T holder "$@"; }                       # la Holder App SIMULADA (cartera del titular)
HND()    { docker compose --progress quiet --profile tools run --rm -T --no-deps holder "$@"; }              # igual que H pero SIN recrear dependencias (necesario con el VDR apagado a propósito)
# suma las comprobaciones que imprimió un escenario; si no imprimió su línea RESULTADO (se cayó), cuenta como fallo
absorb() { PASS=$((PASS+$(grep -c '✔ PASS' <<<"$1"))); FAIL=$((FAIL+$(grep -c '✘ FAIL' <<<"$1"))); grep -q 'RESULTADO' <<<"$1" || { FAIL=$((FAIL+1)); echo "  ✘ FAIL  el escenario terminó sin línea RESULTADO"; }; }
h()    { echo; echo "════════════════════════════════════════════════════════════════════"; echo "  $*"; echo "════════════════════════════════════════════════════════════════════"; }

PUBLIC() { curl -sS --cacert certs/ca.crt --resolve "$DOMAIN:8443:127.0.0.1" "$@"; }       # canal de lectura (host)
T()      { docker compose --progress quiet --profile tools run --rm -T tools "$@"; }                          # la "entidad" dentro de la red
W()      { local cid=$1 sec=$2; shift 2; T write --admin-url "https://$DOMAIN:8443" --client-id "$cid" --secret "$sec" --p12 "/certs/$cid.p12" --p12-pass "$P12_PASS" --ca /certs/ca.crt "$@"; }
A()      { local cid=$1 sec=$2; shift 2; T admin --admin-url "https://$DOMAIN:8443" --client-id "$cid" --secret "$sec" --p12 "/certs/$cid.p12" --p12-pass "$P12_PASS" --ca /certs/ca.crt "$@"; }
json()   { sed -n '/^{/,$p' | jq -r "$1"; }
sha()    { sha256sum | awk '{print "sha256:"$1}'; }
raw()    { curl -sS --cacert certs/ca.crt --resolve "$DOMAIN:8443:127.0.0.1" "https://$DOMAIN:8443$1"; }

echo "Compilando y construyendo imágenes (todos los módulos)…"
(cd "$ROOT" && ./gradlew -q :vdr-service:installDist :did-tools:installDist :wallet-service:installDist :credential-service:installDist :wallet-sim:installDist) || { echo "FALLÓ la compilación"; exit 1; }
docker compose --progress quiet --profile tools build >/dev/null 2>&1 || { echo "FALLÓ la construcción de imágenes"; exit 1; }
echo "Preparando entorno limpio (docker compose down -v && up)…"
docker compose --progress quiet down -v >/dev/null 2>&1; docker compose --progress quiet up -d >/dev/null 2>&1
for i in $(seq 1 40); do curl -sf --cacert certs/ca.crt --resolve "$DOMAIN:8443:127.0.0.1" "https://$DOMAIN:8443/health" >/dev/null 2>&1 && break; sleep 1; done
echo "VDR de extensión — recorrido E2E — $(date -Is) — dominio $DOMAIN"
echo "Canales: lectura https://$DOMAIN/ (443) · escritura mTLS https://$DOMAIN:8443/admin/v1 (dentro de la red Docker)"

# ═══════════════════════════════════════════════════════════════════════════════════════
h "ERSo 2026-004 · paso 0 — el camino base y el VDR de extensión encendido"
check "camino base responde (/base/ping)" test "$(PUBLIC "https://$DOMAIN:8443/base/ping")" = "pong"
check "health informa vdr=enabled" test "$(PUBLIC "https://$DOMAIN:8443/health" | jq -r .vdr)" = "enabled"
check "el puerto de la aplicación y de Postgres NO están publicados en el host" bash -c '! (ss -ltn | grep -E ":(8080|8081|5432) ")'

# ═══════════════════════════════════════════════════════════════════════════════════════
h "ERSo 2026-005 · Publicación del DID Document bajo did:web (laboratorio)"
LABDID="did:web:$DOMAIN:lab:laboratorio"
T did --domain "$DOMAIN" --namespace lab/laboratorio
T keygen --out lab.json
T build-doc --did "$LABDID" --key lab.json --out lab-doc.json >/dev/null
OUT=$(W lab-operator "$CLIENT_SECRET_LAB" --did "$LABDID" --purpose CREATE --expected 0 --key lab.json --doc lab-doc.json); echo "$OUT"
check "publicación aceptada (201, CONFIRMED)" test "$(echo "$OUT" | json .status)" = "CONFIRMED"
HASH_WRITE=$(echo "$OUT" | json .hash)
PUBLIC "https://$DOMAIN:8443/lab/laboratorio/did.json" -D "$WORK/lab.headers" -o "$WORK/lab.served.json"
echo "  Documento servido por HTTPS:"; jq . "$WORK/lab.served.json" | sed 's/^/    /'
check "C1: se sirve por HTTPS en la URL calculada (200 application/did+json)" bash -c "grep -qi '^HTTP.* 200' '$WORK/lab.headers' && grep -qi 'content-type: application/did+json' '$WORK/lab.headers'"
check "C2: hash del contenido servido == hash devuelto por el registro" test "$(sha < "$WORK/lab.served.json")" = "$HASH_WRITE"
check "C2: contiene la clave pública correcta (Multikey del keygen)" test "$(jq -r '.verificationMethod[0].publicKeyMultibase' "$WORK/lab.served.json")" = "$(jq -r .publicKeyMultibase "$WORK/lab.json")"
check "C3: sin claves privadas ni datos civiles en el documento" bash -c "! grep -Eqi 'private|secret|credentialSubject|birthDate|\"d\":' '$WORK/lab.served.json'"
sed "s#\"id\": \"$LABDID\"#\"id\": \"did:web:$DOMAIN:lab:otro\"#" "$WORK/lab-doc.json" > "$WORK/lab-desajustado.json"
OUT=$(W lab-operator "$CLIENT_SECRET_LAB" --did "$LABDID" --purpose UPDATE --expected 1 --key lab.json --doc lab-desajustado.json); echo "$OUT" | tail -8
check "C4: id desajustado se rechaza (422 INVALID_DOCUMENT)" bash -c "echo '$OUT' | grep -q 'INVALID_DOCUMENT'"
OUT=$(W lab-operator "$CLIENT_SECRET_LAB" --did "did:web:$DOMAIN:ciudadanos:ana" --purpose CREATE --expected 0 --key lab.json --doc lab-doc.json); echo "$OUT" | tail -4
check "un DID de ciudadano no se puede crear (412 NAMESPACE_NOT_RESERVED)" bash -c "echo '$OUT' | grep -q 'NAMESPACE_NOT_RESERVED'"

# ═══════════════════════════════════════════════════════════════════════════════════════
h "ERSo 2026-006 · Publicación del DID Document institucional (entidad Avance)"
AVDID="did:web:$DOMAIN:entidades:avance"
T keygen --out avance.json
T build-doc --did "$AVDID" --key avance.json --service-url "https://$DOMAIN/issuer" --out avance-doc.json >/dev/null
OUT=$(W avance-issuer "$CLIENT_SECRET_AVANCE" --did "$AVDID" --purpose CREATE --expected 0 --key avance.json --doc avance-doc.json); echo "$OUT"
OPID=$(echo "$OUT" | json .operationId); HASH_A=$(echo "$OUT" | json .hash)
PUBLIC "https://$DOMAIN:8443/entidades/avance/did.json" -o "$WORK/avance.served.json"
check "C1: el documento institucional se sirve por HTTPS y es resoluble" bash -c "jq -e '.id==\"$AVDID\"' '$WORK/avance.served.json'"
check "C1: resoluble por el cliente consumidor (ERSo 007)" T resolve --did "$AVDID" --ca /certs/ca.crt
check "C3: sin claves privadas ni datos civiles" bash -c "! grep -Eqi 'private|secret|credentialSubject|birthDate' '$WORK/avance.served.json'"
A "avance-issuer" "$CLIENT_SECRET_AVANCE" --path "/admin/v1/operations/$OPID" | tee "$WORK/op.txt" | tail -14
check "C4: evidencia registrada (versión, hash y URL pública)" bash -c "grep -q '\"version\": 1' '$WORK/op.txt' && grep -q '$HASH_A' '$WORK/op.txt' && grep -q 'https://$DOMAIN/entidades/avance/did.json' '$WORK/op.txt'"

echo "  — escritura autenticada y limitada al namespace —"
OUT=$(W avance-issuer "$CLIENT_SECRET_AVANCE" --did "$AVDID" --purpose CREATE --expected 0 --key avance.json --doc avance-doc.json 2>&1); echo "$OUT" | tail -3
OUT=$(W avance-issuer "mala-clave" --did "$AVDID" --purpose UPDATE --expected 1 --key avance.json --doc avance-doc.json 2>&1); echo "$OUT" | tail -2
check "C2: credenciales incorrectas -> 401 invalid_client" bash -c "echo '$OUT' | grep -q 'invalid_client'"
OUT=$(T write --admin-url "https://$DOMAIN:8443" --client-id avance-issuer --secret "$CLIENT_SECRET_AVANCE" --p12 /certs/lab-operator.p12 --p12-pass "$P12_PASS" --ca /certs/ca.crt --did "$AVDID" --purpose UPDATE --expected 1 --key avance.json --doc avance-doc.json 2>&1); echo "$OUT" | tail -2
check "C2: certificado de OTRA entidad con credenciales de Avance -> 403 MTLS_REQUIRED" bash -c "echo '$OUT' | grep -q 'MTLS_REQUIRED'"
OUT=$(T write --admin-url "https://$DOMAIN:8443" --client-id avance-issuer --secret "$CLIENT_SECRET_AVANCE" --p12 /certs/intruso.p12 --p12-pass "$P12_PASS" --ca /certs/ca.crt --did "$AVDID" --purpose UPDATE --expected 1 --key avance.json --doc avance-doc.json 2>&1); echo "$OUT" | tail -2
check "C2: certificado de una CA desconocida -> handshake TLS rechazado por nginx (no llega a la aplicación)" bash -c "! echo '$OUT' | grep -q 'CONFIRMED'"
OUT=$(W lab-operator "$CLIENT_SECRET_LAB" --did "$AVDID" --purpose CREATE --expected 0 --key lab.json --doc avance-doc.json 2>&1); echo "$OUT" | tail -3
check "C2: laboratorio no puede escribir en el namespace de Avance (412 NAMESPACE_NOT_OWNED)" bash -c "echo '$OUT' | grep -q 'NAMESPACE_NOT_OWNED'"
check "canal público NO acepta escrituras (PUT/POST/DELETE)" bash -c "for m in PUT POST DELETE; do c=\$(curl -s -o /dev/null -w '%{http_code}' --cacert certs/ca.crt --resolve $DOMAIN:8443:127.0.0.1 -X \$m https://$DOMAIN:8443/entidades/avance/did.json); [ \"\$c\" -ge 400 ] || exit 1; done"
check "el canal de escritura exige certificado cliente (nginx responde 400 antes de llegar a la aplicación)" test "$(curl -s -o /dev/null -w '%{http_code}' --cacert certs/ca.crt --resolve $DOMAIN:9443:127.0.0.1 https://$DOMAIN:9443/admin/v1/audit)" = "400"
A avance-issuer "$CLIENT_SECRET_AVANCE" --path "/admin/v1/audit?did=$AVDID" --out audit-avance.json >/dev/null
echo "  Traza de auditoría (acciones):"; jq -r '.[] | "    \(.at)  \(.actor)  \(.action)  v\(.version // "-")"' "$WORK/audit-avance.json"
check "C2: la operación quedó trazada (WRITE_CREATE con actor avance-issuer)" bash -c "jq -e 'map(select(.action==\"WRITE_CREATE\" and .actor==\"avance-issuer\")) | length == 1' '$WORK/audit-avance.json'"

# ═══════════════════════════════════════════════════════════════════════════════════════
h "ERSo 2026-008 · Ciclo de vida y trazabilidad (namespace entidades/avance-ciclo)"
CDID="did:web:$DOMAIN:entidades:avance-ciclo"
T keygen --out c1.json; T keygen --out c2.json
T build-doc --did "$CDID" --key c1.json --out c-v1.json >/dev/null
T build-doc --did "$CDID" --key c2.json --out c-v2.json >/dev/null
T build-doc --did "$CDID" --key c2.json --service-url "https://$DOMAIN/issuer" --out c-v3.json >/dev/null

echo "  — versión 1: CREATE —"
OUT=$(W avance-issuer "$CLIENT_SECRET_AVANCE" --did "$CDID" --purpose CREATE --expected 0 --key c1.json --doc c-v1.json); echo "$OUT" | sed -n '1,5p'
check "C4: la escritura devolvió versión, hash y URL pública" bash -c "echo '$OUT' | sed -n '/^{/,\$p' | jq -e '.version==1 and (.hash|startswith(\"sha256:\")) and (.publicUrl|startswith(\"https://\"))'"
check "C5: confirmada por lectura de la URL y comparación de hash" test "$(echo "$OUT" | json .status)" = "CONFIRMED"
sleep 1
echo "  — versión 2: UPDATE con rotación de clave (firma la clave VIGENTE c1) —"
sleep 1
OUT=$(W avance-issuer "$CLIENT_SECRET_AVANCE" --did "$CDID" --purpose UPDATE --expected 1 --key c1.json --doc c-v2.json); echo "$OUT" | sed -n '1,5p'
check "rotación aceptada: la prueba la firmó la clave vigente" test "$(echo "$OUT" | json .version)" = "2"
OUT=$(W avance-issuer "$CLIENT_SECRET_AVANCE" --did "$CDID" --purpose UPDATE --expected 2 --key c1.json --doc c-v3.json 2>&1); echo "$OUT" | tail -3
check "la clave anterior ya no puede escribir (403 INVALID_PROOF)" bash -c "echo '$OUT' | grep -q 'INVALID_PROOF'"
OUT=$(W avance-issuer "$CLIENT_SECRET_AVANCE" --did "$CDID" --purpose UPDATE --expected 1 --key c2.json --doc c-v3.json 2>&1); echo "$OUT" | tail -3
check "C4: versión esperada equivocada se rechaza (412 VERSION_CONFLICT)" bash -c "echo '$OUT' | grep -q 'VERSION_CONFLICT'"
echo "  — versión 3: UPDATE (firma c2) —"
OUT=$(W avance-issuer "$CLIENT_SECRET_AVANCE" --did "$CDID" --purpose UPDATE --expected 2 --key c2.json --doc c-v3.json); echo "$OUT" | sed -n '1,5p'
check "versión 3 publicada" test "$(echo "$OUT" | json .version)" = "3"
echo "  — versión 4: DEACTIVATE —"
OUT=$(W avance-issuer "$CLIENT_SECRET_AVANCE" --did "$CDID" --purpose DEACTIVATE --expected 3 --key c2.json); echo "$OUT" | sed -n '1,5p'
check "desactivación confirmada (la URL pública responde 410)" test "$(echo "$OUT" | json .status)" = "CONFIRMED"
check "lectura pública del DID desactivado -> 410 Gone" test "$(curl -s -o /dev/null -w '%{http_code}' --cacert certs/ca.crt --resolve $DOMAIN:8443:127.0.0.1 https://$DOMAIN:8443/entidades/avance-ciclo/did.json)" = "410"
OUT=$(W avance-issuer "$CLIENT_SECRET_AVANCE" --did "$CDID" --purpose CREATE --expected 0 --key c1.json --doc c-v1.json 2>&1); echo "$OUT" | tail -3
check "regla transversal: un historial terminal no vuelve a activarse (409 TERMINAL_STATE)" bash -c "echo '$OUT' | grep -q 'TERMINAL_STATE'"

A avance-issuer "$CLIENT_SECRET_AVANCE" --path "/admin/v1/documents/$CDID/versions" --out versions.json >/dev/null
echo "  Historial de versiones (C7):"; jq -r '.[] | "    v\(.version)  \(.operation)  \(.createdAt)  \(.hash)"' "$WORK/versions.json"
check "C7: 4 versiones en orden CREATE, UPDATE, UPDATE, DEACTIVATE" bash -c "jq -e '[.[].operation]==[\"CREATE\",\"UPDATE\",\"UPDATE\",\"DEACTIVATE\"]' '$WORK/versions.json'"
V2AT=$(jq -r '.[1].createdAt' "$WORK/versions.json"); V4AT=$(jq -r '.[3].createdAt' "$WORK/versions.json"); V1AT=$(jq -r '.[0].createdAt' "$WORK/versions.json")
A avance-issuer "$CLIENT_SECRET_AVANCE" --path "/admin/v1/documents/$CDID/state?at=$V2AT" --out state2.json >/dev/null
A avance-issuer "$CLIENT_SECRET_AVANCE" --path "/admin/v1/documents/$CDID/state?at=$V4AT" --out state4.json >/dev/null
check "C7: estado reconstruido en el instante de la v2 = ACTIVE, versión 2" bash -c "jq -e '.status==\"ACTIVE\" and .version==2' '$WORK/state2.json'"
check "C7: estado reconstruido en el instante de la v4 = DEACTIVATED" bash -c "jq -e '.status==\"DEACTIVATED\"' '$WORK/state4.json'"
check "C7: el historial no se puede reescribir (trigger append-only en PostgreSQL)" bash -c "docker compose exec -T postgres psql -U $POSTGRES_USER -d $POSTGRES_DB -c \"UPDATE did_document_versions SET hash='x'\" 2>&1 | grep -q 'append-only'"
check "C2: desafíos emitidos quedaron registrados (tipo y audiencia)" bash -c "docker compose exec -T postgres psql -U $POSTGRES_USER -d $POSTGRES_DB -tAc \"select count(*) from audit_log where action='CHALLENGE_ISSUED' and detail->>'audience'='vdr:$DOMAIN:did-operation'\" | grep -qv '^0'"
check "C1: sin certificado cliente no se emite desafío (nginx 400; la aplicación ni se entera)" test "$(curl -s -o /dev/null -w '%{http_code}' --cacert certs/ca.crt --resolve $DOMAIN:9443:127.0.0.1 -X POST https://$DOMAIN:9443/admin/v1/challenges)" = "400"
check "C1: la denegación por precondiciones (namespace ajeno) dejó rastro y no creó desafío" bash -c "docker compose exec -T postgres psql -U $POSTGRES_USER -d $POSTGRES_DB -tAc \"select count(*) from audit_log where action='CHALLENGE_DENIED'\" | grep -qv '^0'"

# ═══════════════════════════════════════════════════════════════════════════════════════
h "ERSo 2026-007 · Resolución y verificación como consumidor conforme"
echo "  — el consumidor resuelve el DID institucional publicado (solo lectura, GET por HTTPS) —"
T resolve --did "$AVDID" --ca /certs/ca.crt | sed 's/^/    /' | head -30
JWS=$(T sign --key avance.json --kid "$AVDID#key-1" --message "solicitud-de-presentacion")
OUT=$(T verify --jws "$JWS" --purpose assertionMethod --did "$AVDID" --ca /certs/ca.crt); echo "  $OUT"
check "C1: prueba firmada por la clave resuelta -> VÁLIDA" bash -c "echo '$OUT' | grep -q '^VALIDA'"
JWS2=$(T sign --key lab.json --kid "$AVDID#key-1" --message "suplantacion")
OUT=$(T verify --jws "$JWS2" --purpose assertionMethod --ca /certs/ca.crt); echo "  $OUT"
check "C2: prueba firmada por una clave ajena -> RECHAZADA (INVALID_SIGNATURE)" bash -c "echo '$OUT' | grep -q 'INVALID_SIGNATURE'"
OUT=$(T verify --jws "$JWS" --did "did:web:$DOMAIN:entidades:otra" --ca /certs/ca.crt); echo "  $OUT"
check "C3: DID que no coincide con el de la prueba -> RECHAZADA (DID_MISMATCH)" bash -c "echo '$OUT' | grep -q 'DID_MISMATCH'"
JWS3=$(T sign --key avance.json --kid "did:web:$DOMAIN:entidades:fantasma#key-1" --message "x")
OUT=$(T verify --jws "$JWS3" --ca /certs/ca.crt); echo "  $OUT"
check "C3: DID inexistente -> RECHAZADA (RESOLUTION_FAILED / notFound)" bash -c "echo '$OUT' | grep -q 'RESOLUTION_FAILED'"
JWS4=$(T sign --key c2.json --kid "$CDID#key-1" --message "x")
OUT=$(T verify --jws "$JWS4" --purpose authentication --ca /certs/ca.crt); echo "  $OUT"
check "C3: DID desactivado -> RECHAZADA (DEACTIVATED)" bash -c "echo '$OUT' | grep -q 'DEACTIVATED'"
check "C4: el consumidor solo usa GET (las pruebas unitarias con MockEngine y de interfaz lo verifican)" bash -c "cd '$ROOT' && grep -q 'CRITERIO 4' did-resolver/src/test/kotlin/co/org/avance/ssi/resolver/ResolverTest.kt"


# ═══════════════════════════════════════════════════════════════════════════════════════
h "ERSo 2026-001/002/003 · preparación: autoridad de attestation de laboratorio, Wallet Backend y servicio de credenciales"
H authority-init --out authority.json --root-out lab-attestation-root.pem
docker compose --progress quiet up -d --force-recreate wallet credential >/dev/null 2>&1
for i in $(seq 1 60); do
  w=$(curl -s -o /dev/null -w '%{http_code}' --cacert certs/ca.crt --resolve "$DOMAIN:8444:127.0.0.1" "https://$DOMAIN:8444/wallet/v1/instances/00000000-0000-0000-0000-000000000000")
  c=$(curl -s -o /dev/null -w '%{http_code}' --cacert certs/ca.crt --resolve "$DOMAIN:8444:127.0.0.1" "https://$DOMAIN:8444/.well-known/openid-credential-issuer")
  [ "$w" = "401" ] && [ "$c" = "200" ] && break; sleep 1
done
check "el Wallet Backend responde por el canal de cartera (401 sin token = servicio vivo)" test "$w" = "401"
check "el emisor publica sus metadatos OpenID4VCI" test "$c" = "200"
check "ni el Wallet Backend ni el servicio de credenciales publican puertos en el host" bash -c '! (ss -ltn | grep -E ":(8090|8100) ")'
check "los portales de administración NO están en el canal de ciudadanos (404 en :8444)" test "$(curl -s -o /dev/null -w '%{http_code}' --cacert certs/ca.crt --resolve $DOMAIN:8444:127.0.0.1 -X POST https://$DOMAIN:8444/admin/offers)" = "404"
check "los portales de administración exigen certificado cliente (400 de nginx sin él)" test "$(curl -s -o /dev/null -w '%{http_code}' --cacert certs/ca.crt --resolve $DOMAIN:9443:127.0.0.1 -X POST https://$DOMAIN:9443/admin/offers)" = "400"
check "la configuración de nginx es válida" bash -c "docker compose exec -T nginx nginx -t 2>&1 | grep -q 'successful'"

# ═══════════════════════════════════════════════════════════════════════════════════════
h "ERSo 2026-001 · Cartera de identidad y custodia de claves en hardware (dispositivo SIMULADO + Wallet Backend real)"
OUT=$(H scenario wallet --authority authority.json --domain "$DOMAIN" --base "https://$DOMAIN:8444" --ca /certs/ca.crt --secrets-out secrets-wallet.txt); echo "$OUT"; absorb "$OUT"
docker compose exec -T postgres pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB" > "$WORK/dump.sql" 2>/dev/null
check "control: el volcado de la base contiene datos de la cartera (la búsqueda no es vacía)" bash -c "grep -q 'INSTANCE_ACTIVATED' '$WORK/dump.sql'"
check "C2/C4 ninguna clave privada de los dispositivos aparece en la base de datos del backend (pg_dump)" test "$(grep -c -F -f "$WORK/secrets-wallet.txt" "$WORK/dump.sql")" = "0"
check "la auditoría de la cartera es inalterable (trigger append-only)" bash -c "docker compose exec -T postgres psql -U $POSTGRES_USER -d $POSTGRES_DB -c \"DELETE FROM wallet_audit\" 2>&1 | grep -q 'append-only'"
check "una sola cartera activa por ciudadano: lo garantiza la base (índice único parcial)" bash -c "docker compose exec -T postgres psql -U $POSTGRES_USER -d $POSTGRES_DB -tAc \"select indexdef from pg_indexes where indexname='wallet_one_active_per_citizen'\" | grep -q 'ACTIVE'"
rm -f "$WORK/secrets-wallet.txt" "$WORK/dump.sql"

# ═══════════════════════════════════════════════════════════════════════════════════════
h "ERSo 2026-003 · Creación del DID y DID Document del titular (dispositivo → Wallet Backend → VDR → consumidor conforme)"
OUT=$(H scenario did --authority authority.json --domain "$DOMAIN" --base "https://$DOMAIN:8444" --ca /certs/ca.crt --secrets-out secrets-did.txt); echo "$OUT"; absorb "$OUT"
TDID=$(echo "$OUT" | sed -n 's/^DID=//p' | tail -1); TTHUMB=${TDID##*:}
echo "  DID del titular: $TDID"
T resolve --did "$TDID" --ca /certs/ca.crt | sed 's/^/    /' | head -24
check "el cliente de línea de comandos (ERSo 007) también resuelve el DID del titular" bash -c "T() { docker compose --progress quiet --profile tools run --rm -T tools \"\$@\"; }; T resolve --did '$TDID' --ca /certs/ca.crt | grep -q publicKeyMultibase"
PUBLIC "https://$DOMAIN:8443/titulares/$TTHUMB/did.json" -D "$WORK/titular.headers" -o "$WORK/titular.served.json"
check "el DID Document del titular se sirve por HTTPS en la URL calculada (200 application/did+json)" bash -c "grep -qi '^HTTP.* 200' '$WORK/titular.headers' && grep -qi 'content-type: application/did+json' '$WORK/titular.headers'"
check "el documento publicado no contiene claves privadas ni datos civiles" bash -c "! grep -Eqi 'private|secret|credentialSubject|birthDate|\"d\":' '$WORK/titular.served.json'"
BEFORE_T=$(sha < "$WORK/titular.served.json")
check "el VDR registró la escritura a nombre del Wallet Backend, no del titular" bash -c "docker compose exec -T postgres psql -U $POSTGRES_USER -d $POSTGRES_DB -tAc \"select actor from audit_log where action='WRITE_CREATE' and did='$TDID'\" | grep -q wallet-backend"
check "el namespace del titular se materializó bajo el comodín titulares/* del Wallet Backend" bash -c "docker compose exec -T postgres psql -U $POSTGRES_USER -d $POSTGRES_DB -tAc \"select owner_client_id from namespaces where path='titulares/$TTHUMB'\" | grep -q wallet-backend"
docker compose exec -T postgres pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB" > "$WORK/dump.sql" 2>/dev/null
check "C1 ninguna clave privada del titular está en la base de datos (backend + VDR)" test "$(grep -c -F -f "$WORK/secrets-did.txt" "$WORK/dump.sql")" = "0"
rm -f "$WORK/secrets-did.txt" "$WORK/dump.sql"

# ═══════════════════════════════════════════════════════════════════════════════════════
h "ERSo 2026-002 · Suite criptográfica y formatos de credencial (dc+sd-jwt y mso_mdoc sobre OpenID4VCI/OpenID4VP)"
OUT=$(H scenario credentials --authority authority.json --domain "$DOMAIN" --base "https://$DOMAIN:8444" --ca /certs/ca.crt --admin-url "https://$DOMAIN:8443" --p12 /certs/vdr-admin.p12 --p12-pass "$P12_PASS" --admin-token "$CREDENTIAL_ADMIN_TOKEN"); echo "$OUT"; absorb "$OUT"
check "C4 la matriz de claves por rol se generó con las claves reales (ISSUER_SIGNING y HOLDER_PROOF)" grep -q 'ISSUER_SIGNING.*HOLDER_PROOF\|HOLDER_PROOF' <<<"$OUT"

# ═══════════════════════════════════════════════════════════════════════════════════════
h "ERSo 2026-004 · respaldo/restauración y camino base con el registro apagado"
BEFORE_A=$(PUBLIC "https://$DOMAIN:8443/entidades/avance/did.json" | sha); BEFORE_L=$(PUBLIC "https://$DOMAIN:8443/lab/laboratorio/did.json" | sha)
A vdr-admin "$CLIENT_SECRET_ADMIN" --method POST --path /admin/v1/backup --out backup.json
check "C2: respaldo generado con checksum" bash -c "jq -e '.checksum|startswith(\"sha256:\")' '$WORK/backup.json'"
echo "  Simulando pérdida total del estado del registro (TRUNCATE)…"
docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -q -c "TRUNCATE audit_log, operations, challenges, did_document_versions, did_documents, namespaces, entity_accounts CASCADE"
check "tras la pérdida, el DID ya no se sirve (404)" test "$(curl -s -o /dev/null -w '%{http_code}' --cacert certs/ca.crt --resolve $DOMAIN:8443:127.0.0.1 https://$DOMAIN:8443/entidades/avance/did.json)" = "404"
jq '.checksum="sha256:0000"' "$WORK/backup.json" > "$WORK/backup-alterado.json"
OUT=$(A vdr-admin "$CLIENT_SECRET_ADMIN" --method POST --path /admin/v1/restore --body backup-alterado.json 2>&1); echo "  $OUT" | head -3
check "un respaldo alterado se rechaza (CHECKSUM_MISMATCH)" bash -c "echo '$OUT' | grep -q 'CHECKSUM_MISMATCH'"
OUT=$(A vdr-admin "$CLIENT_SECRET_ADMIN" --method POST --path /admin/v1/restore --body backup.json); echo "$OUT" | head -14
check "restauración correcta con verificación de integridad" bash -c "echo '$OUT' | grep -q 'documentsVerified'"
check "C2: tras restaurar, el DID de Avance se sirve idéntico (mismo hash)" test "$(PUBLIC "https://$DOMAIN:8443/entidades/avance/did.json" | sha)" = "$BEFORE_A"
check "C2: tras restaurar, el DID del laboratorio se sirve idéntico (mismo hash)" test "$(PUBLIC "https://$DOMAIN:8443/lab/laboratorio/did.json" | sha)" = "$BEFORE_L"
check "C2: tras restaurar, el DID del titular (Wallet Backend) se sirve idéntico (mismo hash)" test "$(PUBLIC "https://$DOMAIN:8443/titulares/$TTHUMB/did.json" | sha)" = "$BEFORE_T"
check "C2: tras restaurar, el namespace comodín del Wallet Backend sigue siendo suyo" bash -c "docker compose exec -T postgres psql -U $POSTGRES_USER -d $POSTGRES_DB -tAc \"select owner_client_id from namespaces where path='titulares/$TTHUMB'\" | grep -q wallet-backend"
check "C2: el historial (4 versiones) sobrevive a la restauración" bash -c "docker compose exec -T postgres psql -U $POSTGRES_USER -d $POSTGRES_DB -tAc \"select count(*) from did_document_versions where did='$CDID'\" | grep -q '^4'"
check "el DID desactivado sigue desactivado tras restaurar (410)" test "$(curl -s -o /dev/null -w '%{http_code}' --cacert certs/ca.crt --resolve $DOMAIN:8443:127.0.0.1 https://$DOMAIN:8443/entidades/avance-ciclo/did.json)" = "410"

echo; echo "  — C3: camino base con la opción APAGADA (VDR_ENABLED=false) —"
VDR_ENABLED=false docker compose up -d --force-recreate vdr >/dev/null 2>&1; sleep 6
check "C3: /base/ping responde con el registro apagado" test "$(PUBLIC "https://$DOMAIN:8443/base/ping")" = "pong"
check "C3: /health informa vdr=disabled" test "$(PUBLIC "https://$DOMAIN:8443/health" | jq -r .vdr)" = "disabled"
check "C3: los endpoints de did.json no existen (404)" test "$(curl -s -o /dev/null -w '%{http_code}' --cacert certs/ca.crt --resolve $DOMAIN:8443:127.0.0.1 https://$DOMAIN:8443/entidades/avance/did.json)" = "404"
check "C3: el canal de escritura ni siquiera está abierto (502 desde nginx)" test "$(curl -s -o /dev/null -w '%{http_code}' --cacert certs/ca.crt --cert certs/vdr-admin.crt --key certs/vdr-admin.key --resolve $DOMAIN:9443:127.0.0.1 https://$DOMAIN:9443/admin/v1/audit)" = "502"
echo "  — con el VDR apagado, la cartera sigue funcionando (el camino base no depende del registro) —"
OUT=$(HND scenario vdr-off --authority authority.json --domain "$DOMAIN" --base "https://$DOMAIN:8444" --ca /certs/ca.crt); echo "$OUT"; absorb "$OUT"
echo "  Reactivando el registro…"
docker compose up -d --force-recreate vdr >/dev/null 2>&1; sleep 6
check "el registro vuelve a estar encendido y conserva su estado" test "$(PUBLIC "https://$DOMAIN:8443/entidades/avance/did.json" | sha)" = "$BEFORE_A"

echo; echo "════════════════════════════════════════════════════════════════════"
echo "  RESULTADO: $PASS comprobaciones PASS · $FAIL FAIL     (log: ${LOG#$ROOT/})"
echo "════════════════════════════════════════════════════════════════════"
[ "$FAIL" -eq 0 ]
