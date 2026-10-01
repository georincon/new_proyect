#!/usr/bin/env bash
# Laboratorio ERSo 2026-004 (condiciones del proceso): aislado, forma según el método DID, marca O.
# Requisito: proyecto levantado (docs/MARCO-CONCEPTUAL.md, sección 2b, pasos A–D). Detiene el VDR unos segundos y lo vuelve a encender.
cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy
set -a; . ./.env; set +a
export LOCAL_UID=$(id -u) LOCAL_GID=$(id -g)
D=$VDR_DOMAIN
CODIGO() { curl -s -o /dev/null -w "%{http_code}" --cacert certs/ca.crt --resolve $1:$2:127.0.0.1 "https://$1:$2$3"; }

echo "=== CONDICIÓN 1 · entorno aislado, sin afectar el camino base ==="
echo "--- 1a. Todo en marcha ---"
echo "VDR   did.json de avance      : HTTP $(CODIGO $D 8443 /entidades/avance/did.json)"
echo "Wallet (instancia inexistente): HTTP $(CODIGO $D 8444 /wallet/v1/instances/00000000-0000-0000-0000-000000000000)   (401 = el servicio está vivo)"
echo "Credenciales (metadatos)      : HTTP $(CODIGO $D 8444 /.well-known/openid-credential-issuer)"
echo "--- 1b. Se DETIENE el contenedor del VDR (como si fallara) ---"
docker compose stop vdr >/dev/null 2>&1
echo "VDR   did.json de avance      : HTTP $(CODIGO $D 8443 /entidades/avance/did.json)   (502 = nginx no encuentra al VDR)"
echo "Wallet (instancia inexistente): HTTP $(CODIGO $D 8444 /wallet/v1/instances/00000000-0000-0000-0000-000000000000)"
echo "Credenciales (metadatos)      : HTTP $(CODIGO $D 8444 /.well-known/openid-credential-issuer)"
echo "nginx sigue arriba            : $(docker compose ps --format '{{.Service}} {{.State}}' | grep nginx)"
echo "--- 1c. Se vuelve a encender ---"
docker compose start vdr >/dev/null 2>&1
for i in $(seq 1 30); do [ "$(CODIGO $D 8443 /health)" = "200" ] && break; sleep 1; done
echo "VDR   did.json de avance      : HTTP $(CODIGO $D 8443 /entidades/avance/did.json)"

echo; echo "=== CONDICIÓN 2 · la forma del registro depende del método DID ==="
echo "--- 2a. La pieza que traduce (interfaz DidMethodAdapter) ---"
grep -n -A8 "interface DidMethodAdapter" ../vdr-service/src/main/kotlin/co/org/avance/ssi/vdr/RegistryService.kt | cut -c1-150
echo "--- 2b. Para did:web, el DID ES una dirección web ---"
docker compose --progress quiet --profile tools run --rm -T tools did --domain $D --namespace entidades/avance
echo "--- 2c. Un DID de OTRO método no tiene adaptador: se rechaza ---"
docker compose --progress quiet --profile tools run --rm -T tools resolve --did did:ebsi:z2FPyQqu1zrZvwFdtHHdsFf1 --ca /certs/ca.crt 2>&1 | grep -iE "error|inválido|invalid|did:web" | head -2

echo; echo "=== CONDICIÓN 3 · marca O: fuera del alcance base y DESACTIVADA por defecto ==="
IMG=vdr-ssi/vdr-service:local
PRUEBA() {
  desc=$1; shift
  docker rm -f vdr-cond >/dev/null 2>&1
  docker run -d --name vdr-cond "$@" $IMG >/dev/null
  sleep 5
  printf "%-46s -> " "$desc"
  docker logs vdr-cond 2>&1 | grep -E "VDR APAGADO|VDR HABILITADO|debe tener|no puede estar vac" | sed 's/^[0-9:. ]*//; s/INFO  vdr.main - //; s/Exception in thread "main" java.lang.IllegalArgumentException: //' | cut -c1-110 | head -1
  docker rm -f vdr-cond >/dev/null 2>&1
}
PRUEBA "sin definir VDR_ENABLED"
PRUEBA "VDR_ENABLED=false" -e VDR_ENABLED=false
PRUEBA "VDR_ENABLED=si (valor no válido)" -e VDR_ENABLED=si
PRUEBA "VDR_ENABLED=True (mayúscula)" -e VDR_ENABLED=True
PRUEBA "VDR_ENABLED=true SIN secreto ni clientes" -e VDR_ENABLED=true
