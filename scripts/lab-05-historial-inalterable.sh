#!/usr/bin/env bash
# Laboratorio 5: el historial que no se puede borrar. Requiere el proyecto levantado y datos (pasos A–E).
# Ejecuta con:  bash scripts/lab-05-historial-inalterable.sh   (evita errores de copiado al pegar en la terminal)
cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy
set -a; . ./.env; set +a
PSQL="docker compose exec -T postgres psql -U $POSTGRES_USER -d $POSTGRES_DB"

echo "--- 1. El historial: una fila por versión de cada DID ---"
$PSQL -c "select did, version, operation, left(hash,18) as hash from did_document_versions order by did, version"

echo "--- 2. Intentar BORRAR el historial ---"
$PSQL -c "delete from did_document_versions" 2>&1 | head -2

echo "--- 3. Intentar MODIFICAR una versión ---"
$PSQL -c "update did_document_versions set hash = 'x'" 2>&1 | head -2

echo "--- 4. Comprobar que no se perdió nada ---"
$PSQL -c "select count(*) as versiones from did_document_versions"
