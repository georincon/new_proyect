#!/usr/bin/env bash
# ERSo 2026-006 — todos los laboratorios en una sola sesión. Genera la MISMA sesión que el informe final.
# Requisitos: proyecto levantado (docker compose up -d en deploy/) y base desechable de pruebas vdr-test-pg (laboratorio de pruebas automáticas).
# Uso:  bash scripts/lab-006-completo.sh

echo; echo "══════ MONTAJE (consultas de lectura) ══════"
printf '\n%s\n' '$ cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy && set -a && . ./.env && set +a'
cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy && set -a && . ./.env && set +a
printf '\n%s\n' '$ docker compose ps --format '"'"'table {{.Service}}\t{{.Ports}}'"'"''
docker compose ps --format 'table {{.Service}}\t{{.Ports}}'
printf '\n%s\n' '$ sed -n '"'"'/listen 443/,/location \/ { return 404; }/p'"'"' nginx/nginx.conf'
sed -n '/listen 443/,/location \/ { return 404; }/p' nginx/nginx.conf
printf '\n%s\n' '$ sed -n '"'"'/listen 8443/,/location \/ { return 404; }/p'"'"' nginx/nginx.conf'
sed -n '/listen 8443/,/location \/ { return 404; }/p' nginx/nginx.conf
printf '\n%s\n' '$ echo "$VDR_CLIENTS" | jq '"'"'map(del(.secret, .clientSecret) | with_entries(select(.key|test("secret";"i")|not)))'"'"''
echo "$VDR_CLIENTS" | jq 'map(del(.secret, .clientSecret) | with_entries(select(.key|test("secret";"i")|not)))'
printf '\n%s\n' '$ grep -n '"'"'NAMESPACE_NOT_OWNED\|ACCOUNT_MISSING_OR_DISABLED\|WRITE_CHANNEL_NOT_RESTRICTED\|DID_DOMAIN_MISMATCH\|NAMESPACE_NOT_RESERVED\|DID_INVALID'"'"' ../vdr-service/src/main/kotlin/co/org/avance/ssi/vdr/RegistryService.kt | cut -c1-170'
grep -n 'NAMESPACE_NOT_OWNED\|ACCOUNT_MISSING_OR_DISABLED\|WRITE_CHANNEL_NOT_RESTRICTED\|DID_DOMAIN_MISMATCH\|NAMESPACE_NOT_RESERVED\|DID_INVALID' ../vdr-service/src/main/kotlin/co/org/avance/ssi/vdr/RegistryService.kt | cut -c1-170
printf '\n%s\n' '$ sed -n '"'"'/fun channelRestricted/,/^    }/p'"'"' ../vdr-service/src/main/kotlin/co/org/avance/ssi/vdr/AuthService.kt'
sed -n '/fun channelRestricted/,/^    }/p' ../vdr-service/src/main/kotlin/co/org/avance/ssi/vdr/AuthService.kt
printf '\n%s\n' '$ grep -rn '"'"'tokenTtl\|TOKEN_TTL'"'"' ../vdr-service/src/main/kotlin/co/org/avance/ssi/vdr/AppConfig.kt | cut -c1-160'
grep -rn 'tokenTtl\|TOKEN_TTL' ../vdr-service/src/main/kotlin/co/org/avance/ssi/vdr/AppConfig.kt | cut -c1-160
echo; echo "══════ PREPARACIÓN ══════"
printf '\n%s\n' '$ cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy'
cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy
printf '\n%s\n' '$ set -a; . ./.env; set +a; export LOCAL_UID=$(id -u) LOCAL_GID=$(id -g)'
set -a; . ./.env; set +a; export LOCAL_UID=$(id -u) LOCAL_GID=$(id -g)
printf '\n%s\n' '$ D=$VDR_DOMAIN …'
D=$VDR_DOMAIN
PUB="--cacert certs/ca.crt --resolve $D:8443:127.0.0.1"
ADM="--cacert certs/ca.crt --resolve $D:9443:127.0.0.1"
W=../evidencias/work
PSQL="docker compose exec -T postgres psql -U $POSTGRES_USER -d $POSTGRES_DB"
TOOLS() { docker compose --progress quiet --profile tools run --rm -T tools "$@"; }
ESCRIBIR() { local cid=$1 sec=$2 p12=$3 did=$4; shift 4; TOOLS write --admin-url "https://$D:8443" --client-id "$cid" --secret "$sec" --p12 "/certs/$p12.p12" --p12-pass "$P12_PASS" --ca /certs/ca.crt --did "$did" "$@"; }
ADMINISTRAR() { local cid=$1 sec=$2 p12=$3; shift 3; TOOLS admin --admin-url "https://$D:8443" --client-id "$cid" --secret "$sec" --p12 "/certs/$p12.p12" --p12-pass "$P12_PASS" --ca /certs/ca.crt "$@"; }
printf '\n%s\n' '$ docker compose ps --format '"'"'table {{.Service}}\t{{.Status}}'"'"''
docker compose ps --format 'table {{.Service}}\t{{.Status}}'
printf '\n%s\n' '$ curl -s $PUB https://$D:8443/health'
curl -s $PUB https://$D:8443/health
printf '\n%s\n' '$ ID=inst-006-$(date +%s); DID=did:web:$D:entidades:$ID; echo "ID=$ID"; echo "DID=$DID"'
ID=inst-006-$(date +%s); DID=did:web:$D:entidades:$ID; echo "ID=$ID"; echo "DID=$DID"
echo; echo "══════ LABORATORIO A · Paso 1: precondiciones ══════"
printf '\n%s\n' '$ echo "select client_id, display_name, enabled from entity_accounts order by 1" | $PSQL'
echo "select client_id, display_name, enabled from entity_accounts order by 1" | $PSQL
printf '\n%s\n' '$ echo "select path, owner_client_id from namespaces where path not like '"'"'%prueba-005%'"'"' order by path" | $PSQL'
echo "select path, owner_client_id from namespaces where path not like '%prueba-005%' order by path" | $PSQL
printf '\n%s\n' '$ echo "desafíos antes: $($PSQL -tAc "select count(*) from challenges")"'
echo "desafíos antes: $($PSQL -tAc "select count(*) from challenges")"
printf '\n%s\n' '$ ESCRIBIR lab-operator $CLIENT_SECRET_LAB lab-operator did:web:$D:entidades:avance --purpose UPDATE --expected 1 --key lab.json --doc lab-doc.json 2>&1 | tail -n +2'
ESCRIBIR lab-operator $CLIENT_SECRET_LAB lab-operator did:web:$D:entidades:avance --purpose UPDATE --expected 1 --key lab.json --doc lab-doc.json 2>&1 | tail -n +2
printf '\n%s\n' '$ echo "desafíos después: $($PSQL -tAc "select count(*) from challenges")"'
echo "desafíos después: $($PSQL -tAc "select count(*) from challenges")"
printf '\n%s\n' '$ echo "select action, detail->'"'"'failures'"'"' as motivos from audit_log where action = '"'"'CHALLENGE_DENIED'"'"' order by id desc limit 3" | $PSQL'
echo "select action, detail->'failures' as motivos from audit_log where action = 'CHALLENGE_DENIED' order by id desc limit 3" | $PSQL
echo; echo "══════ LABORATORIO B · Paso 2: construir el documento ══════"
printf '\n%s\n' '$ TOOLS keygen --out c006-clave.json'
TOOLS keygen --out c006-clave.json
printf '\n%s\n' '$ TOOLS build-doc --did $DID --key c006-clave.json --service-url "https://$D/issuer" --out c006-doc.json >/dev/null; jq . $W/c006-doc.json'
TOOLS build-doc --did $DID --key c006-clave.json --service-url "https://$D/issuer" --out c006-doc.json >/dev/null; jq . $W/c006-doc.json
printf '\n%s\n' '$ jq -c '"'"'keys'"'"' $W/c006-doc.json; grep -c -iE "privateKey|pkcs8" $W/c006-doc.json'
jq -c 'keys' $W/c006-doc.json; grep -c -iE "privateKey|pkcs8" $W/c006-doc.json
echo; echo "══════ LABORATORIO C · Criterio 1 ══════"
printf '\n%s\n' '$ OUT=$(ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer $DID --purpose CREATE --expected 0 --key c006-clave.json --doc c006-doc.json 2>&1); echo "$OUT"'
OUT=$(ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer $DID --purpose CREATE --expected 0 --key c006-clave.json --doc c006-doc.json 2>&1); echo "$OUT"
printf '\n%s\n' '$ curl -s -D - -o /dev/null $PUB https://$D:8443/entidades/$ID/did.json'
curl -s -D - -o /dev/null $PUB https://$D:8443/entidades/$ID/did.json
printf '\n%s\n' '$ curl -s $PUB https://$D:8443/entidades/$ID/did.json | jq .'
curl -s $PUB https://$D:8443/entidades/$ID/did.json | jq .
printf '\n%s\n' '$ TOOLS resolve --did $DID --ca /certs/ca.crt | grep -E "^(resolutionMetadata|documentMetadata)" | cut -c1-200'
TOOLS resolve --did $DID --ca /certs/ca.crt | grep -E "^(resolutionMetadata|documentMetadata)" | cut -c1-200
printf '\n%s\n' '$ curl -s -o /dev/null -w "DID institucional real de Avance -> HTTP %{http_code}\n" $PUB https://$D:8443/entidades/avance/did.json; TOOLS resolve --did did:web:$D:entidades:avance --ca /certs/ca.crt | grep -E "^resolutionMetadata" | cut -c1-200'
curl -s -o /dev/null -w "DID institucional real de Avance -> HTTP %{http_code}\n" $PUB https://$D:8443/entidades/avance/did.json; TOOLS resolve --did did:web:$D:entidades:avance --ca /certs/ca.crt | grep -E "^resolutionMetadata" | cut -c1-200
printf '\n%s\n' '$ JWS=$(TOOLS sign --key avance.json --kid "did:web:$D:entidades:avance#key-1" --message "firma-institucional" | tail -1); TOOLS verify --jws "$JWS" --purpose assertionMethod --did did:web:$D:entidades:avance --ca /certs/ca.crt'
JWS=$(TOOLS sign --key avance.json --kid "did:web:$D:entidades:avance#key-1" --message "firma-institucional" | tail -1); TOOLS verify --jws "$JWS" --purpose assertionMethod --did did:web:$D:entidades:avance --ca /certs/ca.crt
echo; echo "══════ LABORATORIO D · Criterio 2 ══════"
printf '\n%s\n' '$ DIDX=did:web:$D:entidades:$ID; echo "DID objetivo de los intentos: $DIDX"'
DIDX=did:web:$D:entidades:$ID; echo "DID objetivo de los intentos: $DIDX"
printf '\n%s\n' '$ ESCRIBIR avance-issuer clave-incorrecta avance-issuer $DIDX --purpose UPDATE --expected 1 --key c006-clave.json --doc c006-doc.json 2>&1'
ESCRIBIR avance-issuer clave-incorrecta avance-issuer $DIDX --purpose UPDATE --expected 1 --key c006-clave.json --doc c006-doc.json 2>&1
printf '\n%s\n' '$ curl -s -o /dev/null -w "token SIN certificado cliente -> HTTP %{http_code}\n" $ADM -X POST https://$D:9443/admin/v1/oauth/token -d grant_type=client_credentials -d client_id=avance-issuer -d client_secret=$CLIENT_SECRET_AVANCE'
curl -s -o /dev/null -w "token SIN certificado cliente -> HTTP %{http_code}\n" $ADM -X POST https://$D:9443/admin/v1/oauth/token -d grant_type=client_credentials -d client_id=avance-issuer -d client_secret=$CLIENT_SECRET_AVANCE
printf '\n%s\n' '$ ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE lab-operator $DIDX --purpose UPDATE --expected 1 --key c006-clave.json --doc c006-doc.json 2>&1'
ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE lab-operator $DIDX --purpose UPDATE --expected 1 --key c006-clave.json --doc c006-doc.json 2>&1
printf '\n%s\n' '$ ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE intruso $DIDX --purpose UPDATE --expected 1 --key c006-clave.json --doc c006-doc.json 2>&1 | head -3 | cut -c1-200'
ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE intruso $DIDX --purpose UPDATE --expected 1 --key c006-clave.json --doc c006-doc.json 2>&1 | head -3 | cut -c1-200
printf '\n%s\n' '$ ESCRIBIR lab-operator $CLIENT_SECRET_LAB lab-operator did:web:$D:entidades:avance --purpose UPDATE --expected 1 --key lab.json --doc lab-doc.json 2>&1 | tail -n +2'
ESCRIBIR lab-operator $CLIENT_SECRET_LAB lab-operator did:web:$D:entidades:avance --purpose UPDATE --expected 1 --key lab.json --doc lab-doc.json 2>&1 | tail -n +2
printf '\n%s\n' '$ ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer did:web:$D:lab:invasion --purpose CREATE --expected 0 --key c006-clave.json --doc c006-doc.json 2>&1 | tail -n +2'
ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer did:web:$D:lab:invasion --purpose CREATE --expected 0 --key c006-clave.json --doc c006-doc.json 2>&1 | tail -n +2
printf '\n%s\n' '$ ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer did:web:$D:titulares:invasion --purpose CREATE --expected 0 --key c006-clave.json --doc c006-doc.json 2>&1 | tail -n +2'
ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer did:web:$D:titulares:invasion --purpose CREATE --expected 0 --key c006-clave.json --doc c006-doc.json 2>&1 | tail -n +2
printf '\n%s\n' '$ ADMINISTRAR vdr-admin $CLIENT_SECRET_ADMIN vdr-admin --path "/admin/v1/audit" 2>&1 | sed -n '"'"'/^\[/,$p'"'"' | jq -r '"'"'.[-12:][] | "\(.at[11:23])  \(.actor)  \(.action)  \(.detail[0:90])"'"'"''
ADMINISTRAR vdr-admin $CLIENT_SECRET_ADMIN vdr-admin --path "/admin/v1/audit" 2>&1 | sed -n '/^\[/,$p' | jq -r '.[-12:][] | "\(.at[11:23])  \(.actor)  \(.action)  \(.detail[0:90])"'
printf '\n%s\n' '$ curl -s -o /dev/null -w "PUT por la puerta pública        -> HTTP %{http_code}\n" -X PUT $PUB https://$D:8443/entidades/avance/did.json'
curl -s -o /dev/null -w "PUT por la puerta pública        -> HTTP %{http_code}\n" -X PUT $PUB https://$D:8443/entidades/avance/did.json
printf '\n%s\n' '$ curl -s -o /dev/null -w "leer did.json por la de escritura -> HTTP %{http_code}\n" $ADM --cert certs/avance-issuer.crt --key certs/avance-issuer.key https://$D:9443/entidades/avance/did.json'
curl -s -o /dev/null -w "leer did.json por la de escritura -> HTTP %{http_code}\n" $ADM --cert certs/avance-issuer.crt --key certs/avance-issuer.key https://$D:9443/entidades/avance/did.json
echo; echo "══════ LABORATORIO E · Criterio 3 ══════"
printf '\n%s\n' '$ curl -s $PUB https://$D:8443/entidades/$ID/did.json | grep -c -iE '"'"'private|secret|credentialSubject|birthDate|"d":'"'"''
curl -s $PUB https://$D:8443/entidades/$ID/did.json | grep -c -iE 'private|secret|credentialSubject|birthDate|"d":'
printf '\n%s\n' '$ PRIV=$(jq -r .privateKeyPkcs8 $W/c006-clave.json); curl -s $PUB https://$D:8443/entidades/$ID/did.json | grep -c -F "$PRIV"'
PRIV=$(jq -r .privateKeyPkcs8 $W/c006-clave.json); curl -s $PUB https://$D:8443/entidades/$ID/did.json | grep -c -F "$PRIV"
printf '\n%s\n' '$ DIDN=did:web:$D:entidades:$ID-neg; TOOLS build-doc --did $DIDN --key c006-clave.json --service-url "https://$D/issuer" --out c006-neg.json >/dev/null; echo "base de los intentos: $DIDN"'
DIDN=did:web:$D:entidades:$ID-neg; TOOLS build-doc --did $DIDN --key c006-clave.json --service-url "https://$D/issuer" --out c006-neg.json >/dev/null; echo "base de los intentos: $DIDN"
printf '\n%s\n' '$ jq '"'"'.verificationMethod[0].privateKeyMultibase="z1234"'"'"' $W/c006-neg.json > $W/c006-neg1.json; ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer $DIDN --purpose CREATE --expected 0 --key c006-clave.json --doc c006-neg1.json 2>&1 | tail -n +4'
jq '.verificationMethod[0].privateKeyMultibase="z1234"' $W/c006-neg.json > $W/c006-neg1.json; ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer $DIDN --purpose CREATE --expected 0 --key c006-clave.json --doc c006-neg1.json 2>&1 | tail -n +4
printf '\n%s\n' '$ jq '"'"'.credentialSubject={"name":"Ana Pérez"}'"'"' $W/c006-neg.json > $W/c006-neg2.json; ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer $DIDN --purpose CREATE --expected 0 --key c006-clave.json --doc c006-neg2.json 2>&1 | tail -n +4'
jq '.credentialSubject={"name":"Ana Pérez"}' $W/c006-neg.json > $W/c006-neg2.json; ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer $DIDN --purpose CREATE --expected 0 --key c006-clave.json --doc c006-neg2.json 2>&1 | tail -n +4
printf '\n%s\n' '$ jq '"'"'.controller="did:web:otra.entidad"'"'"' $W/c006-neg.json > $W/c006-neg3.json; ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer $DIDN --purpose CREATE --expected 0 --key c006-clave.json --doc c006-neg3.json 2>&1 | tail -n +4'
jq '.controller="did:web:otra.entidad"' $W/c006-neg.json > $W/c006-neg3.json; ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer $DIDN --purpose CREATE --expected 0 --key c006-clave.json --doc c006-neg3.json 2>&1 | tail -n +4
printf '\n%s\n' '$ echo "DID registrados con ese identificador: $($PSQL -tAc "select count(*) from did_documents where did = '"'"'$DIDN'"'"'")"'
echo "DID registrados con ese identificador: $($PSQL -tAc "select count(*) from did_documents where did = '$DIDN'")"
echo; echo "══════ LABORATORIO F · Criterio 4 ══════"
printf '\n%s\n' '$ OPID=$(echo "$OUT" | sed -n '"'"'/^{/,$p'"'"' | jq -r .operationId); echo "operationId de la publicación: $OPID"'
OPID=$(echo "$OUT" | sed -n '/^{/,$p' | jq -r .operationId); echo "operationId de la publicación: $OPID"
printf '\n%s\n' '$ ADMINISTRAR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer --path "/admin/v1/operations/$OPID" 2>&1 | sed -n '"'"'/^{/,$p'"'"''
ADMINISTRAR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer --path "/admin/v1/operations/$OPID" 2>&1 | sed -n '/^{/,$p'
printf '\n%s\n' '$ echo "select purpose, status, version, left(hash,26) as hash, public_url, (confirmed_at is not null) as confirmada from operations where id = '"'"'$OPID'"'"'" | $PSQL'
echo "select purpose, status, version, left(hash,26) as hash, public_url, (confirmed_at is not null) as confirmada from operations where id = '$OPID'" | $PSQL
printf '\n%s\n' '$ curl -s $PUB https://$D:8443/entidades/$ID/did.json | sha256sum | awk '"'"'{print "hash del documento público: sha256:"$1}'"'"'; echo "select '"'"'hash de la evidencia      : '"'"'||hash from operations where id = '"'"'$OPID'"'"'" | $PSQL -tA'
curl -s $PUB https://$D:8443/entidades/$ID/did.json | sha256sum | awk '{print "hash del documento público: sha256:"$1}'; echo "select 'hash de la evidencia      : '||hash from operations where id = '$OPID'" | $PSQL -tA
printf '\n%s\n' '$ ADMINISTRAR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer --path "/admin/v1/audit?did=$DID" 2>&1 | sed -n '"'"'/^\[/,$p'"'"' | jq -r '"'"'.[] | "\(.at[11:23])  \(.actor)  \(.action)  v\(.version // "-")"'"'"''
ADMINISTRAR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer --path "/admin/v1/audit?did=$DID" 2>&1 | sed -n '/^\[/,$p' | jq -r '.[] | "\(.at[11:23])  \(.actor)  \(.action)  v\(.version // "-")"'
echo; echo "══════ LABORATORIO G · Pruebas automáticas ══════"
printf '\n%s\n' '$ cd /home/geovani/Descargas/generic/bitacora/new_proyect'
cd /home/geovani/Descargas/generic/bitacora/new_proyect
printf '\n%s\n' '$ TEST_DB_URL=jdbc:postgresql://127.0.0.1:55432/wallet_test ./gradlew :vdr-service:test --tests '"'"'*Erso006*'"'"' --rerun-tasks -q 2>&1 | tail -3; echo "(sin salida = todas pasaron)"'
TEST_DB_URL=jdbc:postgresql://127.0.0.1:55432/wallet_test ./gradlew :vdr-service:test --tests '*Erso006*' --rerun-tasks -q 2>&1 | tail -3; echo "(sin salida = todas pasaron)"
printf '\n%s\n' '$ python3 - <<'"'"'EOF'"'"' …'
python3 - <<'EOF'
import re, glob, html
f = glob.glob('vdr-service/build/test-results/test/*Erso006*.xml')[0]
s = open(f, encoding='utf-8').read()
tot = re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)"', s).groups()
print(f"resultado: {tot[0]} pruebas, {tot[1]} omitidas, {tot[2]} fallos\n")
for m in re.finditer(r'<testcase name="([^"]+)"[^>]*?(/>|>(.*?)</testcase>)', s, re.S):
    name = html.unescape(m.group(1)).removesuffix("()")
    estado = "FALLA" if "<failure" in (m.group(3) or "") else "PASA "
    print(f"  [{estado}] {name}")
EOF
echo; echo "Fin de los laboratorios de la ERSo 6."
