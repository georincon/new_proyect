#!/usr/bin/env bash
# ERSo 2026-002 — todos los laboratorios en una sola sesión. Genera la MISMA sesión que el informe final.
# Requisitos: proyecto levantado (docker compose up -d en deploy/), imagen holder-sim construida y base desechable vdr-test-pg (pruebas automáticas).
# Uso:  bash scripts/lab-002-completo.sh

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
printf '\n%s\n' '$ sed -n '"'"'/listen 8444/,/location \/ { return 404; }/p'"'"' nginx/nginx.conf | cut -c1-165'
sed -n '/listen 8444/,/location \/ { return 404; }/p' nginx/nginx.conf | cut -c1-165
printf '\n%s\n' '$ grep -n '"'"'        ep("'"'"' ../credential-service/src/main/kotlin/co/org/avance/ssi/credential/Server.kt | sed -E '"'"'s/^[0-9]+: *//; s/ \{.*$//'"'"' '
grep -n '        ep("' ../credential-service/src/main/kotlin/co/org/avance/ssi/credential/Server.kt | sed -E 's/^[0-9]+: *//; s/ \{.*$//' 
printf '\n%s\n' '$ sed -n '"'"'/object CredentialProfiles/,/^}/p'"'"' ../credentials-core/src/main/kotlin/co/org/avance/ssi/credentials/Profiles.kt | cut -c1-190'
sed -n '/object CredentialProfiles/,/^}/p' ../credentials-core/src/main/kotlin/co/org/avance/ssi/credentials/Profiles.kt | cut -c1-190
printf '\n%s\n' '$ grep -n '"'"'implementation('"'"' ../credentials-core/build.gradle.kts ../credential-service/build.gradle.kts | cut -c1-150'
grep -n 'implementation(' ../credentials-core/build.gradle.kts ../credential-service/build.gradle.kts | cut -c1-150
echo; echo "══════ LABORATORIO A · Paso 1: el perfil ══════"
printf '\n%s\n' '$ sed -n '"'"'1,45p'"'"' ../docs/PERFIL-CREDENCIALES.md | cut -c1-200'
sed -n '1,45p' ../docs/PERFIL-CREDENCIALES.md | cut -c1-200
echo; echo "══════ LABORATORIO B · Criterios 1, 2 y 4: el recorrido de credenciales ══════"
printf '\n%s\n' '$ CRED tour-credentials --secrets-out secrets-cred.txt'
CRED tour-credentials --secrets-out secrets-cred.txt
echo; echo "══════ LABORATORIO D · Criterio 3: sin protocolo propio ══════"
printf '\n%s\n' '$ curl -s -o /dev/null -w "POST /admin/offers por el canal público 8444 -> HTTP %{http_code}\n" $CW -X POST $BASE/admin/offers …'
curl -s -o /dev/null -w "POST /admin/offers por el canal público 8444 -> HTTP %{http_code}\n" $CW -X POST $BASE/admin/offers
curl -s -o /dev/null -w "POST /admin/offers por 9443 con certificado pero SIN token -> HTTP %{http_code}\n" --cacert certs/ca.crt --resolve $D:9443:127.0.0.1 --cert certs/vdr-admin.crt --key certs/vdr-admin.key -X POST https://$D:9443/admin/offers -H "Content-Type: application/json" -d '{}'
curl -s -o /dev/null -w "POST /admin/offers por 9443 SIN certificado -> HTTP %{http_code}\n" --cacert certs/ca.crt --resolve $D:9443:127.0.0.1 -X POST https://$D:9443/admin/offers -d '{}'
curl -s -o /dev/null -w "GET /token (verbo equivocado) por 8444 -> HTTP %{http_code}\n" $CW $BASE/token
printf '\n%s\n' '$ curl -s $CW $BASE/.well-known/oauth-authorization-server | jq -c '"'"'{issuer, token_endpoint, grant_types_supported}'"'"' '
curl -s $CW $BASE/.well-known/oauth-authorization-server | jq -c '{issuer, token_endpoint, grant_types_supported}' 
echo; echo "══════ LABORATORIO E · Pruebas automáticas ══════"
printf '\n%s\n' '$ cd /home/geovani/Descargas/generic/bitacora/new_proyect'
cd /home/geovani/Descargas/generic/bitacora/new_proyect
printf '\n%s\n' '$ TEST_DB_URL=jdbc:postgresql://127.0.0.1:55432/wallet_test ./gradlew :credentials-core:test :credential-service:test --rerun-tasks -q 2>&1 | tail -3; echo '"'"'(sin salida = todas pasaron)'"'"''
TEST_DB_URL=jdbc:postgresql://127.0.0.1:55432/wallet_test ./gradlew :credentials-core:test :credential-service:test --rerun-tasks -q 2>&1 | tail -3; echo '(sin salida = todas pasaron)'
printf '\n%s\n' '$ python3 - <<'"'"'EOF'"'"' …'
python3 - <<'EOF'
import re, glob, html
for pat in ['credentials-core/build/test-results/test/*CredentialsCoreTest*.xml', 'credential-service/build/test-results/test/*Erso002CredentialsTest*.xml']:
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
echo; echo "Fin de los laboratorios de la ERSo 2."
