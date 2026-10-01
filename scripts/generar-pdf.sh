#!/usr/bin/env bash
# Genera el PDF de un informe en Markdown (pandoc → HTML con estilo de impresión → Chrome sin interfaz).
# Uso:  bash scripts/generar-pdf.sh informes/INFORME-FINAL-ERSo-2026-004.md
# Requisitos: pandoc y google-chrome.
set -euo pipefail
cd "$(dirname "$0")/.."
MD="${1:?indique el archivo .md}"
PDF="${MD%.md}.pdf"
TMP="$(mktemp -d)"
pandoc "$MD" -f gfm -t html5 --standalone --toc --toc-depth=2 --metadata title="$(head -1 "$MD" | sed 's/^# *//')" --metadata lang=es \
  -c docs/print.css --embed-resources -o "$TMP/informe.html"
google-chrome --headless=new --disable-gpu --no-sandbox --no-pdf-header-footer --print-to-pdf="$PWD/$PDF" "file://$TMP/informe.html" >/dev/null 2>&1
echo "Generado: $PDF ($(pdfinfo "$PDF" 2>/dev/null | awk '/Pages/{print $2}') páginas)"
