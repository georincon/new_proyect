#!/usr/bin/env bash
# Laboratorio ERSo 2026-004: qué prueba automática demuestra cada paso y cada criterio.
# Requisitos: Docker, JDK 21. Usa una base de datos DESECHABLE (vdr-test-pg); no toca el proyecto levantado.
cd /home/geovani/Descargas/generic/bitacora/new_proyect

echo "=== 1. Base de datos DESECHABLE para las pruebas (no toca el proyecto levantado) ==="
if ! docker ps --format '{{.Names}}' | grep -qx vdr-test-pg; then
  docker rm -f vdr-test-pg >/dev/null 2>&1
  docker run -d --name vdr-test-pg -e POSTGRES_USER=vdr -e POSTGRES_PASSWORD=vdr -e POSTGRES_DB=vdr -p 127.0.0.1:55432:5432 postgres:16-alpine >/dev/null
  sleep 6
fi
docker exec vdr-test-pg psql -U vdr -d vdr -tc "select 1 from pg_database where datname='wallet_test'" | grep -q 1 || docker exec vdr-test-pg psql -U vdr -d vdr -c "CREATE DATABASE wallet_test" >/dev/null
echo "lista: vdr-test-pg (puerto 55432) con la base wallet_test"

echo; echo "=== 2. Ejecutar SOLO las pruebas de la ERSo 004 ==="
TEST_DB_URL=jdbc:postgresql://127.0.0.1:55432/wallet_test ./gradlew :vdr-service:test --tests '*Erso004*' --rerun-tasks -q 2>&1 | tail -3

echo; echo "=== 3. Qué prueba demuestra cada paso y cada criterio ==="
python3 - <<'EOF'
import re, glob, html
f = glob.glob('vdr-service/build/test-results/test/*Erso004*.xml')[0]
s = open(f, encoding='utf-8').read()
tot = re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)"', s).groups()
print(f"resultado: {tot[0]} pruebas, {tot[1]} omitidas, {tot[2]} fallos\n")
for m in re.finditer(r'<testcase name="([^"]+)"[^>]*?(/>|>(.*?)</testcase>)', s, re.S):
    name = html.unescape(m.group(1)).removesuffix("()")
    estado = "FALLA" if "<failure" in (m.group(3) or "") else "PASA "
    print(f"  [{estado}] {name}")
EOF
