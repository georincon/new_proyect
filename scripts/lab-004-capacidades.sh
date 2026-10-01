#!/usr/bin/env bash
# Laboratorio ERSo 2026-004 (capacidades del proceso): opcional, aislado, adaptador y canales.
# Requisito: proyecto levantado (docs/MARCO-CONCEPTUAL.md, sección 2b, pasos A–D). No modifica datos.
cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy
set -a; . ./.env; set +a
export LOCAL_UID=$(id -u) LOCAL_GID=$(id -g)
D=$VDR_DOMAIN

echo "=== 1. ¿Qué puertos publica cada contenedor hacia TU máquina? ==="
docker compose ps --format 'table {{.Service}}\t{{.Ports}}' | sed 's/\[::\]:[0-9]*->[0-9]*\/tcp,\? \?//g'

echo; echo "=== 2. ¿Algún puerto interno (aplicación o base de datos) escucha en tu máquina? ==="
ss -ltn | grep -E ":(8080|8081|5432|8090|8100) " || echo "ninguno: 8080, 8081, 5432, 8090 y 8100 NO están publicados"

echo; echo "=== 3. Intentar entrar DIRECTO a la aplicación, saltándose nginx ==="
curl -s -m 3 http://127.0.0.1:8080/health; echo "resultado de curl: código de salida $? (7 = no se pudo conectar)"

echo; echo "=== 4. Lo mismo, pero DESDE DENTRO de la red de Docker (como lo hace nginx) ==="
docker compose exec -T nginx wget -qO- http://vdr:8080/health; echo

echo; echo "=== 5. OPCIONAL: una segunda copia del servicio con la extensión APAGADA (puerto 18080) ==="
docker run -d --rm --name vdr-off -e VDR_ENABLED=false -p 18080:8080 vdr-ssi/vdr-service:local >/dev/null
sleep 6
echo "/health            : $(curl -s -m 3 http://127.0.0.1:18080/health)"
echo "/base/ping         : $(curl -s -m 3 http://127.0.0.1:18080/base/ping)"
echo "did.json de avance : HTTP $(curl -s -m 3 -o /dev/null -w '%{http_code}' http://127.0.0.1:18080/entidades/avance/did.json)"
docker logs vdr-off 2>&1 | grep -E "VDR APAGADO|HABILITADO" | sed 's/^[0-9:. ]*//'
docker rm -f vdr-off >/dev/null

echo; echo "=== 6. EL ADAPTADOR: cómo un DID se convierte en la 'forma' del registro (did:web) ==="
docker compose --progress quiet --profile tools run --rm -T tools did --domain $D --namespace entidades/avance

echo; echo "=== 7. Los DOS CANALES de nginx: lectura pública y escritura protegida ==="
echo "lectura  (8443) GET did.json   : HTTP $(curl -s -o /dev/null -w '%{http_code}' --cacert certs/ca.crt --resolve $D:8443:127.0.0.1 https://$D:8443/entidades/avance/did.json)"
echo "lectura  (8443) PUT did.json   : HTTP $(curl -s -o /dev/null -w '%{http_code}' -X PUT --cacert certs/ca.crt --resolve $D:8443:127.0.0.1 https://$D:8443/entidades/avance/did.json)"
echo "lectura  (8443) /admin/v1/...  : HTTP $(curl -s -o /dev/null -w '%{http_code}' --cacert certs/ca.crt --resolve $D:8443:127.0.0.1 https://$D:8443/admin/v1/audit)"
echo "escritura(9443) sin certificado: HTTP $(curl -s -o /dev/null -w '%{http_code}' --cacert certs/ca.crt --resolve $D:9443:127.0.0.1 https://$D:9443/admin/v1/audit)"
