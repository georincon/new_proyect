#!/usr/bin/env bash
# ERSo 2026-008 — todos los laboratorios en una sola sesión. Genera la MISMA sesión que el informe final.
# Requisitos: proyecto levantado (docker compose up -d en deploy/) y base desechable de pruebas vdr-test-pg (laboratorio de pruebas automáticas).
# Uso:  bash scripts/lab-008-completo.sh

echo; echo "══════ MONTAJE (consultas de lectura) ══════"
printf '\n%s\n' '$ cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy && set -a && . ./.env && set +a'
cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy && set -a && . ./.env && set +a
printf '\n%s\n' '$ docker compose exec -T postgres psql -U $POSTGRES_USER -d $POSTGRES_DB -c "\dt"'
docker compose exec -T postgres psql -U $POSTGRES_USER -d $POSTGRES_DB -c "\dt"
printf '\n%s\n' '$ docker compose exec -T postgres psql -U $POSTGRES_USER -d $POSTGRES_DB -c "select tgrelid::regclass as tabla, tgname as disparador from pg_trigger where not tgisinternal order by 1"'
docker compose exec -T postgres psql -U $POSTGRES_USER -d $POSTGRES_DB -c "select tgrelid::regclass as tabla, tgname as disparador from pg_trigger where not tgisinternal order by 1"
printf '\n%s\n' '$ grep -nE '"'"'^        // [0-9]\)|^            // prueba de posesión'"'"' ../vdr-service/src/main/kotlin/co/org/avance/ssi/vdr/RegistryService.kt | cut -c1-175'
grep -nE '^        // [0-9]\)|^            // prueba de posesión' ../vdr-service/src/main/kotlin/co/org/avance/ssi/vdr/RegistryService.kt | cut -c1-175
printf '\n%s\n' '$ grep -n '"'"'challengeTtlSeconds'"'"' ../vdr-service/src/main/kotlin/co/org/avance/ssi/vdr/AppConfig.kt | head -2; grep -n '"'"'confirmTimeoutMs'"'"' ../vdr-service/src/main/kotlin/co/org/avance/ssi/vdr/AppConfig.kt | head -1'
grep -n 'challengeTtlSeconds' ../vdr-service/src/main/kotlin/co/org/avance/ssi/vdr/AppConfig.kt | head -2; grep -n 'confirmTimeoutMs' ../vdr-service/src/main/kotlin/co/org/avance/ssi/vdr/AppConfig.kt | head -1
printf '\n%s\n' '$ sed -n '"'"'/private fun expectedPayload/,/^    }/p'"'"' ../vdr-service/src/main/kotlin/co/org/avance/ssi/vdr/RegistryService.kt'
sed -n '/private fun expectedPayload/,/^    }/p' ../vdr-service/src/main/kotlin/co/org/avance/ssi/vdr/RegistryService.kt
echo; echo "══════ PREPARACIÓN ══════"
printf '\n%s\n' '$ cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy'
cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy
printf '\n%s\n' '$ set -a; . ./.env; set +a; export LOCAL_UID=$(id -u) LOCAL_GID=$(id -g)'
set -a; . ./.env; set +a; export LOCAL_UID=$(id -u) LOCAL_GID=$(id -g)
printf '\n%s\n' '$ D=$VDR_DOMAIN …'
D=$VDR_DOMAIN
W=../evidencias/work
PUB="--cacert certs/ca.crt --resolve $D:8443:127.0.0.1"
PSQL="docker compose exec -T postgres psql -U $POSTGRES_USER -d $POSTGRES_DB"
TOOLS() { docker compose --progress quiet --profile tools run --rm -T tools "$@"; }
ESCRIBIR() { local cid=$1 sec=$2 p12=$3 did=$4; shift 4; TOOLS write --admin-url "https://$D:8443" --client-id "$cid" --secret "$sec" --p12 "/certs/$p12.p12" --p12-pass "$P12_PASS" --ca /certs/ca.crt --did "$did" "$@"; }
CURLM() { local who=$1; shift; curl -s --cacert certs/ca.crt --resolve $D:9443:127.0.0.1 --cert certs/$who.crt --key certs/$who.key "$@"; }
TOKEN() { CURLM $1 -X POST https://$D:9443/admin/v1/oauth/token -d grant_type=client_credentials -d client_id=$1 -d client_secret=$2 | jq -r .access_token; }
API() { local who=$1 tk=$2 m=$3 p=$4; shift 4; CURLM $who -X $m "https://$D:9443/admin/v1$p" -H "Authorization: Bearer $tk" "$@"; }
DESAFIO() { API $1 $2 POST /challenges -H "Content-Type: application/json" -d "{\"did\":\"$3\",\"purpose\":\"$4\"}"; }
DOCHASH() { jq -cS . $W/$1 | tr -d '\n' | sha256sum | awk '{print "sha256:"$1}'; }
PRUEBA() { local p; p=$(jq -cn --arg a "$AUD" --arg c "$NONCE" --arg d "$1" --arg h "$3" --arg p "$2" '{aud:$a,challenge:$c,did:$d,docHash:$h,purpose:$p}' | jq -cS .); TOOLS sign --key $4 --kid "$1#key-1" --message "$p" | tail -1; }
PUTDOC() { jq -n --arg c "$CHID" --slurpfile d $W/$4 --arg p "$5" '{challengeId:$c,document:$d[0],proof:$p}' | API avance-issuer $TK PUT "/documents/$1" -w "\nHTTP %{http_code}\n" -H "If-Match: $2" -H "Idempotency-Key: $3" -H "Content-Type: application/json" -d @-; }
NUEVO() { local R_; R_=$(DESAFIO avance-issuer $TK $1 $2); CHID=$(echo "$R_" | jq -r .challengeId); NONCE=$(echo "$R_" | jq -r .nonce); AUD=$(echo "$R_" | jq -r .audience); echo "$R_" | jq -c .; }
printf '\n%s\n' '$ curl -s $PUB https://$D:8443/health'
curl -s $PUB https://$D:8443/health
printf '\n%s\n' '$ ID=ciclo-008-$(date +%s); DID=did:web:$D:entidades:$ID; echo "DID=$DID"'
ID=ciclo-008-$(date +%s); DID=did:web:$D:entidades:$ID; echo "DID=$DID"
echo; echo "══════ LABORATORIO A · Criterio 1: precondiciones ══════"
printf '\n%s\n' '$ echo "select client_id, enabled from entity_accounts order by 1" | $PSQL'
echo "select client_id, enabled from entity_accounts order by 1" | $PSQL
printf '\n%s\n' '$ TK=$(TOKEN avance-issuer $CLIENT_SECRET_AVANCE); TKL=$(TOKEN lab-operator $CLIENT_SECRET_LAB); echo "desafíos guardados ANTES: $($PSQL -tAc "select count(*) from challenges")"'
TK=$(TOKEN avance-issuer $CLIENT_SECRET_AVANCE); TKL=$(TOKEN lab-operator $CLIENT_SECRET_LAB); echo "desafíos guardados ANTES: $($PSQL -tAc "select count(*) from challenges")"
printf '\n%s\n' '$ CASO() { printf '"'"'%-30s -> '"'"' "$1"; shift; "$@" | jq -c '"'"'[.error, .details]'"'"'; } …'
CASO() { printf '%-30s -> ' "$1"; shift; "$@" | jq -c '[.error, .details]'; }
CASO "1 espacio ajeno"          DESAFIO lab-operator    $TKL did:web:$D:entidades:avance UPDATE
CASO "2 ruta no reservada"      DESAFIO avance-issuer   $TK  did:web:$D:ciudadanos:ana CREATE
CASO "3 dominio ajeno"          DESAFIO avance-issuer   $TK  did:web:otro.dominio:entidades:x CREATE
CASO "4 DID inválido"           DESAFIO avance-issuer   $TK  "did:web:$D:entidades:.." CREATE
CASO "5 certificado de otro"    DESAFIO lab-operator    $TK  $DID CREATE
printf '\n%s\n' '$ echo "update entity_accounts set enabled=false where client_id='"'"'lab-operator'"'"'" | $PSQL …'
echo "update entity_accounts set enabled=false where client_id='lab-operator'" | $PSQL
CASO "6 cuenta deshabilitada"   DESAFIO lab-operator    $TKL did:web:$D:lab:x CREATE
echo "update entity_accounts set enabled=true where client_id='lab-operator'" | $PSQL
printf '\n%s\n' '$ curl -s -o /dev/null -w "7 sin certificado de cliente -> HTTP %{http_code}\n" --cacert certs/ca.crt --resolve $D:9443:127.0.0.1 -X POST https://$D:9443/admin/v1/challenges -H "Authorization: Bearer $TK" -d '"'"'{}'"'"' '
curl -s -o /dev/null -w "7 sin certificado de cliente -> HTTP %{http_code}\n" --cacert certs/ca.crt --resolve $D:9443:127.0.0.1 -X POST https://$D:9443/admin/v1/challenges -H "Authorization: Bearer $TK" -d '{}' 
printf '\n%s\n' '$ echo "desafíos guardados DESPUÉS: $($PSQL -tAc "select count(*) from challenges")" …'
echo "desafíos guardados DESPUÉS: $($PSQL -tAc "select count(*) from challenges")"
echo "select to_char(at,'HH24:MI:SS') hora, actor, action, detail from audit_log where action='CHALLENGE_DENIED' order by id desc limit 6" | $PSQL
echo; echo "══════ LABORATORIO B · Criterio 2: el desafío (nace el DID de prueba) ══════"
printf '\n%s\n' '$ NUEVO $DID CREATE'
NUEVO $DID CREATE
printf '\n%s\n' '$ echo "select left(id::text,8) as id, purpose, audience, to_char(expires_at,'"'"'HH24:MI:SS'"'"') as vence, used_at is not null as usado from challenges where id = '"'"'$CHID'"'"'" | $PSQL'
echo "select left(id::text,8) as id, purpose, audience, to_char(expires_at,'HH24:MI:SS') as vence, used_at is not null as usado from challenges where id = '$CHID'" | $PSQL
printf '\n%s\n' '$ echo "select action, detail from audit_log where did = '"'"'$DID'"'"' order by id" | $PSQL'
echo "select action, detail from audit_log where did = '$DID' order by id" | $PSQL
printf '\n%s\n' '$ TOOLS keygen --out c008-a.json; TOOLS build-doc --did $DID --key c008-a.json --out c008-v1.json >/dev/null; H=$(DOCHASH c008-v1.json); echo "huella del documento (calculada a mano): $H"'
TOOLS keygen --out c008-a.json; TOOLS build-doc --did $DID --key c008-a.json --out c008-v1.json >/dev/null; H=$(DOCHASH c008-v1.json); echo "huella del documento (calculada a mano): $H"
printf '\n%s\n' '$ J=$(PRUEBA $DID CREATE $H c008-a.json) …'
J=$(PRUEBA $DID CREATE $H c008-a.json)
dec() { local s=$(echo "$1" | tr "_-" "/+"); while [ $(( ${#s} % 4 )) -ne 0 ]; do s="$s="; done; echo "$s" | base64 -d 2>/dev/null; }
echo "contenido firmado:"; dec $(echo $J | cut -d. -f2) | jq .
printf '\n%s\n' '$ PUTDOC $DID 0 k1-$ID c008-v1.json "$J"'
PUTDOC $DID 0 k1-$ID c008-v1.json "$J"
printf '\n%s\n' '$ echo "select left(id::text,8) as id, purpose, used_at is not null as usado from challenges where id = '"'"'$CHID'"'"'" | $PSQL'
echo "select left(id::text,8) as id, purpose, used_at is not null as usado from challenges where id = '$CHID'" | $PSQL
printf '\n%s\n' '$ PUTDOC $DID 0 k2-$ID c008-v1.json "$J"'
PUTDOC $DID 0 k2-$ID c008-v1.json "$J"
printf '\n%s\n' '$ NUEVO $DID UPDATE >/dev/null …'
NUEVO $DID UPDATE >/dev/null
H1=$(DOCHASH c008-v1.json); J=$(PRUEBA $DID DEACTIVATE $H1 c008-a.json)
jq -n --arg c "$CHID" --arg p "$J" '{challengeId:$c,proof:$p}' | API avance-issuer $TK POST "/documents/$DID/deactivate" -w "\nHTTP %{http_code}\n" -H "If-Match: 1" -H "Idempotency-Key: k3-$ID" -H "Content-Type: application/json" -d @-
printf '\n%s\n' '$ TOOLS keygen --out c008-b.json >/dev/null; TOOLS build-doc --did $DID --key c008-b.json --out c008-v2.json >/dev/null …'
TOOLS keygen --out c008-b.json >/dev/null; TOOLS build-doc --did $DID --key c008-b.json --out c008-v2.json >/dev/null
NUEVO $DID UPDATE >/dev/null; H2=$(DOCHASH c008-v2.json); J=$(PRUEBA $DID UPDATE $H2 c008-a.json)
echo "update challenges set expires_at = now() - interval '1 second' where id = '$CHID'" | $PSQL
PUTDOC $DID 1 k4-$ID c008-v2.json "$J"
printf '\n%s\n' '$ NUEVO $DID UPDATE >/dev/null; AUDREAL=$AUD; AUD="vdr:otro.dominio:did-operation" …'
NUEVO $DID UPDATE >/dev/null; AUDREAL=$AUD; AUD="vdr:otro.dominio:did-operation"
J=$(PRUEBA $DID UPDATE $H2 c008-a.json); AUD=$AUDREAL
PUTDOC $DID 1 k5-$ID c008-v2.json "$J"
echo; echo "══════ LABORATORIO C · Criterio 3: contenido del documento ══════"
printf '\n%s\n' '$ DIDN=did:web:$D:entidades:$ID-neg; TOOLS build-doc --did $DIDN --key c008-a.json --out c008-neg.json >/dev/null; echo "base de los intentos: $DIDN"'
DIDN=did:web:$D:entidades:$ID-neg; TOOLS build-doc --did $DIDN --key c008-a.json --out c008-neg.json >/dev/null; echo "base de los intentos: $DIDN"
printf '\n%s\n' '$ jq '"'"'.verificationMethod[0].privateKeyMultibase="z1234"'"'"' $W/c008-neg.json > $W/c008-n1.json; ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer $DIDN --purpose CREATE --expected 0 --key c008-a.json --doc c008-n1.json 2>&1 | tail -n +4'
jq '.verificationMethod[0].privateKeyMultibase="z1234"' $W/c008-neg.json > $W/c008-n1.json; ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer $DIDN --purpose CREATE --expected 0 --key c008-a.json --doc c008-n1.json 2>&1 | tail -n +4
printf '\n%s\n' '$ jq '"'"'.id="did:web:'"'"'$D'"'"':entidades:otra-entidad"'"'"' $W/c008-neg.json > $W/c008-n2.json; ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer $DIDN --purpose CREATE --expected 0 --key c008-a.json --doc c008-n2.json 2>&1 | tail -n +4'
jq '.id="did:web:'$D':entidades:otra-entidad"' $W/c008-neg.json > $W/c008-n2.json; ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer $DIDN --purpose CREATE --expected 0 --key c008-a.json --doc c008-n2.json 2>&1 | tail -n +4
printf '\n%s\n' '$ jq '"'"'.controller="did:web:otra.entidad"'"'"' $W/c008-neg.json > $W/c008-n3.json; ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer $DIDN --purpose CREATE --expected 0 --key c008-a.json --doc c008-n3.json 2>&1 | tail -n +4'
jq '.controller="did:web:otra.entidad"' $W/c008-neg.json > $W/c008-n3.json; ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer $DIDN --purpose CREATE --expected 0 --key c008-a.json --doc c008-n3.json 2>&1 | tail -n +4
printf '\n%s\n' '$ jq '"'"'.credentialSubject={"name":"Ana Pérez"}'"'"' $W/c008-neg.json > $W/c008-n4.json; ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer $DIDN --purpose CREATE --expected 0 --key c008-a.json --doc c008-n4.json 2>&1 | tail -n +4'
jq '.credentialSubject={"name":"Ana Pérez"}' $W/c008-neg.json > $W/c008-n4.json; ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer $DIDN --purpose CREATE --expected 0 --key c008-a.json --doc c008-n4.json 2>&1 | tail -n +4
printf '\n%s\n' '$ echo "documentos registrados con ese identificador: $($PSQL -tAc "select count(*) from did_documents where did = '"'"'$DIDN'"'"'")"'
echo "documentos registrados con ese identificador: $($PSQL -tAc "select count(*) from did_documents where did = '$DIDN'")"
echo; echo "══════ LABORATORIO D · Criterio 4: versión esperada e idempotencia (rotación) ══════"
printf '\n%s\n' '$ API avance-issuer $TK PUT "/documents/$DID" -w "\nHTTP %{http_code}\n" -H "Idempotency-Key: z-$ID" -H "Content-Type: application/json" -d '"'"'{}'"'"' '
API avance-issuer $TK PUT "/documents/$DID" -w "\nHTTP %{http_code}\n" -H "Idempotency-Key: z-$ID" -H "Content-Type: application/json" -d '{}' 
printf '\n%s\n' '$ NUEVO $DID UPDATE >/dev/null; J=$(PRUEBA $DID UPDATE $H2 c008-a.json) …'
NUEVO $DID UPDATE >/dev/null; J=$(PRUEBA $DID UPDATE $H2 c008-a.json)
PUTDOC $DID 5 k6-$ID c008-v2.json "$J"
printf '\n%s\n' '$ NUEVO $DID UPDATE >/dev/null; J=$(PRUEBA $DID UPDATE $H2 c008-a.json) …'
NUEVO $DID UPDATE >/dev/null; J=$(PRUEBA $DID UPDATE $H2 c008-a.json)
OUT=$(PUTDOC $DID 1 k7-$ID c008-v2.json "$J"); echo "$OUT"; OP2=$(echo "$OUT" | head -1 | jq -r .operationId); CH2=$CHID; J2=$J
printf '\n%s\n' '$ CHID=$CH2; PUTDOC $DID 1 k7-$ID c008-v2.json "$J2"'
CHID=$CH2; PUTDOC $DID 1 k7-$ID c008-v2.json "$J2"
printf '\n%s\n' '$ TOOLS build-doc --did $DID --key c008-b.json --service-url "https://$D/issuer" --out c008-v3.json >/dev/null; H3=$(DOCHASH c008-v3.json) …'
TOOLS build-doc --did $DID --key c008-b.json --service-url "https://$D/issuer" --out c008-v3.json >/dev/null; H3=$(DOCHASH c008-v3.json)
NUEVO $DID UPDATE >/dev/null; J=$(PRUEBA $DID UPDATE $H3 c008-b.json)
PUTDOC $DID 2 k7-$ID c008-v3.json "$J"
printf '\n%s\n' '$ echo "select version, operation, actor from did_document_versions where did = '"'"'$DID'"'"' order by version" | $PSQL'
echo "select version, operation, actor from did_document_versions where did = '$DID' order by version" | $PSQL
printf '\n%s\n' '$ NUEVO $DID UPDATE >/dev/null; J=$(PRUEBA $DID UPDATE $H3 c008-a.json) …'
NUEVO $DID UPDATE >/dev/null; J=$(PRUEBA $DID UPDATE $H3 c008-a.json)
PUTDOC $DID 2 k8-$ID c008-v3.json "$J"
echo; echo "══════ LABORATORIO E · Criterio 5: confirmación ══════"
printf '\n%s\n' '$ echo "operación de la actualización (v2): $OP2"; API avance-issuer $TK GET "/operations/$OP2" | jq .'
echo "operación de la actualización (v2): $OP2"; API avance-issuer $TK GET "/operations/$OP2" | jq .
printf '\n%s\n' '$ echo "hash de la evidencia      : $(API avance-issuer $TK GET /operations/$OP2 | jq -r .hash)" …'
echo "hash de la evidencia      : $(API avance-issuer $TK GET /operations/$OP2 | jq -r .hash)"
echo "hash del documento público: $(curl -s $PUB https://$D:8443/entidades/$ID/did.json | sha256sum | awk '{print "sha256:"$1}')"
curl -s -D - -o /dev/null $PUB https://$D:8443/entidades/$ID/did.json | grep -iE "^(HTTP|etag|x-content-hash)"
printf '\n%s\n' '$ curl -s $PUB https://$D:8443/entidades/$ID/did.json | jq -c '"'"'.verificationMethod[0].publicKeyMultibase'"'"'; echo "(clave b de la v2: $(jq -r .publicKeyMultibase $W/c008-b.json))"'
curl -s $PUB https://$D:8443/entidades/$ID/did.json | jq -c '.verificationMethod[0].publicKeyMultibase'; echo "(clave b de la v2: $(jq -r .publicKeyMultibase $W/c008-b.json))"
echo; echo "══════ LABORATORIO F · Criterio 6: pendiente y reconciliación (segunda instancia temporal) ══════"
printf '\n%s\n' '$ CD=$W/confirm008; rm -rf $CD; mkdir -p $CD …'
CD=$W/confirm008; rm -rf $CD; mkdir -p $CD
openssl req -new -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -keyout $CD/c.key -subj "/CN=confirm.lab" -out $CD/c.csr 2>/dev/null
printf "subjectAltName=DNS:confirm.lab\n" > $CD/san.cnf
openssl x509 -req -in $CD/c.csr -CA certs/ca.crt -CAkey certs/ca.key -set_serial 0x1008 -days 30 -extfile $CD/san.cnf -out $CD/c.crt 2>/dev/null
cat > $CD/c.conf <<'EOF'
server {
  listen 443 ssl;
  server_name confirm.lab;
  ssl_certificate     /etc/nginx/c.crt;
  ssl_certificate_key /etc/nginx/c.key;
  location / { proxy_pass http://vdr:8080; }
}
EOF
ls $CD
printf '\n%s\n' '$ docker rm -f vdr-lento >/dev/null 2>&1 …'
docker rm -f vdr-lento >/dev/null 2>&1
docker run -d --name vdr-lento --network vdr-ssi_default -p 127.0.0.1:18081:8081 \
  -e VDR_ENABLED=true -e VDR_DOMAIN=$D -e PUBLIC_BASE_URL=https://$D \
  -e CONFIRM_BASE_URL=https://confirm.lab -e CONFIRM_CA_PEM=/certs/ca.crt -e CONFIRM_TIMEOUT_MS=2000 \
  -e DB_URL=jdbc:postgresql://postgres:5432/$POSTGRES_DB -e DB_USER=$POSTGRES_USER -e DB_PASSWORD=$POSTGRES_PASSWORD \
  -e VDR_JWT_SECRET=$VDR_JWT_SECRET -e VDR_CLIENTS="$VDR_CLIENTS" -e VDR_REQUIRE_MTLS=false \
  -v "$PWD/certs/ca.crt:/certs/ca.crt:ro" vdr-ssi/vdr-service:local >/dev/null
until curl -s -o /dev/null http://127.0.0.1:18081/admin/v1/oauth/token; do sleep 1; done
docker ps --filter name=vdr-lento --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
printf '\n%s\n' '$ API2() { local m=$1 p=$2; shift 2; curl -s -X $m "http://127.0.0.1:18081/admin/v1$p" -H "Authorization: Bearer $TK2" "$@"; } …'
API2() { local m=$1 p=$2; shift 2; curl -s -X $m "http://127.0.0.1:18081/admin/v1$p" -H "Authorization: Bearer $TK2" "$@"; }
TK2=$(curl -s http://127.0.0.1:18081/admin/v1/oauth/token -d grant_type=client_credentials -d client_id=avance-issuer -d client_secret=$CLIENT_SECRET_AVANCE | jq -r .access_token)
DIDP=did:web:$D:entidades:pend-$ID
TOOLS keygen --out c008-p.json >/dev/null; TOOLS build-doc --did $DIDP --key c008-p.json --out c008-pdoc.json >/dev/null; HP=$(DOCHASH c008-pdoc.json)
R_=$(API2 POST /challenges -H "Content-Type: application/json" -d "{\"did\":\"$DIDP\",\"purpose\":\"CREATE\"}")
CHID=$(echo "$R_" | jq -r .challengeId); NONCE=$(echo "$R_" | jq -r .nonce); AUD=$(echo "$R_" | jq -r .audience)
JP=$(PRUEBA $DIDP CREATE $HP c008-p.json)
jq -n --arg c "$CHID" --slurpfile d $W/c008-pdoc.json --arg p "$JP" '{challengeId:$c,document:$d[0],proof:$p}' | API2 PUT "/documents/$DIDP" -w "\nHTTP %{http_code}\n" -H "If-Match: 0" -H "Idempotency-Key: kp-$ID" -H "Content-Type: application/json" -d @-
printf '\n%s\n' '$ OPP=$(echo "select id from operations where did='"'"'$DIDP'"'"'" | $PSQL -tA) …'
OPP=$(echo "select id from operations where did='$DIDP'" | $PSQL -tA)
echo "select purpose, status, version, confirmed_at is not null as confirmada from operations where id='$OPP'" | $PSQL
echo "select action, detail from audit_log where did='$DIDP' order by id" | $PSQL
printf '\n%s\n' '$ API2 POST "/operations/$OPP/reconcile" | jq -c '"'"'{status, version, hash: .hash[0:20]}'"'"' '
API2 POST "/operations/$OPP/reconcile" | jq -c '{status, version, hash: .hash[0:20]}' 
printf '\n%s\n' '$ docker rm -f confirm-lab >/dev/null 2>&1 …'
docker rm -f confirm-lab >/dev/null 2>&1
docker run -d --name confirm-lab --network vdr-ssi_default --network-alias confirm.lab -v "$PWD/$CD/c.conf:/etc/nginx/conf.d/default.conf:ro" -v "$PWD/$CD/c.crt:/etc/nginx/c.crt:ro" -v "$PWD/$CD/c.key:/etc/nginx/c.key:ro" nginx:1.27-alpine >/dev/null; sleep 2
docker ps --filter name=confirm-lab --format "table {{.Names}}\t{{.Status}}"
printf '\n%s\n' '$ sleep 15   # la JVM recuerda ~10 s que el nombre no existía …'
sleep 15   # la JVM recuerda ~10 s que el nombre no existía
API2 POST "/operations/$OPP/reconcile" | jq -c '{status, version, hash: .hash[0:20]}'
echo "select action, detail from audit_log where did='$DIDP' order by id" | $PSQL
echo; echo "══════ LABORATORIO G · Criterio 7: traza (actualización y baja) ══════"
printf '\n%s\n' '$ ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer $DID --purpose UPDATE --expected 2 --key c008-b.json --doc c008-v3.json 2>&1 | grep -E "^\[4/4\]|\"status\"|\"version\""'
ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer $DID --purpose UPDATE --expected 2 --key c008-b.json --doc c008-v3.json 2>&1 | grep -E "^\[4/4\]|\"status\"|\"version\""
printf '\n%s\n' '$ ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer $DID --purpose DEACTIVATE --expected 3 --key c008-b.json 2>&1 | grep -E "^\[4/4\]|\"status\"|\"purpose\"|\"version\""'
ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer $DID --purpose DEACTIVATE --expected 3 --key c008-b.json 2>&1 | grep -E "^\[4/4\]|\"status\"|\"purpose\"|\"version\""
printf '\n%s\n' '$ TK=$(TOKEN avance-issuer $CLIENT_SECRET_AVANCE); API avance-issuer $TK GET "/documents/$DID/versions" | jq -r '"'"'.[] | "v\(.version)  \(.operation)  \(.createdAt[11:23])  \(.actor)  \(.hash[0:26])…"'"'"' '
TK=$(TOKEN avance-issuer $CLIENT_SECRET_AVANCE); API avance-issuer $TK GET "/documents/$DID/versions" | jq -r '.[] | "v\(.version)  \(.operation)  \(.createdAt[11:23])  \(.actor)  \(.hash[0:26])…"' 
printf '\n%s\n' '$ API avance-issuer $TK GET "/documents/$DID/versions/2" | jq '"'"'{version, operation, hash, claveEnElDocumento: (.document | fromjson | .verificationMethod[0].publicKeyMultibase)}'"'"' '
API avance-issuer $TK GET "/documents/$DID/versions/2" | jq '{version, operation, hash, claveEnElDocumento: (.document | fromjson | .verificationMethod[0].publicKeyMultibase)}' 
printf '\n%s\n' '$ API avance-issuer $TK GET "/documents/$DID/versions" > $W/c008-versiones.json …'
API avance-issuer $TK GET "/documents/$DID/versions" > $W/c008-versiones.json
for n in 1 2 3 4; do T=$(jq -r ".[$((n-1))].createdAt" $W/c008-versiones.json); printf "en el instante de v$n (%s) -> " "${T:11:12}"; API avance-issuer $TK GET "/documents/$DID/state?at=$T" | jq -c '{status, version}'; done
printf '\n%s\n' '$ API avance-issuer $TK GET "/documents/$DID/state?at=2000-01-01T00:00:00Z" -w "\nHTTP %{http_code}\n"'
API avance-issuer $TK GET "/documents/$DID/state?at=2000-01-01T00:00:00Z" -w "\nHTTP %{http_code}\n"
printf '\n%s\n' '$ curl -s -o /dev/null -w "URL pública del DID desactivado -> HTTP %{http_code}\n" $PUB https://$D:8443/entidades/$ID/did.json'
curl -s -o /dev/null -w "URL pública del DID desactivado -> HTTP %{http_code}\n" $PUB https://$D:8443/entidades/$ID/did.json
printf '\n%s\n' '$ ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer $DID --purpose CREATE --expected 0 --key c008-a.json --doc c008-v1.json 2>&1'
ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer $DID --purpose CREATE --expected 0 --key c008-a.json --doc c008-v1.json 2>&1
printf '\n%s\n' '$ echo "update did_document_versions set hash='"'"'x'"'"' where did='"'"'$DID'"'"'" | $PSQL 2>&1 | head -2 …'
echo "update did_document_versions set hash='x' where did='$DID'" | $PSQL 2>&1 | head -2
echo "delete from audit_log where did='$DID'" | $PSQL 2>&1 | head -2
echo; echo "══════ LABORATORIO H · Pruebas automáticas ══════"
printf '\n%s\n' '$ cd /home/geovani/Descargas/generic/bitacora/new_proyect'
cd /home/geovani/Descargas/generic/bitacora/new_proyect
printf '\n%s\n' '$ TEST_DB_URL=jdbc:postgresql://127.0.0.1:55432/wallet_test ./gradlew :vdr-service:test --tests '"'"'*Erso008*'"'"' --rerun-tasks -q 2>&1 | tail -3; echo '"'"'(sin salida = todas pasaron)'"'"''
TEST_DB_URL=jdbc:postgresql://127.0.0.1:55432/wallet_test ./gradlew :vdr-service:test --tests '*Erso008*' --rerun-tasks -q 2>&1 | tail -3; echo '(sin salida = todas pasaron)'
printf '\n%s\n' '$ python3 - <<'"'"'EOF'"'"' …'
python3 - <<'EOF'
import re, glob, html
f = glob.glob('vdr-service/build/test-results/test/*Erso008*.xml')[0]
s = open(f, encoding='utf-8').read()
tot = re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)"', s).groups()
print(f"resultado: {tot[0]} pruebas, {tot[1]} omitidas, {tot[2]} fallos\n")
for m in re.finditer(r'<testcase name="([^"]+)"[^>]*?(/>|>(.*?)</testcase>)', s, re.S):
    name = html.unescape(m.group(1)).removesuffix("()")
    estado = "FALLA" if "<failure" in (m.group(3) or "") else "PASA "
    print(f"  [{estado}] {name}")
EOF
echo; echo "══════ LIMPIEZA ══════"
printf '\n%s\n' '$ cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy; docker rm -f vdr-lento confirm-lab; rm -rf ../evidencias/work/confirm008'
cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy; docker rm -f vdr-lento confirm-lab; rm -rf ../evidencias/work/confirm008
echo; echo "Fin de los laboratorios de la ERSo 8."
