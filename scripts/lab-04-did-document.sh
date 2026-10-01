#!/usr/bin/env bash
# Laboratorio 4: leer un DID Document. Requiere el proyecto levantado y datos (pasos A–E).
# Ejecuta con:  bash scripts/lab-04-did-document.sh   (evita errores de copiado al pegar en la terminal)
cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy
D=civica-desarrollo.avance.org.co

echo "--- 1. Descargar el DID Document de Avance (es público: no hace falta ni token ni certificado cliente) ---"
curl -s --cacert certs/ca.crt --resolve $D:8443:127.0.0.1 https://$D:8443/entidades/avance/did.json | jq .

echo "--- 2. Resumen: lo esencial en una línea ---"
curl -s --cacert certs/ca.crt --resolve $D:8443:127.0.0.1 https://$D:8443/entidades/avance/did.json | jq -c '{id, claves: (.verificationMethod | length), tipo: .verificationMethod[0].type, prefijo: .verificationMethod[0].publicKeyMultibase[0:3], authentication, assertionMethod}'

echo "--- 3. Comprobación: ¿algún campo de clave privada? (debe imprimir 0) ---"
curl -s --cacert certs/ca.crt --resolve $D:8443:127.0.0.1 https://$D:8443/entidades/avance/did.json | grep -c -Ei 'private|secret|"d":'

cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy
export LOCAL_UID=$(id -u) LOCAL_GID=$(id -g)
docker compose --progress quiet --profile tools run --rm -T tools did --domain civica-desarrollo.avance.org.co --namespace entidades/avance
