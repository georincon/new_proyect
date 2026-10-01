#!/usr/bin/env bash
# Laboratorio ERSo 2026-004, criterio 1: registro, actualización y trazabilidad (cuatro experimentos).
# Requisitos: proyecto levantado y con datos (docs/MARCO-CONCEPTUAL.md, sección 2b, pasos A–E); existe evidencias/work/lab.json.
# Cada ejecución agrega UNA versión nueva al DID del laboratorio (el historial solo crece).
cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy
set -a; . ./.env; set +a
export LOCAL_UID=$(id -u) LOCAL_GID=$(id -g)
D=$VDR_DOMAIN
DID=did:web:$D:lab:laboratorio
PSQL="docker compose exec -T postgres psql -U $POSTGRES_USER -d $POSTGRES_DB"
TOOLS() { docker compose --progress quiet --profile tools run --rm -T tools "$@"; }
LABOP() { TOOLS admin --admin-url "https://$D:8443" --client-id lab-operator --secret "$CLIENT_SECRET_LAB" --p12 /certs/lab-operator.p12 --p12-pass "$P12_PASS" --ca /certs/ca.crt "$@"; }
WRITE() { TOOLS write --admin-url "https://$D:8443" --client-id lab-operator --secret "$CLIENT_SECRET_LAB" --p12 /certs/lab-operator.p12 --p12-pass "$P12_PASS" --ca /certs/ca.crt --did $DID "$@"; }
HUELLA() { curl -s --cacert certs/ca.crt --resolve $D:8443:127.0.0.1 "https://$D:8443/lab/laboratorio/did.json" | sha256sum | awk '{print "sha256:"$1}'; }

echo "=== 0. Estado de partida: versiones del DID del laboratorio ==="
LABOP --path "/admin/v1/documents/$DID/versions" 2>&1 | sed -n '/^\[/,$p' | jq -r '.[] | "v\(.version)  \(.operation)  \(.hash[0:26])…"'
V=$(LABOP --path "/admin/v1/documents/$DID/versions" 2>&1 | sed -n '/^\[/,$p' | jq 'length')
echo "versión actual = $V"

echo; echo "=== 1. EXPERIMENTO 1: volver a CREAR un DID que ya existe (If-Match: 0) ==="
TOOLS build-doc --did $DID --key lab.json --out lab-doc-base.json >/dev/null
WRITE --purpose CREATE --expected 0 --key lab.json --doc lab-doc-base.json 2>&1 | tail -3

echo; echo "=== 2. EXPERIMENTO 2: ACTUALIZACIÓN legítima (If-Match: $V, firmada con la clave VIGENTE) ==="
TOOLS build-doc --did $DID --key lab.json --service-url "https://$D/lab/servicio-$(date +%s)" --out lab-doc-nuevo.json >/dev/null
WRITE --purpose UPDATE --expected $V --key lab.json --doc lab-doc-nuevo.json 2>&1

echo; echo "=== 3. ¿Lo publicado es lo escrito? (hash público frente al hash de la versión) ==="
echo "hash del documento público : $(HUELLA)"
LABOP --path "/admin/v1/documents/$DID/versions" 2>&1 | sed -n '/^\[/,$p' | jq -r '"hash de la última versión: " + .[-1].hash'

echo; echo "=== 4. EXPERIMENTO 3: un ATACANTE con certificado y token, pero con SU PROPIA clave ==="
TOOLS keygen --out lab-intruso.json
TOOLS build-doc --did $DID --key lab-intruso.json --service-url "https://sitio-malicioso.example" --out lab-doc-malo.json >/dev/null
WRITE --purpose UPDATE --expected $((V+1)) --key lab-intruso.json --doc lab-doc-malo.json 2>&1 | tail -6

echo; echo "=== 5. EXPERIMENTO 4: versión DESACTUALIZADA (If-Match: $V cuando ya es la $((V+1))) ==="
WRITE --purpose UPDATE --expected $V --key lab.json --doc lab-doc-nuevo.json 2>&1 | tail -6

echo; echo "=== 6. TRAZABILIDAD: historial de versiones ==="
LABOP --path "/admin/v1/documents/$DID/versions" 2>&1 | sed -n '/^\[/,$p' | jq -r '.[] | "v\(.version)  \(.operation)  actor=\(.actor)  \(.createdAt)  \(.hash[0:26])…"'

echo; echo "=== 7. TRAZABILIDAD: auditoría de este DID (los intentos rechazados también quedan) ==="
LABOP --path "/admin/v1/audit?did=$DID" 2>&1 | sed -n '/^\[/,$p' | jq -r '.[-9:][] | "\(.at[11:23])  \(.actor)  \(.action)  v\(.version // "-")"'

echo; echo "=== 8. INALTERABILIDAD: intentar borrar o modificar el historial ==="
$PSQL -c "delete from did_document_versions" 2>&1 | head -1
$PSQL -c "update did_document_versions set hash='x'" 2>&1 | head -1
