#!/usr/bin/env bash
# Genera deploy/.env con secretos aleatorios (no se versiona). Si ya existe, lo ACTUALIZA sin tocar los secretos existentes
# (agrega la cuenta wallet-backend y el token del portal de credenciales de las ERSo 001/002/003).
set -euo pipefail
cd "$(dirname "$0")/../deploy"
r() { openssl rand -hex 16; }

if [ ! -f .env ]; then
  PG=$(r); JWT=$(openssl rand -hex 32); A=$(r); L=$(r); D=$(r); W=$(r); T=$(openssl rand -hex 24)
  cat > .env <<ENV
VDR_DOMAIN=civica-desarrollo.avance.org.co
VDR_ENABLED=true
POSTGRES_DB=vdr
POSTGRES_USER=vdr
POSTGRES_PASSWORD=$PG
VDR_JWT_SECRET=$JWT
P12_PASS=changeit
# Cuentas de entidad: cada una con su namespace did:web reservado (ERSo 006). wallet-backend publica DID de titulares bajo titulares/* (ERSo 003).
VDR_CLIENTS='[{"clientId":"avance-issuer","secret":"$A","displayName":"Avance (emisor institucional)","namespaces":["entidades/avance","entidades/avance-ciclo"]},{"clientId":"lab-operator","secret":"$L","displayName":"Laboratorio de pruebas de identidad","namespaces":["lab/laboratorio"]},{"clientId":"vdr-admin","secret":"$D","displayName":"Administración VDR","admin":true},{"clientId":"wallet-backend","secret":"$W","displayName":"Wallet Backend","namespaces":["titulares/*"]}]'
CLIENT_SECRET_AVANCE=$A
CLIENT_SECRET_LAB=$L
CLIENT_SECRET_ADMIN=$D
CLIENT_SECRET_WALLET=$W
CREDENTIAL_ADMIN_TOKEN=$T
ENV
  chmod 600 .env
  echo "deploy/.env generado."
  exit 0
fi

# ---- actualización de un .env existente ----
if grep -q '^CLIENT_SECRET_WALLET=' .env && grep -q '^CREDENTIAL_ADMIN_TOKEN=' .env; then echo "deploy/.env ya está actualizado."; exit 0; fi
python3 - <<'PY'
import json, re, secrets
lines = open('.env').read().splitlines()
out, clients_idx = [], None
for i, l in enumerate(lines):
    out.append(l)
    if l.startswith("VDR_CLIENTS="): clients_idx = len(out) - 1
w = secrets.token_hex(16); t = secrets.token_hex(24)
raw = out[clients_idx].split("=", 1)[1].strip("'")
clients = json.loads(raw)
if not any(c["clientId"] == "wallet-backend" for c in clients):
    clients.append({"clientId": "wallet-backend", "secret": w, "displayName": "Wallet Backend", "namespaces": ["titulares/*"]})
else:
    w = next(c["secret"] for c in clients if c["clientId"] == "wallet-backend")
out[clients_idx] = "VDR_CLIENTS='" + json.dumps(clients, ensure_ascii=False, separators=(",", ":")) + "'"
if not any(l.startswith("CLIENT_SECRET_WALLET=") for l in out): out.append("CLIENT_SECRET_WALLET=" + w)
if not any(l.startswith("CREDENTIAL_ADMIN_TOKEN=") for l in out): out.append("CREDENTIAL_ADMIN_TOKEN=" + t)
open('.env', 'w').write("\n".join(out) + "\n")
PY
chmod 600 .env
echo "deploy/.env actualizado (wallet-backend y CREDENTIAL_ADMIN_TOKEN)."
