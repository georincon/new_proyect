#!/usr/bin/env bash
# Laboratorio 1b: verificar con la clave de otra persona, y firmar dos veces el mismo mensaje.
# Requisito: haber ejecutado antes lab-01-hash-y-firma.sh (usa los archivos de /tmp/lab).
# Ejecuta con:  bash scripts/lab-01b-firma-detalles.sh
mkdir -p /tmp/lab && cd /tmp/lab
echo "--- 0. Requisito: haber hecho el Laboratorio 1 ---"
ls k.pem pub.pem m.txt sig.bin

echo "--- 1. Verificar con la clave pública de OTRA persona ---"
openssl ecparam -name prime256v1 -genkey -noout -out k2.pem
openssl ec -in k2.pem -pubout -out pub2.pem 2>/dev/null
openssl dgst -sha256 -verify pub2.pem -signature sig.bin m.txt

echo "--- 2. Firmar el MISMO mensaje dos veces con la MISMA clave ---"
openssl dgst -sha256 -sign k.pem -out sigA.bin m.txt
openssl dgst -sha256 -sign k.pem -out sigB.bin m.txt
sha256sum sigA.bin sigB.bin
openssl dgst -sha256 -verify pub.pem -signature sigA.bin m.txt
openssl dgst -sha256 -verify pub.pem -signature sigB.bin m.txt

echo "--- 3. El hash que realmente se firma ---"
openssl dgst -sha256 m.txt
