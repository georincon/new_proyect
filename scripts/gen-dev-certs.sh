#!/usr/bin/env bash
# Certificados SOLO para laboratorio: una CA propia, el certificado TLS del servidor (dominio civica-desarrollo.avance.org.co)
# y un certificado de cliente por entidad (mTLS del canal de escritura, ERSo 006/008).
# En producción: certificado público (Let's Encrypt) para el canal de lectura y una CA privada para las entidades.
set -euo pipefail
cd "$(dirname "$0")/../deploy/certs"
DOMAIN="${VDR_DOMAIN:-civica-desarrollo.avance.org.co}"
P12PASS="${P12_PASS:-changeit}"

CLIENTS="avance-issuer lab-operator vdr-admin wallet-backend"

# Idempotente: si ya hay CA, solo se generan los certificados de cliente que falten (p. ej. wallet-backend, ERSo 001/003).
if [ -f ca.crt ]; then
  new=0
  for c in $CLIENTS; do
    [ -f "$c.p12" ] && continue
    openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -keyout "$c.key" -out "$c.csr" -subj "/CN=$c/O=Avance" 2>/dev/null
    printf "extendedKeyUsage=clientAuth\n" > client.ext
    openssl x509 -req -in "$c.csr" -CA ca.crt -CAkey ca.key -CAcreateserial -out "$c.crt" -days 825 -extfile client.ext 2>/dev/null
    openssl pkcs12 -export -inkey "$c.key" -in "$c.crt" -certfile ca.crt -out "$c.p12" -passout "pass:$P12PASS" 2>/dev/null
    chmod 644 "$c".key "$c".crt "$c".p12; new=$((new+1)); echo "Certificado de cliente generado: $c"
  done
  rm -f *.csr *.ext *.srl
  [ "$new" -eq 0 ] && echo "Ya existen certificados en deploy/certs (bórrelos para regenerar)."
  exit 0
fi

openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -keyout ca.key -out ca.crt \
  -subj "/CN=VDR Laboratorio CA/O=Avance" -days 825 2>/dev/null

# servidor
openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -keyout server.key -out server.csr -subj "/CN=$DOMAIN" 2>/dev/null
printf "subjectAltName=DNS:%s,DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth\n" "$DOMAIN" > server.ext
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out server.crt -days 825 -extfile server.ext 2>/dev/null

# clientes (una por entidad; el CN debe coincidir con el clientId)
for c in $CLIENTS; do
  openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -keyout "$c.key" -out "$c.csr" -subj "/CN=$c/O=Avance" 2>/dev/null
  printf "extendedKeyUsage=clientAuth\n" > client.ext
  openssl x509 -req -in "$c.csr" -CA ca.crt -CAkey ca.key -CAcreateserial -out "$c.crt" -days 825 -extfile client.ext 2>/dev/null
  openssl pkcs12 -export -inkey "$c.key" -in "$c.crt" -certfile ca.crt -out "$c.p12" -passout "pass:$P12PASS" 2>/dev/null
done

# certificado de una entidad NO registrada, para las pruebas negativas de mTLS
openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -keyout intruso.key -out intruso.csr -subj "/CN=intruso/O=Otro" 2>/dev/null
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -keyout ca-falsa.key -out ca-falsa.crt -subj "/CN=CA falsa" -days 30 2>/dev/null
openssl x509 -req -in intruso.csr -CA ca-falsa.crt -CAkey ca-falsa.key -CAcreateserial -out intruso.crt -days 30 -extfile client.ext 2>/dev/null
openssl pkcs12 -export -inkey intruso.key -in intruso.crt -out intruso.p12 -passout "pass:$P12PASS" 2>/dev/null

rm -f *.csr *.ext *.srl
chmod 644 *.key *.crt *.p12
echo "Certificados generados en deploy/certs:"; ls
