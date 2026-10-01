#!/usr/bin/env bash
# ERSo 2026-005 — todos los laboratorios en una sola sesión (A a G). Genera la MISMA sesión que el informe final.
# Requisitos: proyecto levantado (docker compose up -d en deploy/), espacio lab/* reservado y base desechable de pruebas vdr-test-pg (laboratorio G).
# Cada ejecución publica UN DID de prueba nuevo en lab/prueba-005-<hora>. Uso:  bash scripts/lab-005-completo.sh

echo; echo "══════ PREPARACIÓN ══════"
printf '\n%s\n' '$ cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy'
cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy
printf '\n%s\n' '$ set -a; . ./.env; set +a; export LOCAL_UID=$(id -u) LOCAL_GID=$(id -g)'
set -a; . ./.env; set +a; export LOCAL_UID=$(id -u) LOCAL_GID=$(id -g)
printf '\n%s\n' '$ D=$VDR_DOMAIN …'
D=$VDR_DOMAIN
PUB="--cacert certs/ca.crt --resolve $D:8443:127.0.0.1"
W=../evidencias/work
PSQL="docker compose exec -T postgres psql -U $POSTGRES_USER -d $POSTGRES_DB"
TOOLS() { docker compose --progress quiet --profile tools run --rm -T tools "$@"; }
LABOP() { TOOLS admin --admin-url "https://$D:8443" --client-id lab-operator --secret "$CLIENT_SECRET_LAB" --p12 /certs/lab-operator.p12 --p12-pass "$P12_PASS" --ca /certs/ca.crt "$@"; }
PUBLICAR() { local did=$1; shift; TOOLS write --admin-url "https://$D:8443" --client-id lab-operator --secret "$CLIENT_SECRET_LAB" --p12 /certs/lab-operator.p12 --p12-pass "$P12_PASS" --ca /certs/ca.crt --did "$did" "$@"; }
printf '\n%s\n' '$ docker compose ps --format '"'"'table {{.Service}}\t{{.Status}}'"'"''
docker compose ps --format 'table {{.Service}}\t{{.Status}}'
printf '\n%s\n' '$ curl -s $PUB https://$D:8443/health'
curl -s $PUB https://$D:8443/health
printf '\n%s\n' '$ echo "select path, owner_client_id from namespaces where path like '"'"'lab/%'"'"' order by path" | $PSQL'
echo "select path, owner_client_id from namespaces where path like 'lab/%' order by path" | $PSQL
printf '\n%s\n' '$ sed -n '"'"'/listen 443/,/location \/ { return 404; }/p'"'"' nginx/nginx.conf'
sed -n '/listen 443/,/location \/ { return 404; }/p' nginx/nginx.conf
printf '\n%s\n' '$ sed -n '"'"'/get("{path...}")/,/^            }$/p'"'"' ../vdr-service/src/main/kotlin/co/org/avance/ssi/vdr/Server.kt'
sed -n '/get("{path...}")/,/^            }$/p' ../vdr-service/src/main/kotlin/co/org/avance/ssi/vdr/Server.kt
printf '\n%s\n' '$ ID=prueba-005-$(date +%s); DID=did:web:$D:lab:$ID; echo "ID=$ID"; echo "DID=$DID"'
ID=prueba-005-$(date +%s); DID=did:web:$D:lab:$ID; echo "ID=$ID"; echo "DID=$DID"
echo; echo "══════ LABORATORIO A · Paso 1: generar el did:web ══════"
printf '\n%s\n' '$ TOOLS did --domain $D --namespace lab/$ID'
TOOLS did --domain $D --namespace lab/$ID
printf '\n%s\n' '$ TOOLS did --domain "$D%3A8443" --namespace lab/laboratorio'
TOOLS did --domain "$D%3A8443" --namespace lab/laboratorio
printf '\n%s\n' '$ TOOLS did --domain CIVICA-Desarrollo.AVANCE.org.co --namespace lab/laboratorio'
TOOLS did --domain CIVICA-Desarrollo.AVANCE.org.co --namespace lab/laboratorio
printf '\n%s\n' '$ TOOLS did --domain $D --namespace "lab/../etc" 2>&1 | head -2'
TOOLS did --domain $D --namespace "lab/../etc" 2>&1 | head -2
printf '\n%s\n' '$ TOOLS did --domain $D --namespace "lab/mi ruta" 2>&1 | head -2'
TOOLS did --domain $D --namespace "lab/mi ruta" 2>&1 | head -2
echo; echo "══════ LABORATORIO B · Paso 2: construir el documento ══════"
printf '\n%s\n' '$ TOOLS keygen --out c005-clave.json'
TOOLS keygen --out c005-clave.json
printf '\n%s\n' '$ jq -r '"'"'keys[]'"'"' $W/c005-clave.json'
jq -r 'keys[]' $W/c005-clave.json
printf '\n%s\n' '$ TOOLS build-doc --did $DID --key c005-clave.json --out c005-doc.json >/dev/null; jq . $W/c005-doc.json'
TOOLS build-doc --did $DID --key c005-clave.json --out c005-doc.json >/dev/null; jq . $W/c005-doc.json
printf '\n%s\n' '$ jq -c '"'"'keys'"'"' $W/c005-doc.json'
jq -c 'keys' $W/c005-doc.json
printf '\n%s\n' '$ python3 - <<'"'"'EOF'"'"' …'
python3 - <<'EOF'
import json
A = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
k = json.load(open("../evidencias/work/c005-clave.json"))["publicKeyMultibase"]
n = 0
for ch in k[1:]: n = n * 58 + A.index(ch)
b = n.to_bytes(35, "big")
print("texto            :", k[:14] + "…   (la 'z' inicial significa base58btc)")
print("bytes decodificados:", len(b))
print("prefijo (tipo)   :", b[:2].hex(), " <- 0x8024 = clave pública P-256")
print("punto comprimido :", b[2:3].hex(), "+ 32 bytes de la coordenada x   (02 o 03 = paridad de y)")
EOF
printf '\n%s\n' '$ grep -c -iE "privateKey|pkcs8" $W/c005-doc.json'
grep -c -iE "privateKey|pkcs8" $W/c005-doc.json
echo; echo "══════ LABORATORIO C · Criterio 1: se sirve por HTTPS en la URL calculada ══════"
printf '\n%s\n' '$ PUBLICAR $DID --purpose CREATE --expected 0 --key c005-clave.json --doc c005-doc.json'
PUBLICAR $DID --purpose CREATE --expected 0 --key c005-clave.json --doc c005-doc.json
printf '\n%s\n' '$ curl -s -D - -o /dev/null $PUB https://$D:8443/lab/$ID/did.json'
curl -s -D - -o /dev/null $PUB https://$D:8443/lab/$ID/did.json
printf '\n%s\n' '$ curl -s $PUB https://$D:8443/lab/$ID/did.json | jq .'
curl -s $PUB https://$D:8443/lab/$ID/did.json | jq .
printf '\n%s\n' '$ curl -s -I $PUB https://$D:8443/lab/$ID/did.json | head -3'
curl -s -I $PUB https://$D:8443/lab/$ID/did.json | head -3
printf '\n%s\n' '$ curl -s -o /dev/null -w "otra ruta (index.html)   -> HTTP %{http_code}\n" $PUB https://$D:8443/lab/$ID/index.html'
curl -s -o /dev/null -w "otra ruta (index.html)   -> HTTP %{http_code}\n" $PUB https://$D:8443/lab/$ID/index.html
printf '\n%s\n' '$ curl -s -o /dev/null -w "POST sobre did.json     -> HTTP %{http_code}\n" -X POST $PUB https://$D:8443/lab/$ID/did.json'
curl -s -o /dev/null -w "POST sobre did.json     -> HTTP %{http_code}\n" -X POST $PUB https://$D:8443/lab/$ID/did.json
printf '\n%s\n' '$ curl -s -o /dev/null -w "DID que no existe       -> HTTP %{http_code}\n" $PUB https://$D:8443/lab/no-existe-$ID/did.json'
curl -s -o /dev/null -w "DID que no existe       -> HTTP %{http_code}\n" $PUB https://$D:8443/lab/no-existe-$ID/did.json
printf '\n%s\n' '$ TOOLS resolve --did $DID --ca /certs/ca.crt | grep -E "^(resolutionMetadata|documentMetadata)" | cut -c1-210'
TOOLS resolve --did $DID --ca /certs/ca.crt | grep -E "^(resolutionMetadata|documentMetadata)" | cut -c1-210
echo; echo "══════ LABORATORIO D · Criterio 2: clave pública correcta y hash ══════"
printf '\n%s\n' '$ curl -s $PUB https://$D:8443/lab/$ID/did.json | sha256sum | awk '"'"'{print "hash del contenido servido      : sha256:"$1}'"'"''
curl -s $PUB https://$D:8443/lab/$ID/did.json | sha256sum | awk '{print "hash del contenido servido      : sha256:"$1}'
printf '\n%s\n' '$ curl -s -D - -o /dev/null $PUB https://$D:8443/lab/$ID/did.json | grep -i "x-content-hash" | sed '"'"'s/^[^:]*: /hash que informa el servidor     : /'"'"''
curl -s -D - -o /dev/null $PUB https://$D:8443/lab/$ID/did.json | grep -i "x-content-hash" | sed 's/^[^:]*: /hash que informa el servidor     : /'
printf '\n%s\n' '$ LABOP --path "/admin/v1/documents/$DID/versions" 2>&1 | sed -n '"'"'/^\[/,$p'"'"' | jq -r '"'"'"hash que registró el registro   : " + .[-1].hash'"'"''
LABOP --path "/admin/v1/documents/$DID/versions" 2>&1 | sed -n '/^\[/,$p' | jq -r '"hash que registró el registro   : " + .[-1].hash'
printf '\n%s\n' '$ GEN=$(jq -r .publicKeyMultibase $W/c005-clave.json); echo "clave pública generada   : $GEN"'
GEN=$(jq -r .publicKeyMultibase $W/c005-clave.json); echo "clave pública generada   : $GEN"
printf '\n%s\n' '$ PUBK=$(curl -s $PUB https://$D:8443/lab/$ID/did.json | jq -r '"'"'.verificationMethod[0].publicKeyMultibase'"'"'); echo "clave pública publicada  : $PUBK"'
PUBK=$(curl -s $PUB https://$D:8443/lab/$ID/did.json | jq -r '.verificationMethod[0].publicKeyMultibase'); echo "clave pública publicada  : $PUBK"
printf '\n%s\n' '$ [ "$GEN" = "$PUBK" ] && echo "RESULTADO: las dos claves son IDÉNTICAS" || echo "RESULTADO: NO coinciden"'
[ "$GEN" = "$PUBK" ] && echo "RESULTADO: las dos claves son IDÉNTICAS" || echo "RESULTADO: NO coinciden"
printf '\n%s\n' '$ JWS=$(TOOLS sign --key c005-clave.json --kid "$DID#key-1" --message "prueba-005" | tail -1); echo "${JWS:0:60}…"'
JWS=$(TOOLS sign --key c005-clave.json --kid "$DID#key-1" --message "prueba-005" | tail -1); echo "${JWS:0:60}…"
printf '\n%s\n' '$ TOOLS verify --jws "$JWS" --purpose assertionMethod --did $DID --ca /certs/ca.crt'
TOOLS verify --jws "$JWS" --purpose assertionMethod --did $DID --ca /certs/ca.crt
printf '\n%s\n' '$ curl -s $PUB https://$D:8443/lab/$ID/did.json | sed '"'"'s/zDn/zDm/'"'"' | sha256sum | awk '"'"'{print "hash si se altera un carácter : sha256:"$1}'"'"''
curl -s $PUB https://$D:8443/lab/$ID/did.json | sed 's/zDn/zDm/' | sha256sum | awk '{print "hash si se altera un carácter : sha256:"$1}'
echo; echo "══════ LABORATORIO E · Criterio 3: sin claves privadas ni datos civiles ══════"
printf '\n%s\n' '$ curl -s $PUB https://$D:8443/lab/$ID/did.json | jq -c '"'"'keys'"'"''
curl -s $PUB https://$D:8443/lab/$ID/did.json | jq -c 'keys'
printf '\n%s\n' '$ curl -s $PUB https://$D:8443/lab/$ID/did.json | grep -c -iE '"'"'private|secret|credentialSubject|birthDate|"d":'"'"''
curl -s $PUB https://$D:8443/lab/$ID/did.json | grep -c -iE 'private|secret|credentialSubject|birthDate|"d":'
printf '\n%s\n' '$ PRIV=$(jq -r .privateKeyPkcs8 $W/c005-clave.json); curl -s $PUB https://$D:8443/lab/$ID/did.json | grep -c -F "$PRIV"'
PRIV=$(jq -r .privateKeyPkcs8 $W/c005-clave.json); curl -s $PUB https://$D:8443/lab/$ID/did.json | grep -c -F "$PRIV"
printf '\n%s\n' '$ DIDNEG=did:web:$D:lab:$ID-neg; TOOLS build-doc --did $DIDNEG --key c005-clave.json --out c005-neg.json >/dev/null; echo "documento base para los intentos: $DIDNEG"'
DIDNEG=did:web:$D:lab:$ID-neg; TOOLS build-doc --did $DIDNEG --key c005-clave.json --out c005-neg.json >/dev/null; echo "documento base para los intentos: $DIDNEG"
printf '\n%s\n' '$ jq '"'"'.verificationMethod[0].privateKeyMultibase="z1234"'"'"' $W/c005-neg.json > $W/c005-neg1.json; PUBLICAR $DIDNEG --purpose CREATE --expected 0 --key c005-clave.json --doc c005-neg1.json 2>&1 | tail -n +4'
jq '.verificationMethod[0].privateKeyMultibase="z1234"' $W/c005-neg.json > $W/c005-neg1.json; PUBLICAR $DIDNEG --purpose CREATE --expected 0 --key c005-clave.json --doc c005-neg1.json 2>&1 | tail -n +4
printf '\n%s\n' '$ jq '"'"'.credentialSubject={"name":"Ana Pérez"}'"'"' $W/c005-neg.json > $W/c005-neg2.json; PUBLICAR $DIDNEG --purpose CREATE --expected 0 --key c005-clave.json --doc c005-neg2.json 2>&1 | tail -n +4'
jq '.credentialSubject={"name":"Ana Pérez"}' $W/c005-neg.json > $W/c005-neg2.json; PUBLICAR $DIDNEG --purpose CREATE --expected 0 --key c005-clave.json --doc c005-neg2.json 2>&1 | tail -n +4
printf '\n%s\n' '$ jq '"'"'.birthDate="1990-01-01"'"'"' $W/c005-neg.json > $W/c005-neg3.json; PUBLICAR $DIDNEG --purpose CREATE --expected 0 --key c005-clave.json --doc c005-neg3.json 2>&1 | tail -n +4'
jq '.birthDate="1990-01-01"' $W/c005-neg.json > $W/c005-neg3.json; PUBLICAR $DIDNEG --purpose CREATE --expected 0 --key c005-clave.json --doc c005-neg3.json 2>&1 | tail -n +4
printf '\n%s\n' '$ jq '"'"'.extension="valor propietario"'"'"' $W/c005-neg.json > $W/c005-neg4.json; PUBLICAR $DIDNEG --purpose CREATE --expected 0 --key c005-clave.json --doc c005-neg4.json 2>&1 | tail -n +4'
jq '.extension="valor propietario"' $W/c005-neg.json > $W/c005-neg4.json; PUBLICAR $DIDNEG --purpose CREATE --expected 0 --key c005-clave.json --doc c005-neg4.json 2>&1 | tail -n +4
printf '\n%s\n' '$ jq '"'"'.service=[{"id":"#s","type":"X","serviceEndpoint":"http://inseguro.example"}]'"'"' $W/c005-neg.json > $W/c005-neg5.json; PUBLICAR $DIDNEG --purpose CREATE --expected 0 --key c005-clave.json --doc c005-neg5.json 2>&1 | tail -n +4'
jq '.service=[{"id":"#s","type":"X","serviceEndpoint":"http://inseguro.example"}]' $W/c005-neg.json > $W/c005-neg5.json; PUBLICAR $DIDNEG --purpose CREATE --expected 0 --key c005-clave.json --doc c005-neg5.json 2>&1 | tail -n +4
printf '\n%s\n' '$ echo "DID registrados con ese identificador: $($PSQL -tAc "select count(*) from did_documents where did = '"'"'$DIDNEG'"'"'")"; curl -s -o /dev/null -w "lectura pública de $ID-neg -> HTTP %{http_code}\n" $PUB https://$D:8443/lab/$ID-neg/did.json'
echo "DID registrados con ese identificador: $($PSQL -tAc "select count(*) from did_documents where did = '$DIDNEG'")"; curl -s -o /dev/null -w "lectura pública de $ID-neg -> HTTP %{http_code}\n" $PUB https://$D:8443/lab/$ID-neg/did.json
echo; echo "══════ LABORATORIO F · Criterio 4: id desajustado y camino base apagado ══════"
printf '\n%s\n' '$ DIDOTRO=did:web:$D:lab:$ID-otro; TOOLS build-doc --did $DIDOTRO --key c005-clave.json --out c005-otro.json >/dev/null; jq -r .id $W/c005-otro.json'
DIDOTRO=did:web:$D:lab:$ID-otro; TOOLS build-doc --did $DIDOTRO --key c005-clave.json --out c005-otro.json >/dev/null; jq -r .id $W/c005-otro.json
printf '\n%s\n' '$ PUBLICAR $DIDNEG --purpose CREATE --expected 0 --key c005-clave.json --doc c005-otro.json 2>&1 | tail -n +4'
PUBLICAR $DIDNEG --purpose CREATE --expected 0 --key c005-clave.json --doc c005-otro.json 2>&1 | tail -n +4
printf '\n%s\n' '$ echo "DID registrados bajo $ID-neg: $($PSQL -tAc "select count(*) from did_documents where did like '"'"'%$ID-neg'"'"'")"; curl -s -o /dev/null -w "lectura pública -> HTTP %{http_code}\n" $PUB https://$D:8443/lab/$ID-neg/did.json'
echo "DID registrados bajo $ID-neg: $($PSQL -tAc "select count(*) from did_documents where did like '%$ID-neg'")"; curl -s -o /dev/null -w "lectura pública -> HTTP %{http_code}\n" $PUB https://$D:8443/lab/$ID-neg/did.json
printf '\n%s\n' '$ PUBLICAR did:web:$D:ciudadanos:ana --purpose CREATE --expected 0 --key c005-clave.json --doc c005-doc.json 2>&1 | tail -3'
PUBLICAR did:web:$D:ciudadanos:ana --purpose CREATE --expected 0 --key c005-clave.json --doc c005-doc.json 2>&1 | tail -3
printf '\n%s\n' '$ PUBLICAR did:web:otro.example:lab:x --purpose CREATE --expected 0 --key c005-clave.json --doc c005-doc.json 2>&1 | tail -3'
PUBLICAR did:web:otro.example:lab:x --purpose CREATE --expected 0 --key c005-clave.json --doc c005-doc.json 2>&1 | tail -3
printf '\n%s\n' '$ PUBLICAR "did:web:$D:lab:.." --purpose CREATE --expected 0 --key c005-clave.json --doc c005-doc.json 2>&1 | tail -3'
PUBLICAR "did:web:$D:lab:.." --purpose CREATE --expected 0 --key c005-clave.json --doc c005-doc.json 2>&1 | tail -3
printf '\n%s\n' '$ echo "DID de ciudadanos publicados (ruta /ciudadanos/): $($PSQL -tAc "select count(*) from did_documents where did like '"'"'%:ciudadanos:%'"'"'")"'
echo "DID de ciudadanos publicados (ruta /ciudadanos/): $($PSQL -tAc "select count(*) from did_documents where did like '%:ciudadanos:%'")"
printf '\n%s\n' '$ docker rm -f vdr-off >/dev/null 2>&1; docker run -d --name vdr-off -p 18080:8080 vdr-ssi/vdr-service:local >/dev/null; sleep 6'
docker rm -f vdr-off >/dev/null 2>&1; docker run -d --name vdr-off -p 18080:8080 vdr-ssi/vdr-service:local >/dev/null; sleep 6
printf '\n%s\n' '$ curl -s http://127.0.0.1:18080/health'
curl -s http://127.0.0.1:18080/health
printf '\n%s\n' '$ curl -s http://127.0.0.1:18080/base/ping'
curl -s http://127.0.0.1:18080/base/ping
printf '\n%s\n' '$ curl -s -o /dev/null -w "did.json con la extensión apagada -> HTTP %{http_code}\n" http://127.0.0.1:18080/lab/laboratorio/did.json'
curl -s -o /dev/null -w "did.json con la extensión apagada -> HTTP %{http_code}\n" http://127.0.0.1:18080/lab/laboratorio/did.json
printf '\n%s\n' '$ docker rm -f vdr-off'
docker rm -f vdr-off
echo; echo "══════ LABORATORIO G · Pruebas automáticas ══════"
printf '\n%s\n' '$ cd /home/geovani/Descargas/generic/bitacora/new_proyect'
cd /home/geovani/Descargas/generic/bitacora/new_proyect
printf '\n%s\n' '$ TEST_DB_URL=jdbc:postgresql://127.0.0.1:55432/wallet_test ./gradlew :vdr-service:test --tests '"'"'*Erso005*'"'"' --rerun-tasks -q 2>&1 | tail -3; echo "(sin salida = todas pasaron)"'
TEST_DB_URL=jdbc:postgresql://127.0.0.1:55432/wallet_test ./gradlew :vdr-service:test --tests '*Erso005*' --rerun-tasks -q 2>&1 | tail -3; echo "(sin salida = todas pasaron)"
printf '\n%s\n' '$ python3 - <<'"'"'EOF'"'"' …'
python3 - <<'EOF'
import re, glob, html
f = glob.glob('vdr-service/build/test-results/test/*Erso005*.xml')[0]
s = open(f, encoding='utf-8').read()
tot = re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)"', s).groups()
print(f"resultado: {tot[0]} pruebas, {tot[1]} omitidas, {tot[2]} fallos\n")
for m in re.finditer(r'<testcase name="([^"]+)"[^>]*?(/>|>(.*?)</testcase>)', s, re.S):
    name = html.unescape(m.group(1)).removesuffix("()")
    estado = "FALLA" if "<failure" in (m.group(3) or "") else "PASA "
    print(f"  [{estado}] {name}")
EOF
echo; echo "Fin de los laboratorios de la ERSo 005."
