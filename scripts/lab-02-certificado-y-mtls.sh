#!/usr/bin/env bash
# Laboratorio 2: certificado, cadena de confianza y mTLS. Requiere el proyecto levantado (pasos A–D).
# Ejecuta con:  bash scripts/lab-02-certificado-y-mtls.sh   (evita errores de copiado al pegar en la terminal)
cd /home/geovani/Descargas/generic/bitacora/new_proyect/deploy
D=civica-desarrollo.avance.org.co

echo "--- 1. Datos del certificado del servidor ---"
openssl x509 -in certs/server.crt -noout -subject -issuer -dates -ext subjectAltName

echo "--- 2. ¿Lo firmó nuestra CA? ---"
openssl verify -CAfile certs/ca.crt certs/server.crt

echo "--- 3. ¿Y una CA que NO lo firmó? ---"
openssl verify -CAfile certs/ca-falsa.crt certs/server.crt

echo "--- 4. Canal de escritura SIN certificado cliente ---"
curl -s -o /dev/null -w "HTTP %{http_code}\n" --cacert certs/ca.crt --resolve $D:9443:127.0.0.1 https://$D:9443/admin/v1/audit

echo "--- 5. Canal de escritura CON certificado cliente, sin token ---"
curl -s -o /dev/null -w "HTTP %{http_code}\n" --cacert certs/ca.crt --cert certs/vdr-admin.crt --key certs/vdr-admin.key --resolve $D:9443:127.0.0.1 https://$D:9443/admin/v1/audit

echo "--- 6. Sin --cacert: el cliente no conoce nuestra CA ---"
curl -s -o /dev/null -w "HTTP %{http_code}\n" --resolve $D:8443:127.0.0.1 https://$D:8443/health; echo "código de salida de curl: $?"
