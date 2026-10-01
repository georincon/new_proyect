#!/usr/bin/env bash
# ERSo 2026-007 — todos los laboratorios en una sola sesión. Genera la MISMA sesión que el informe final.
# Requisitos: proyecto levantado (docker compose up -d en deploy/) y base desechable de pruebas vdr-test-pg (laboratorio de pruebas automáticas).
# Uso:  bash scripts/lab-007-completo.sh

echo; echo "══════ MONTAJE (consultas de lectura) ══════"
printf '\n%s\n' '$ cd /home/geovani/Descargas/generic/bitacora/new_proyect'
cd /home/geovani/Descargas/generic/bitacora/new_proyect
printf '\n%s\n' '$ sed -n '"'"'/^include(/,/^)/p'"'"' settings.gradle.kts'
sed -n '/^include(/,/^)/p' settings.gradle.kts
printf '\n%s\n' '$ grep -n '"'"'project(":did-resolver")\|project(":vdr-service")'"'"' */build.gradle.kts | cut -c1-120'
grep -n 'project(":did-resolver")\|project(":vdr-service")' */build.gradle.kts | cut -c1-120
printf '\n%s\n' '$ sed -n '"'"'/^  tools:/,/depends_on:/p'"'"' deploy/docker-compose.yml; sed -n '"'"'/depends_on:/,+1p'"'"' deploy/docker-compose.yml | sed -n '"'"'1,2p'"'"' >/dev/null'
sed -n '/^  tools:/,/depends_on:/p' deploy/docker-compose.yml; sed -n '/depends_on:/,+1p' deploy/docker-compose.yml | sed -n '1,2p' >/dev/null
printf '\n%s\n' '$ wc -l did-resolver/src/main/kotlin/co/org/avance/ssi/resolver/*.kt'
wc -l did-resolver/src/main/kotlin/co/org/avance/ssi/resolver/*.kt
echo; echo "══════ PREPARACIÓN ══════"
printf '\n%s\n' '$ cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy'
cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy
printf '\n%s\n' '$ set -a; . ./.env; set +a; export LOCAL_UID=$(id -u) LOCAL_GID=$(id -g)'
set -a; . ./.env; set +a; export LOCAL_UID=$(id -u) LOCAL_GID=$(id -g)
printf '\n%s\n' '$ D=$VDR_DOMAIN …'
D=$VDR_DOMAIN
W=../evidencias/work
PUB="--cacert certs/ca.crt --resolve $D:8443:127.0.0.1"
TOOLS() { docker compose --progress quiet --profile tools run --rm -T tools "$@"; }
ESCRIBIR() { local cid=$1 sec=$2 p12=$3 did=$4; shift 4; TOOLS write --admin-url "https://$D:8443" --client-id "$cid" --secret "$sec" --p12 "/certs/$p12.p12" --p12-pass "$P12_PASS" --ca /certs/ca.crt --did "$did" "$@"; }
VERIFICAR() { local jws=$1; shift; TOOLS verify --jws "$jws" --ca /certs/ca.crt "$@"; }
RESOLVER() { TOOLS resolve --did "$1" --ca /certs/ca.crt 2>&1 | grep -E "^(resolutionMetadata|documentMetadata)" | cut -c1-260; }
printf '\n%s\n' '$ curl -s $PUB https://$D:8443/health'
curl -s $PUB https://$D:8443/health
printf '\n%s\n' '$ ID=inst-007-$(date +%s); DID=did:web:$D:entidades:$ID; echo "DID=$DID"'
ID=inst-007-$(date +%s); DID=did:web:$D:entidades:$ID; echo "DID=$DID"
echo; echo "══════ LABORATORIO A · Pasos 1 y 2: resolver ══════"
printf '\n%s\n' '$ TOOLS keygen --out c007-clave.json; TOOLS build-doc --did $DID --key c007-clave.json --service-url "https://$D/issuer" --out c007-doc.json >/dev/null; echo "(documento de prueba construido: $(jq -c keys $W/c007-doc.json))"'
TOOLS keygen --out c007-clave.json; TOOLS build-doc --did $DID --key c007-clave.json --service-url "https://$D/issuer" --out c007-doc.json >/dev/null; echo "(documento de prueba construido: $(jq -c keys $W/c007-doc.json))"
printf '\n%s\n' '$ ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer $DID --purpose CREATE --expected 0 --key c007-clave.json --doc c007-doc.json 2>&1 | grep -E "^\[4/4\]|\"status\"|\"version\""'
ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer $DID --purpose CREATE --expected 0 --key c007-clave.json --doc c007-doc.json 2>&1 | grep -E "^\[4/4\]|\"status\"|\"version\""
printf '\n%s\n' '$ TOOLS resolve --did $DID --ca /certs/ca.crt'
TOOLS resolve --did $DID --ca /certs/ca.crt
printf '\n%s\n' '$ TOOLS did --domain $D --namespace entidades/$ID; curl -s -o /dev/null -w "GET directo a esa dirección (puerto 8443 del laboratorio) -> HTTP %{http_code}\n" $PUB "https://$D:8443/entidades/$ID/did.json"'
TOOLS did --domain $D --namespace entidades/$ID; curl -s -o /dev/null -w "GET directo a esa dirección (puerto 8443 del laboratorio) -> HTTP %{http_code}\n" $PUB "https://$D:8443/entidades/$ID/did.json"
echo; echo "══════ LABORATORIO B · Paso 3: la prueba y criterio 1 ══════"
printf '\n%s\n' '$ JWS=$(TOOLS sign --key c007-clave.json --kid "$DID#key-1" --message "solicitud-de-presentacion" | tail -1); echo "${JWS:0:70}…"'
JWS=$(TOOLS sign --key c007-clave.json --kid "$DID#key-1" --message "solicitud-de-presentacion" | tail -1); echo "${JWS:0:70}…"
printf '\n%s\n' '$ dec() { local s=$(echo "$1" | tr "_-" "/+"); while [ $(( ${#s} % 4 )) -ne 0 ]; do s="$s="; done; echo "$s" | base64 -d 2>/dev/null; }; echo "cabecera : $(dec $(echo $JWS | cut -d. -f1))"; echo "contenido: $(dec $(echo $JWS | cut -d. -f2))"; echo "firma    : $(echo $JWS | cut -d. -f3 | cut -c1-40)… (64 bytes, ES256)"'
dec() { local s=$(echo "$1" | tr "_-" "/+"); while [ $(( ${#s} % 4 )) -ne 0 ]; do s="$s="; done; echo "$s" | base64 -d 2>/dev/null; }; echo "cabecera : $(dec $(echo $JWS | cut -d. -f1))"; echo "contenido: $(dec $(echo $JWS | cut -d. -f2))"; echo "firma    : $(echo $JWS | cut -d. -f3 | cut -c1-40)… (64 bytes, ES256)"
printf '\n%s\n' '$ VERIFICAR "$JWS" --purpose assertionMethod --did $DID'
VERIFICAR "$JWS" --purpose assertionMethod --did $DID
printf '\n%s\n' '$ VERIFICAR "$JWS" --purpose authentication --did $DID'
VERIFICAR "$JWS" --purpose authentication --did $DID
printf '\n%s\n' '$ JWSA=$(TOOLS sign --key avance.json --kid "did:web:$D:entidades:avance#key-1" --message "solicitud-de-presentacion" | tail -1); VERIFICAR "$JWSA" --purpose assertionMethod --did did:web:$D:entidades:avance'
JWSA=$(TOOLS sign --key avance.json --kid "did:web:$D:entidades:avance#key-1" --message "solicitud-de-presentacion" | tail -1); VERIFICAR "$JWSA" --purpose assertionMethod --did did:web:$D:entidades:avance
echo; echo "══════ LABORATORIO C · Criterio 2 ══════"
printf '\n%s\n' '$ TOOLS keygen --out c007-ajena.json; JWSX=$(TOOLS sign --key c007-ajena.json --kid "$DID#key-1" --message "solicitud-de-presentacion" | tail -1); VERIFICAR "$JWSX" --purpose assertionMethod --did $DID'
TOOLS keygen --out c007-ajena.json; JWSX=$(TOOLS sign --key c007-ajena.json --kid "$DID#key-1" --message "solicitud-de-presentacion" | tail -1); VERIFICAR "$JWSX" --purpose assertionMethod --did $DID
printf '\n%s\n' '$ H=$(echo $JWS | cut -d. -f1); S=$(echo $JWS | cut -d. -f3); P2=$(printf "solicitud-ALTERADA" | base64 -w0 | tr "+/" "-_" | tr -d "="); VERIFICAR "$H.$P2.$S" --purpose assertionMethod --did $DID'
H=$(echo $JWS | cut -d. -f1); S=$(echo $JWS | cut -d. -f3); P2=$(printf "solicitud-ALTERADA" | base64 -w0 | tr "+/" "-_" | tr -d "="); VERIFICAR "$H.$P2.$S" --purpose assertionMethod --did $DID
printf '\n%s\n' '$ JWSK=$(TOOLS sign --key c007-clave.json --kid "$DID#key-9" --message "x" | tail -1); VERIFICAR "$JWSK" --purpose assertionMethod --did $DID'
JWSK=$(TOOLS sign --key c007-clave.json --kid "$DID#key-9" --message "x" | tail -1); VERIFICAR "$JWSK" --purpose assertionMethod --did $DID
printf '\n%s\n' '$ VERIFICAR "$JWS" --purpose assertionMethod --did did:web:$D:entidades:avance'
VERIFICAR "$JWS" --purpose assertionMethod --did did:web:$D:entidades:avance
printf '\n%s\n' '$ VERIFICAR "esto-no-es-una-prueba" --purpose assertionMethod; VERIFICAR "a.b.c" --purpose assertionMethod'
VERIFICAR "esto-no-es-una-prueba" --purpose assertionMethod; VERIFICAR "a.b.c" --purpose assertionMethod
printf '\n%s\n' '$ TOOLS build-doc --did $DID --key c007-ajena.json --service-url "https://$D/issuer" --out c007-doc-b.json >/dev/null; ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer $DID --purpose UPDATE --expected 1 --key c007-clave.json --doc c007-doc-b.json 2>&1 | grep -E "^\[4/4\]|\"status\"|\"version\""'
TOOLS build-doc --did $DID --key c007-ajena.json --service-url "https://$D/issuer" --out c007-doc-b.json >/dev/null; ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer $DID --purpose UPDATE --expected 1 --key c007-clave.json --doc c007-doc-b.json 2>&1 | grep -E "^\[4/4\]|\"status\"|\"version\""
printf '\n%s\n' '$ echo "-- la firma de ANTES (clave vieja) --"; VERIFICAR "$JWS" --purpose assertionMethod --did $DID; echo "-- una firma NUEVA (clave nueva) --"; JWSN=$(TOOLS sign --key c007-ajena.json --kid "$DID#key-1" --message "solicitud-de-presentacion" | tail -1); VERIFICAR "$JWSN" --purpose assertionMethod --did $DID'
echo "-- la firma de ANTES (clave vieja) --"; VERIFICAR "$JWS" --purpose assertionMethod --did $DID; echo "-- una firma NUEVA (clave nueva) --"; JWSN=$(TOOLS sign --key c007-ajena.json --kid "$DID#key-1" --message "solicitud-de-presentacion" | tail -1); VERIFICAR "$JWSN" --purpose assertionMethod --did $DID
echo; echo "══════ LABORATORIO D · Criterio 3 (incluye el servidor malicioso de laboratorio) ══════"
printf '\n%s\n' '$ DIDF=did:web:$D:entidades:fantasma-$ID; RESOLVER $DIDF; JWSF=$(TOOLS sign --key c007-clave.json --kid "$DIDF#key-1" --message "x" | tail -1); VERIFICAR "$JWSF" --purpose assertionMethod'
DIDF=did:web:$D:entidades:fantasma-$ID; RESOLVER $DIDF; JWSF=$(TOOLS sign --key c007-clave.json --kid "$DIDF#key-1" --message "x" | tail -1); VERIFICAR "$JWSF" --purpose assertionMethod
printf '\n%s\n' '$ DIDB=did:web:$D:entidades:baja-$ID; TOOLS keygen --out c007-baja.json >/dev/null; TOOLS build-doc --did $DIDB --key c007-baja.json --out c007-baja-doc.json >/dev/null; ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer $DIDB --purpose CREATE --expected 0 --key c007-baja.json --doc c007-baja-doc.json 2>&1 | grep -E "^\[4/4\]|\"status\""; echo "-- ahora se desactiva --"; ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer $DIDB --purpose DEACTIVATE --expected 1 --key c007-baja.json 2>&1 | grep -E "^\[4/4\]|\"status\"|\"purpose\""'
DIDB=did:web:$D:entidades:baja-$ID; TOOLS keygen --out c007-baja.json >/dev/null; TOOLS build-doc --did $DIDB --key c007-baja.json --out c007-baja-doc.json >/dev/null; ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer $DIDB --purpose CREATE --expected 0 --key c007-baja.json --doc c007-baja-doc.json 2>&1 | grep -E "^\[4/4\]|\"status\""; echo "-- ahora se desactiva --"; ESCRIBIR avance-issuer $CLIENT_SECRET_AVANCE avance-issuer $DIDB --purpose DEACTIVATE --expected 1 --key c007-baja.json 2>&1 | grep -E "^\[4/4\]|\"status\"|\"purpose\""
printf '\n%s\n' '$ RESOLVER $DIDB; JWSB=$(TOOLS sign --key c007-baja.json --kid "$DIDB#key-1" --message "x" | tail -1); VERIFICAR "$JWSB" --purpose assertionMethod'
RESOLVER $DIDB; JWSB=$(TOOLS sign --key c007-baja.json --kid "$DIDB#key-1" --message "x" | tail -1); VERIFICAR "$JWSB" --purpose assertionMethod
printf '\n%s\n' '$ EV=$W/evil007; rm -rf $EV; mkdir -p $EV/www/casos;  …'
EV=$W/evil007; rm -rf $EV; mkdir -p $EV/www/casos; 
openssl req -new -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -keyout $EV/evil.key -subj "/CN=evil.lab" -out $EV/evil.csr 2>/dev/null
printf "subjectAltName=DNS:evil.lab\n" > $EV/san.cnf
openssl x509 -req -in $EV/evil.csr -CA certs/ca.crt -CAkey certs/ca.key -set_serial 0x1007 -days 30 -extfile $EV/san.cnf -out $EV/evil.crt 2>&1 | grep -v "^$"
openssl x509 -in $EV/evil.crt -noout -subject -issuer -ext subjectAltName
printf '\n%s\n' '$ EV=$W/evil007 …'
EV=$W/evil007
TOOLS build-doc --did did:web:evil.lab:casos:bueno --key c007-clave.json --out evil007/base.json >/dev/null
mk() { mkdir -p $EV/www/casos/$1; sed "s/casos:bueno/casos:$1/g" $EV/base.json | jq "$2" > $EV/www/casos/$1/did.json; }
mk bueno '.'
mk idajeno '.id="did:web:evil.lab:casos:otro"'
mk sinctx 'del(."@context")'
mk privada '.verificationMethod[0].privateKeyMultibase="z1234"'
mk dangling '.assertionMethod=["did:web:evil.lab:casos:dangling#key-9"]'
mk tipo '.verificationMethod[0].publicKeyMultibase="zDnaeNoEsUnaClaveValida"'
mk soloauth '.assertionMethod=[] | .id="did:web:evil.lab:casos:soloauth" | .verificationMethod[0].id="did:web:evil.lab:casos:soloauth#key-1" | .verificationMethod[0].controller="did:web:evil.lab:casos:soloauth" | .authentication=["did:web:evil.lab:casos:soloauth#key-1"]'
mk grande '.relleno=("x"*200000)'
mk html '.'
mkdir -p $EV/www/casos/notjson; echo "esto no es json" > $EV/www/casos/notjson/did.json
ls -1 $EV/www/casos | tr "\n" " "; echo; wc -c $EV/www/casos/bueno/did.json $EV/www/casos/grande/did.json
printf '\n%s\n' '$ EV=$W/evil007 …'
EV=$W/evil007
cat > $EV/evil.conf <<'EOF'
server {
  listen 443 ssl;
  server_name evil.lab;
  ssl_certificate     /etc/nginx/evil.crt;
  ssl_certificate_key /etc/nginx/evil.key;
  root /usr/share/nginx/html;
  location = /casos/redir/did.json { return 302 https://evil.lab/casos/bueno/did.json; }
  location = /casos/gone/did.json  { return 410; }
  location = /casos/html/did.json  { types { } default_type text/html; }
}
EOF
docker rm -f evil-lab >/dev/null 2>&1
docker run -d --name evil-lab --network vdr-ssi_default --network-alias evil.lab -v "$(cd $EV && pwd)/evil.conf:/etc/nginx/conf.d/default.conf:ro" -v "$(cd $EV && pwd)/evil.crt:/etc/nginx/evil.crt:ro" -v "$(cd $EV && pwd)/evil.key:/etc/nginx/evil.key:ro" -v "$(cd $EV && pwd)/www:/usr/share/nginx/html:ro" nginx:1.27-alpine >/dev/null; sleep 2; docker ps --filter name=evil-lab --format "table {{.Names}}\t{{.Status}}"
printf '\n%s\n' '$ for c in bueno idajeno sinctx privada dangling tipo notjson html grande redir gone noexiste; do printf "== %-9s " $c; TOOLS resolve --did did:web:evil.lab:casos:$c --ca /certs/ca.crt 2>&1 | grep "^resolutionMetadata" | sed -E "s/.*error=([^,]*),.*violations=(\[.*\])\).*/error=\1  violaciones=\2/"; done'
for c in bueno idajeno sinctx privada dangling tipo notjson html grande redir gone noexiste; do printf "== %-9s " $c; TOOLS resolve --did did:web:evil.lab:casos:$c --ca /certs/ca.crt 2>&1 | grep "^resolutionMetadata" | sed -E "s/.*error=([^,]*),.*violations=(\[.*\])\).*/error=\1  violaciones=\2/"; done
printf '\n%s\n' '$ JWSS=$(TOOLS sign --key c007-clave.json --kid "did:web:evil.lab:casos:soloauth#key-1" --message "x" | tail -1); echo "-- como firma de ASERCIÓN (assertionMethod) --"; VERIFICAR "$JWSS" --purpose assertionMethod; echo "-- como AUTENTICACIÓN (authentication) --"; VERIFICAR "$JWSS" --purpose authentication'
JWSS=$(TOOLS sign --key c007-clave.json --kid "did:web:evil.lab:casos:soloauth#key-1" --message "x" | tail -1); echo "-- como firma de ASERCIÓN (assertionMethod) --"; VERIFICAR "$JWSS" --purpose assertionMethod; echo "-- como AUTENTICACIÓN (authentication) --"; VERIFICAR "$JWSS" --purpose authentication
echo; echo "══════ LABORATORIO E · Criterio 4 ══════"
printf '\n%s\n' '$ cd /home/geovani/Descargas/generic/bitacora/new_proyect'
cd /home/geovani/Descargas/generic/bitacora/new_proyect
printf '\n%s\n' '$ sed -n '"'"'/^\/\*\* Contrato del consumidor/,/^}/p'"'"' did-resolver/src/main/kotlin/co/org/avance/ssi/resolver/Resolver.kt'
sed -n '/^\/\*\* Contrato del consumidor/,/^}/p' did-resolver/src/main/kotlin/co/org/avance/ssi/resolver/Resolver.kt
printf '\n%s\n' '$ sed -n '"'"'/^\/\/ ERSo/,/^}/p'"'"' did-resolver/build.gradle.kts'
sed -n '/^\/\/ ERSo/,/^}/p' did-resolver/build.gradle.kts
printf '\n%s\n' '$ ./gradlew :did-resolver:dependencies --configuration runtimeClasspath -q 2>&1 | grep -E '"'"'project :|vdr'"'"' | sort -u'
./gradlew :did-resolver:dependencies --configuration runtimeClasspath -q 2>&1 | grep -E 'project :|vdr' | sort -u
printf '\n%s\n' '$ grep -rnE '"'"'\.(put|post|delete|patch|submitForm)\(|HttpMethod\.(Put|Post|Delete|Patch)'"'"' did-resolver/src/main || echo '"'"'(ninguna coincidencia: el módulo no contiene llamadas de escritura)'"'"'; grep -rn '"'"'http.get'"'"' did-resolver/src/main | cut -c1-150'
grep -rnE '\.(put|post|delete|patch|submitForm)\(|HttpMethod\.(Put|Post|Delete|Patch)' did-resolver/src/main || echo '(ninguna coincidencia: el módulo no contiene llamadas de escritura)'; grep -rn 'http.get' did-resolver/src/main | cut -c1-150
printf '\n%s\n' '$ echo "métodos HTTP que el servidor malicioso recibió del consumidor (todos los casos de §D):"; docker logs evil-lab 2>&1 | grep -E "^[0-9.]+ - - \[" | awk '"'"'{print $6}'"'"' | tr -d '"'"'"'"'"' | sort | uniq -c; echo; docker logs evil-lab 2>&1 | grep -E "^[0-9.]+ - - \[" | sed -E '"'"'s/^[0-9.]+ - - \[[^]]*\] //'"'"' | cut -c1-70 | head -20'
echo "métodos HTTP que el servidor malicioso recibió del consumidor (todos los casos de §D):"; docker logs evil-lab 2>&1 | grep -E "^[0-9.]+ - - \[" | awk '{print $6}' | tr -d '"' | sort | uniq -c; echo; docker logs evil-lab 2>&1 | grep -E "^[0-9.]+ - - \[" | sed -E 's/^[0-9.]+ - - \[[^]]*\] //' | cut -c1-70 | head -20
echo; echo "══════ LABORATORIO G · Pruebas automáticas ══════"
printf '\n%s\n' '$ ./gradlew :did-resolver:test --rerun-tasks -q 2>&1 | tail -3; echo '"'"'(sin salida = todas pasaron)'"'"''
./gradlew :did-resolver:test --rerun-tasks -q 2>&1 | tail -3; echo '(sin salida = todas pasaron)'
printf '\n%s\n' '$ TEST_DB_URL=jdbc:postgresql://127.0.0.1:55432/wallet_test ./gradlew :vdr-service:test --tests '"'"'*ConsumerInterop*'"'"' --rerun-tasks -q 2>&1 | tail -3; echo '"'"'(sin salida = todas pasaron)'"'"''
TEST_DB_URL=jdbc:postgresql://127.0.0.1:55432/wallet_test ./gradlew :vdr-service:test --tests '*ConsumerInterop*' --rerun-tasks -q 2>&1 | tail -3; echo '(sin salida = todas pasaron)'
printf '\n%s\n' '$ python3 - <<'"'"'EOF'"'"' …'
python3 - <<'EOF'
import re, glob, html
for pat in ('did-resolver/build/test-results/test/*ResolverTest*.xml', 'vdr-service/build/test-results/test/*ConsumerInterop*.xml'):
    f = glob.glob(pat)[0]
    s = open(f, encoding='utf-8').read()
    tot = re.search(r'name="([^"]+)" tests="(\d+)" skipped="(\d+)" failures="(\d+)"', s).groups()
    print(f"{tot[0].split('.')[-1]}: {tot[1]} pruebas, {tot[2]} omitidas, {tot[3]} fallos")
    for m in re.finditer(r'<testcase name="([^"]+)"[^>]*?(/>|>(.*?)</testcase>)', s, re.S):
        name = html.unescape(m.group(1)).removesuffix("()")
        estado = "FALLA" if "<failure" in (m.group(3) or "") else "PASA "
        print(f"  [{estado}] {name}")
    print()
EOF
echo; echo "══════ LIMPIEZA ══════"
printf '\n%s\n' '$ cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy; docker rm -f evil-lab; rm -rf ../evidencias/work/evil007'
cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy; docker rm -f evil-lab; rm -rf ../evidencias/work/evil007
echo; echo "Fin de los laboratorios de la ERSo 7."
