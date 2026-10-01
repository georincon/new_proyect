#!/usr/bin/env bash
# Laboratorio ERSo 2026-004, criterio 2: respaldo, pérdida total y restauración verificada.
# Requisitos: proyecto levantado y con datos (docs/MARCO-CONCEPTUAL.md, sección 2b, pasos A–E).
# DESTRUCTIVO PERO RECUPERABLE: vacía las tablas del registro y las restaura desde el respaldo.
cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy
set -a; . ./.env; set +a
export LOCAL_UID=$(id -u) LOCAL_GID=$(id -g)
D=$VDR_DOMAIN
W=../evidencias/work
PSQL="docker compose exec -T postgres psql -U $POSTGRES_USER -d $POSTGRES_DB"
ADMIN() { docker compose --progress quiet --profile tools run --rm -T tools admin --admin-url "https://$D:8443" --client-id vdr-admin --secret "$CLIENT_SECRET_ADMIN" --p12 /certs/vdr-admin.p12 --p12-pass "$P12_PASS" --ca /certs/ca.crt "$@"; }
HUELLA() { curl -s --cacert certs/ca.crt --resolve $D:8443:127.0.0.1 "https://$D:8443/$1/did.json" | sha256sum | cut -c1-16; }
CODIGO() { curl -s -o /dev/null -w "%{http_code}" --cacert certs/ca.crt --resolve $D:8443:127.0.0.1 "https://$D:8443/$1/did.json"; }

echo "=== 1. ANTES: huellas públicas, versiones y estado ==="
for p in entidades/avance lab/laboratorio; do echo "$p  huella=$(HUELLA $p)"; done
echo "entidades/avance-ciclo (desactivado)  HTTP $(CODIGO entidades/avance-ciclo)"
$PSQL -tAc "select 'did_documents='||(select count(*) from did_documents)||'  versiones='||(select count(*) from did_document_versions)||'  auditoria='||(select count(*) from audit_log)"

echo; echo "=== 2. RESPALDO (solo el administrador, por el canal mTLS) ==="
ADMIN --method POST --path /admin/v1/backup --out leccion-backup.json | tail -1
jq '{format, checksum, tablas: (.tables | to_entries | map({(.key): (.value|length)}) | add)}' $W/leccion-backup.json

echo; echo "=== 3. Restaurar SOBRE UN REGISTRO CON DATOS ==="
ADMIN --method POST --path /admin/v1/restore --body leccion-backup.json 2>&1 | head -4

echo; echo "=== 4. PÉRDIDA TOTAL (se vacían todas las tablas del registro) ==="
$PSQL -q -c "TRUNCATE audit_log, operations, challenges, did_document_versions, did_documents, namespaces, entity_accounts CASCADE"
echo "avance HTTP $(CODIGO entidades/avance)   laboratorio HTTP $(CODIGO lab/laboratorio)"
$PSQL -tAc "select 'did_documents='||(select count(*) from did_documents)"

echo; echo "=== 5. RESTAURAR UN RESPALDO ALTERADO (se cambia un hash dentro del archivo) ==="
jq '.tables.did_document_versions[0].hash = "sha256:00"' $W/leccion-backup.json > $W/leccion-backup-alterado.json
ADMIN --method POST --path /admin/v1/restore --body leccion-backup-alterado.json 2>&1 | head -4
$PSQL -tAc "select 'tras el intento fallido: did_documents='||(select count(*) from did_documents)"

echo; echo "=== 6. RESTAURAR EL RESPALDO BUENO ==="
ADMIN --method POST --path /admin/v1/restore --body leccion-backup.json 2>&1 | head -14

echo; echo "=== 7. DESPUÉS: ¿quedó idéntico? ==="
for p in entidades/avance lab/laboratorio; do echo "$p  huella=$(HUELLA $p)"; done
echo "entidades/avance-ciclo (desactivado)  HTTP $(CODIGO entidades/avance-ciclo)"
$PSQL -tAc "select 'did_documents='||(select count(*) from did_documents)||'  versiones='||(select count(*) from did_document_versions)||'  auditoria='||(select count(*) from audit_log)"
$PSQL -tAc "select 'eventos BACKUP='||count(*) from audit_log where action='BACKUP'"
$PSQL -tAc "select 'eventos RESTORE='||count(*) from audit_log where action='RESTORE'"
