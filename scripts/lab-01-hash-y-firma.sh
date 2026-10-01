#!/usr/bin/env bash
# Laboratorio 1 (marco conceptual): hash y firma. Solo necesita openssl.
# Ejecuta con:  bash scripts/lab-01-hash-y-firma.sh   (evita errores de copiado al pegar en la terminal)
mkdir -p /tmp/lab && cd /tmp/lab

echo "--- 1. Huella de dos textos casi iguales ---"
echo -n "hola"  | sha256sum
echo -n "hola!" | sha256sum

echo "--- 2. Crear un par de claves P-256 (la curva del proyecto) ---"
openssl ecparam -name prime256v1 -genkey -noout -out k.pem          # clave PRIVADA
openssl ec -in k.pem -pubout -out pub.pem 2>/dev/null               # clave PÚBLICA derivada de la privada
ls -l k.pem pub.pem

echo "--- 3. Firmar un mensaje con la privada ---"
echo -n "pago de 100" > m.txt
openssl dgst -sha256 -sign k.pem -out sig.bin m.txt                 # calcula el hash y lo firma
ls -l sig.bin

echo "--- 4. Verificar con la pública (mensaje original) ---"
openssl dgst -sha256 -verify pub.pem -signature sig.bin m.txt

echo "--- 5. Verificar con la pública (mensaje ALTERADO) ---"
echo -n "pago de 900" > m2.txt
openssl dgst -sha256 -verify pub.pem -signature sig.bin m2.txt
