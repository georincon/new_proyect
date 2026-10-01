#!/usr/bin/env bash
# ERSo 2026-001 — todos los laboratorios en una sola sesión. Genera la MISMA sesión que el informe final.
# Requisitos: proyecto levantado (docker compose up -d en deploy/), imagen holder-sim construida y base desechable vdr-test-pg (pruebas automáticas).
# Uso:  bash scripts/lab-001-completo.sh

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
printf '\n%s\n' '$ curl -s -o /dev/null -w "wallet/v1 sin token por el canal 8444 -> HTTP %{http_code}\n" $CW $BASE/wallet/v1/instances/00000000-0000-0000-0000-000000000000'
curl -s -o /dev/null -w "wallet/v1 sin token por el canal 8444 -> HTTP %{http_code}\n" $CW $BASE/wallet/v1/instances/00000000-0000-0000-0000-000000000000
echo; echo "══════ MONTAJE (consultas de lectura) ══════"
printf '\n%s\n' '$ sed -n '"'"'/listen 8444/,/location \/ { return 404; }/p'"'"' nginx/nginx.conf'
sed -n '/listen 8444/,/location \/ { return 404; }/p' nginx/nginx.conf
printf '\n%s\n' '$ echo "\dt wallet*" | $PSQL'
echo "\dt wallet*" | $PSQL
printf '\n%s\n' '$ grep -nE '"'"'WALLET_(MIN_LEVEL|REQUIRE_VERIFIED_BOOT|ATTESTATION_ROOTS)|recovery|maxAttempts|lock'"'"' ../wallet-service/src/main/kotlin/co/org/avance/ssi/wallet/Config.kt | cut -c1-170'
grep -nE 'WALLET_(MIN_LEVEL|REQUIRE_VERIFIED_BOOT|ATTESTATION_ROOTS)|recovery|maxAttempts|lock' ../wallet-service/src/main/kotlin/co/org/avance/ssi/wallet/Config.kt | cut -c1-170
echo; echo "══════ LABORATORIO A · Criterio 1: activación (recorrido completo de la cartera) ══════"
printf '\n%s\n' '$ OUT=$(WALLET tour-wallet --secrets-out secrets-wallet.txt); echo "$OUT"'
OUT=$(WALLET tour-wallet --secrets-out secrets-wallet.txt); echo "$OUT"
printf '\n%s\n' '$ echo "select left(id::text,8) as instancia, status, declared_level as declarado, verified_level as verificado, attested, to_char(activated_at,'"'"'HH24:MI:SS'"'"') as activada, revoked_reason from wallet_instances order by activated_at desc limit 4" | $PSQL'
echo "select left(id::text,8) as instancia, status, declared_level as declarado, verified_level as verificado, attested, to_char(activated_at,'HH24:MI:SS') as activada, revoked_reason from wallet_instances order by activated_at desc limit 4" | $PSQL
printf '\n%s\n' '$ echo "select indexdef from pg_indexes where indexname='"'"'wallet_one_active_per_citizen'"'"'" | $PSQL -tA'
echo "select indexdef from pg_indexes where indexname='wallet_one_active_per_citizen'" | $PSQL -tA
printf '\n%s\n' '$ echo "select action, count(*) from wallet_audit group by action order by 1" | $PSQL …'
echo "select action, count(*) from wallet_audit group by action order by 1" | $PSQL
echo "update wallet_audit set action='X' where id = (select min(id) from wallet_audit)" | $PSQL 2>&1 | head -2
echo; echo "══════ LABORATORIO B · Criterio 2: no exportabilidad ══════"
printf '\n%s\n' '$ echo "select table_name, column_name from information_schema.columns where table_name like '"'"'wallet%'"'"' and (column_name ~* '"'"'priv|secret|seed|d_value|pkcs8'"'"' ) order by 1,2" | $PSQL …'
echo "select table_name, column_name from information_schema.columns where table_name like 'wallet%' and (column_name ~* 'priv|secret|seed|d_value|pkcs8' ) order by 1,2" | $PSQL
echo "select table_name, string_agg(column_name, ', ' order by ordinal_position) as columnas from information_schema.columns where table_name='wallet_instances' group by 1" | $PSQL
printf '\n%s\n' '$ docker compose exec -T postgres pg_dump -U $POSTGRES_USER $POSTGRES_DB > $W/dump.sql …'
docker compose exec -T postgres pg_dump -U $POSTGRES_USER $POSTGRES_DB > $W/dump.sql
echo "control: el volcado contiene datos de la cartera -> $(grep -c 'INSTANCE_ACTIVATED' $W/dump.sql) líneas con INSTANCE_ACTIVATED"
echo "formas textuales de claves privadas de prueba a buscar: $(wc -l < $W/secrets-wallet.txt)"
echo "coincidencias de ESAS formas en todo el volcado de la base: $(grep -c -F -f $W/secrets-wallet.txt $W/dump.sql)"
rm -f $W/dump.sql
printf '\n%s\n' '$ sed -n '"'"'46,75p'"'"' /home/geovani/Descargas/generic/bitacora/new_proyect/wallet-core/src/main/kotlin/co/org/avance/ssi/wallet/core/KeyCustodian.kt | cut -c1-200'
sed -n '46,75p' /home/geovani/Descargas/generic/bitacora/new_proyect/wallet-core/src/main/kotlin/co/org/avance/ssi/wallet/core/KeyCustodian.kt | cut -c1-200
echo; echo "══════ LABORATORIO D · Criterio 4: recuperación ══════"
printf '\n%s\n' '$ echo "select left(id::text,8) as ciudadano, length(recovery_salt) as sal_bytes, length(recovery_hash) as hash_bytes, left(encode(recovery_hash,'"'"'hex'"'"'),16)||'"'"'…'"'"' as hash, failed_attempts, locked_until is not null as bloqueado from wallet_citizens order by created_at desc limit 3" | $PSQL'
echo "select left(id::text,8) as ciudadano, length(recovery_salt) as sal_bytes, length(recovery_hash) as hash_bytes, left(encode(recovery_hash,'hex'),16)||'…' as hash, failed_attempts, locked_until is not null as bloqueado from wallet_citizens order by created_at desc limit 3" | $PSQL
printf '\n%s\n' '$ REF=$(curl -s $CW -X POST $BASE/wallet/v1/citizens -H "Content-Type: application/json" -d '"'"'{}'"'"' | jq -r .citizenRef); echo "ciudadano de prueba: $REF" …'
REF=$(curl -s $CW -X POST $BASE/wallet/v1/citizens -H "Content-Type: application/json" -d '{}' | jq -r .citizenRef); echo "ciudadano de prueba: $REF"
for i in 1 2 3 4 5 6; do printf "intento %s con un código falso -> " $i; curl -s $CW -X POST $BASE/wallet/v1/recovery/start -H "Content-Type: application/json" -d "{\"citizenRef\":\"$REF\",\"recoveryCode\":\"AAAA-BBBB-CCCC-DDDD-EEE$i\"}" | jq -c '[.error,.message]'; done
echo "select failed_attempts, locked_until is not null as bloqueado from wallet_citizens where id='$REF'" | $PSQL
echo; echo "══════ LABORATORIO E · Pruebas automáticas ══════"
printf '\n%s\n' '$ cd /home/geovani/Descargas/generic/bitacora/new_proyect'
cd /home/geovani/Descargas/generic/bitacora/new_proyect
printf '\n%s\n' '$ TEST_DB_URL=jdbc:postgresql://127.0.0.1:55432/wallet_test ./gradlew :wallet-core:test :wallet-service:test --rerun-tasks -q 2>&1 | tail -3; echo '"'"'(sin salida = todas pasaron)'"'"''
TEST_DB_URL=jdbc:postgresql://127.0.0.1:55432/wallet_test ./gradlew :wallet-core:test :wallet-service:test --rerun-tasks -q 2>&1 | tail -3; echo '(sin salida = todas pasaron)'
printf '\n%s\n' '$ python3 - <<'"'"'EOF'"'"' …'
python3 - <<'EOF'
import re, glob, html
for pat in ['wallet-core/build/test-results/test/*WalletCoreTest*.xml', 'wallet-service/build/test-results/test/*KeyAttestationTest*.xml', 'wallet-service/build/test-results/test/*Erso001WalletTest*.xml']:
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
echo; echo "Fin de los laboratorios de la ERSo 1."
