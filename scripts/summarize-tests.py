#!/usr/bin/env python3
"""Resume los resultados JUnit de todos los módulos con pruebas en evidencias/pruebas-automaticas.md (agrupado por ERSo)."""
import glob, re, sys, datetime, html
root = sys.argv[1] if len(sys.argv) > 1 else "."
mods = ["did-core", "did-resolver", "vdr-service", "credentials-core", "wallet-core", "wallet-service", "credential-service"]
out = ["# Evidencia — pruebas automáticas", "", f"Generado: {datetime.datetime.now().isoformat(timespec='seconds')}", ""]
total = fails = 0
for m in mods:
    rows = []
    for x in sorted(glob.glob(f"{root}/{m}/build/test-results/test/*.xml")):
        s = open(x, encoding="utf-8").read()
        cls = re.search(r'<testsuite name="([^"]+)"', s).group(1).split(".")[-1]
        for tc in re.finditer(r'<testcase name="([^"]+)"[^>]*?(/>|>(.*?)</testcase>)', s, re.S):
            name = html.unescape(tc.group(1)).removesuffix("()")
            body = tc.group(3) or ""
            failed = "<failure" in body or "<error" in body
            skipped = "<skipped" in body
            rows.append((cls, name, "FALLA" if failed else ("omitida" if skipped else "PASA")))
    t = len(rows); f = sum(1 for r in rows if r[2] == "FALLA")
    total += t; fails += f
    out += [f"## Módulo `{m}` — {t} pruebas, {f} fallos", "", "| Clase | Prueba | Resultado |", "|---|---|---|"]
    out += [f"| {c} | {n} | {r} |" for c, n, r in rows]
    out.append("")
out.insert(3, f"**Total: {total} pruebas · {fails} fallos**\n")
open(f"{root}/evidencias/pruebas-automaticas.md", "w", encoding="utf-8").write("\n".join(out))
print(f"total={total} fallos={fails}")
