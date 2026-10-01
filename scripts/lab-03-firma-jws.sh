#!/usr/bin/env bash
# Laboratorio 3: abrir una firma JWS. Requiere el proyecto levantado y datos (pasos A–E).
# Ejecuta con:  bash scripts/lab-03-firma-jws.sh   (evita errores de copiado al pegar en la terminal)
cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy
set -a; . ./.env; set +a                       # carga las variables (dominio, contraseñas) de deploy/.env
export LOCAL_UID=$(id -u) LOCAL_GID=$(id -g)  # para que los archivos creados sean tuyos
D=$VDR_DOMAIN

echo "--- 1. Firmar el mensaje 'hola' con la clave privada de Avance ---"
JWS=$(docker compose --progress quiet --profile tools run --rm -T tools sign --key avance.json --kid "did:web:$D:entidades:avance#key-1" --message "hola" 2>/dev/null | tail -1)
echo "$JWS"

echo "--- 2. Abrir las tres partes (solo se decodifica Base64url; no hace falta ninguna clave) ---"
python3 - "$JWS" <<'EOF'
import sys, base64, json
h, p, s = sys.argv[1].split('.')
d = lambda x: base64.urlsafe_b64decode(x + '=' * (-len(x) % 4))
print("cabecera:", json.loads(d(h)))
print("payload :", d(p).decode())
print("firma   :", len(d(s)), "bytes")
EOF

echo "--- 3. Verificar: el verificador resuelve el DID por HTTPS y valida con la clave publicada ---"
docker compose --progress quiet --profile tools run --rm -T tools verify --jws "$JWS" --purpose assertionMethod --ca /certs/ca.crt 2>/dev/null | tail -1
