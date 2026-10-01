#!/usr/bin/env bash
# Laboratorio ERSo 2026-004, criterio 3: el camino base opera con la opción apagada (regresión).
# Requisitos: proyecto levantado (docs/MARCO-CONCEPTUAL.md, sección 2b, pasos A–D) y Docker.
# Crea y borra un contenedor temporal "vdr-off" (sin base de datos); no modifica el proyecto levantado.
cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy
set -a; . ./.env; set +a
D=$VDR_DOMAIN
IMG=vdr-ssi/vdr-service:local

echo "=== 1. POR DEFECTO: se arranca el servicio SIN definir VDR_ENABLED, y SIN base de datos al lado ==="
docker rm -f vdr-off >/dev/null 2>&1
docker run -d --name vdr-off -p 18080:8080 $IMG >/dev/null
sleep 6
docker logs vdr-off 2>&1 | sed 's/^[0-9:. ]*//' | grep -E "VDR|Responding" 

echo; echo "=== 2. El camino base responde con la extensión APAGADA ==="
echo "/health    : $(curl -s -m 3 http://127.0.0.1:18080/health)"
echo "/base/ping : $(curl -s -m 3 http://127.0.0.1:18080/base/ping)"

echo; echo "=== 3. Los endpoints de la extensión NO EXISTEN (no es que estén vacíos) ==="
echo "GET  /entidades/avance/did.json : HTTP $(curl -s -m 3 -o /dev/null -w '%{http_code}' http://127.0.0.1:18080/entidades/avance/did.json)"
echo "GET  /.well-known/did.json      : HTTP $(curl -s -m 3 -o /dev/null -w '%{http_code}' http://127.0.0.1:18080/.well-known/did.json)"
IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' vdr-off)
curl -s -m 3 -o /dev/null http://$IP:8081/admin/v1/challenges; echo "canal de escritura (puerto 8081 del contenedor): código de salida de curl = $? (7 = nadie escucha)"

echo; echo "=== 4. No se conectó a la base de datos: ni una sola línea de PostgreSQL en el registro ==="
echo "líneas del registro que mencionan hikari, postgres o jdbc: $(docker logs vdr-off 2>&1 | grep -ciE 'hikari|postgres|jdbc')"
docker rm -f vdr-off >/dev/null

echo; echo "=== 5. LO MISMO con la extensión ENCENDIDA (el proyecto levantado): el camino base no cambia ==="
echo "/health    : $(curl -s -m 3 --cacert certs/ca.crt --resolve $D:8443:127.0.0.1 https://$D:8443/health)"
echo "/base/ping : $(curl -s -m 3 --cacert certs/ca.crt --resolve $D:8443:127.0.0.1 https://$D:8443/base/ping)"

echo; echo "=== 6. Falla cerrada: valores no válidos NO encienden la extensión ==="
for v in si True 1; do
  docker run -d --name vdr-off -e VDR_ENABLED=$v $IMG >/dev/null; sleep 5
  printf "VDR_ENABLED=%-5s -> " "$v"; docker logs vdr-off 2>&1 | grep -E "VDR APAGADO|VDR HABILITADO" | sed 's/^[0-9:. ]*//; s/INFO  vdr.main - //'
  docker rm -f vdr-off >/dev/null
done
