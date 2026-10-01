#!/usr/bin/env bash
# ERSo 2026-003 — todos los laboratorios en una sola sesión. Genera la MISMA sesión que el informe final.
# Requisitos: proyecto levantado (docker compose up -d en deploy/), imagen holder-sim construida y base desechable vdr-test-pg (pruebas automáticas).
# Uso:  bash scripts/lab-003-completo.sh

echo; echo "══════ PREPARACIÓN ══════"
printf '\n%s\n' '$ cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy'
cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy
printf '\n%s\n' '$ set -a; . ./.env; set +a; export LOCAL_UID=$(id -u) LOCAL_GID=$(id -g)'
set -a; . ./.env; set +a; export LOCAL_UID=$(id -u) LOCAL_GID=$(id -g)
printf '\n%s\n' '$ D=$VDR_DOMAIN …'
D=$VDR_DOMAIN
W=../evidencias/work
BASE=https://$D:8444
PUB="--cacert certs/ca.crt --resolve $D:8443:127.0.0.1"
CW="--cacert certs/ca.crt --resolve $D:8444:127.0.0.1"
PSQL="docker compose exec -T postgres psql -U $POSTGRES_USER -d $POSTGRES_DB"
H() { docker compose --progress quiet --profile tools run --rm -T holder "$@"; }
WALLET() { H scenario "$1" --authority authority.json --domain $D --base $BASE --ca /certs/ca.crt "${@:2}"; }
CRED() { H scenario "$1" --authority authority.json --domain $D --base $BASE --ca /certs/ca.crt --admin-url https://$D:8443 --p12 /certs/vdr-admin.p12 --p12-pass $P12_PASS --admin-token $CREDENTIAL_ADMIN_TOKEN "${@:2}"; }
printf '\n%s\n' '$ docker compose ps --format '"'"'table {{.Service}}\t{{.Status}}\t{{.Ports}}'"'"''
docker compose ps --format 'table {{.Service}}\t{{.Status}}\t{{.Ports}}'
echo; echo "══════ MONTAJE (consultas de lectura) ══════"
printf '\n%s\n' '$ echo "select path, owner_client_id from namespaces where owner_client_id='"'"'wallet-backend'"'"' order by 1 limit 3" | $PSQL'
echo "select path, owner_client_id from namespaces where owner_client_id='wallet-backend' order by 1 limit 3" | $PSQL
printf '\n%s\n' '$ sed -n '"'"'/Derivación del identificador/,/^ \*\//p'"'"' ../wallet-core/src/main/kotlin/co/org/avance/ssi/wallet/core/HolderDid.kt | cut -c1-220'
sed -n '/Derivación del identificador/,/^ \*\//p' ../wallet-core/src/main/kotlin/co/org/avance/ssi/wallet/core/HolderDid.kt | cut -c1-220
printf '\n%s\n' '$ echo "\d wallet_did_publications" | $PSQL | sed -n 1,20p'
echo "\d wallet_did_publications" | $PSQL | sed -n 1,20p
echo; echo "══════ LABORATORIO A · Criterios 1 a 4: el recorrido del DID del titular ══════"
printf '\n%s\n' '$ OUT=$(WALLET tour-did --secrets-out secrets-did.txt); echo "$OUT"; DIDT=$(echo "$OUT" | sed -n '"'"'s/^DID=//p'"'"'); TH=${DIDT##*:}'
OUT=$(WALLET tour-did --secrets-out secrets-did.txt); echo "$OUT"; DIDT=$(echo "$OUT" | sed -n 's/^DID=//p'); TH=${DIDT##*:}
echo; echo "══════ LABORATORIO B · Verificación de claves en toda la base ══════"
printf '\n%s\n' '$ docker compose exec -T postgres pg_dump -U $POSTGRES_USER $POSTGRES_DB > $W/dump.sql …'
docker compose exec -T postgres pg_dump -U $POSTGRES_USER $POSTGRES_DB > $W/dump.sql
echo "control: el volcado contiene la publicación -> $(grep -c 'DID_PUBLISHED' $W/dump.sql) líneas"
echo "formas de claves privadas de prueba buscadas: $(wc -l < $W/secrets-did.txt)"
echo "coincidencias en todo el volcado (backend + VDR): $(grep -c -F -f $W/secrets-did.txt $W/dump.sql)"
rm -f $W/dump.sql
echo; echo "══════ LABORATORIO C · Lo que ve el registro ══════"
printf '\n%s\n' '$ curl -s $PUB https://$D:8443/titulares/$TH/did.json | jq .'
curl -s $PUB https://$D:8443/titulares/$TH/did.json | jq .
printf '\n%s\n' '$ echo "select purpose, status, version, client_id, left(hash,26)||'"'"'…'"'"' as hash from operations where did='"'"'$DIDT'"'"'" | $PSQL …'
echo "select purpose, status, version, client_id, left(hash,26)||'…' as hash from operations where did='$DIDT'" | $PSQL
echo "select action, actor from audit_log where did='$DIDT' order by id" | $PSQL
printf '\n%s\n' '$ echo "select path, owner_client_id from namespaces where path='"'"'titulares/$TH'"'"'" | $PSQL'
echo "select path, owner_client_id from namespaces where path='titulares/$TH'" | $PSQL
echo; echo "══════ LABORATORIO D · Backend ══════"
printf '\n%s\n' '$ echo "select status, vdr_version as version, key_level as nivel, left(public_url,60)||'"'"'…'"'"' as url, completed_at is not null as completada from wallet_did_publications order by created_at desc limit 2" | $PSQL 2>&1 …'
echo "select status, vdr_version as version, key_level as nivel, left(public_url,60)||'…' as url, completed_at is not null as completada from wallet_did_publications order by created_at desc limit 2" | $PSQL 2>&1
echo "select length(blob) as bytes_cifrados, left(encode(blob,'hex'),24)||'…' as inicio, left(checksum,16)||'…' as checksum from wallet_did_backups order by updated_at desc limit 1" | $PSQL 2>&1
echo; echo "══════ LABORATORIO E · Pruebas automáticas ══════"
printf '\n%s\n' '$ cd /home/geovani/Descargas/generic/bitacora/new_proyect'
cd /home/geovani/Descargas/generic/bitacora/new_proyect
printf '\n%s\n' '$ TEST_DB_URL=jdbc:postgresql://127.0.0.1:55432/wallet_test ./gradlew :wallet-core:test :wallet-service:test --rerun-tasks -q 2>&1| tail -3; echo '"'"'(sin salida = todas pasaron)'"'"''
TEST_DB_URL=jdbc:postgresql://127.0.0.1:55432/wallet_test ./gradlew :wallet-core:test :wallet-service:test --rerun-tasks -q 2>&1| tail -3; echo '(sin salida = todas pasaron)'
printf '\n%s\n' '$ python3 - <<'"'"'EOF'"'"' …'
python3 - <<'EOF'
import re, glob, html
for pat in ['wallet-core/build/test-results/test/*WalletCoreTest*.xml', 'wallet-service/build/test-results/test/*Erso003HolderDidTest*.xml']:
    for f in sorted(glob.glob(pat)):
        s = open(f, encoding='utf-8').read()
        tot = re.search(r'name="([^"]+)" tests="(\d+)" skipped="(\d+)" failures="(\d+)"', s).groups()
        print(f"{tot[0].split('.')[-1]}: {tot[1]} pruebas, {tot[2]} omitidas, {tot[3]} fallos")
        for m in re.finditer(r'<testcase name="([^"]+)"[^>]*?(/>|>(.*?)</testcase>)', s, re.S):
            name = html.unescape(m.group(1)).removesuffix("()")
            estado = "FALLA" if "<failure" in (m.group(3) or "") else "PASA "
            print(f"  [{estado}] {name}")
        print()
EOF
echo; echo "Fin de los laboratorios de la ERSo 3."
