"""Genera la página de evaluación a ciegas (beam 1 vs beam 4) y su clave.

Uso:
    python tools/bench/make_eval_html.py private/textos.txt private/comparacion.json \
        private/evaluacion.html private/evaluacion-clave.json

Solo biblioteca estándar. Imprime únicamente conteos, nunca texto. La clave (qué
traducción es A y cuál B) va en un archivo aparte y NO se incrusta en la página.
"""
import hashlib
import html as htmllib
import json
import random
import secrets
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from bench_format import read_bench_paragraphs  # noqa: E402

BEAMS = ("beam1", "beam4")


def _is_int(v) -> bool:
    return isinstance(v, int) and not isinstance(v, bool)


def _validate(paragraphs: list[str], comparison: dict) -> list[dict]:
    """Lanza ValueError. Los mensajes nunca incluyen texto de los párrafos."""
    if not isinstance(comparison, dict) or comparison.get("version") != 1:
        raise ValueError("comparacion.json: versión no soportada (se espera 1)")
    items = comparison.get("paragraphs")
    if not isinstance(items, list):
        raise ValueError("comparacion.json: falta la lista 'paragraphs'")
    if len(items) != len(paragraphs):
        raise ValueError(
            f"No coincide la cantidad: {len(paragraphs)} textos originales y {len(items)} en la comparación"
        )
    for pos, item in enumerate(items):
        if not isinstance(item, dict) or not _is_int(item.get("index")) or item["index"] != pos:
            raise ValueError(f"Párrafo en la posición {pos}: 'index' fuera de orden")
        for beam in BEAMS:
            b = item.get(beam)
            if not isinstance(b, dict) or not isinstance(b.get("text"), str) or not _is_int(b.get("ms")):
                raise ValueError(f"Párrafo {pos}: '{beam}' debe tener 'text' (texto) y 'ms' (entero)")
        if "words" in item and (not _is_int(item["words"]) or item["words"] < 0):
            raise ValueError(f"Párrafo {pos}: 'words' debe ser un entero no negativo")
    return items


def _speed(paragraphs: list[str], items: list[dict]) -> dict:
    words = [it["words"] if "words" in it else len(paragraphs[i].split()) for i, it in enumerate(items)]
    out = {}
    for beam in BEAMS:
        ms = [it[beam]["ms"] for it in items]
        secs = sum(ms) / 1000
        out[beam[-1]] = {
            "wps": round(sum(words) / secs, 2) if secs > 0 else 0.0,
            "median_ms": statistics.median(ms) if ms else 0,
            "max_ms": max(ms) if ms else 0,
        }
    return out


def build_eval(paragraphs: list[str], comparison: dict, seed: int | None = None) -> tuple[str, dict]:
    items = _validate(paragraphs, comparison)
    rng = random.Random(secrets.randbits(64) if seed is None else seed)
    esc = htmllib.escape

    key, blocks = [], []
    for i, item in enumerate(items):
        beam4_en_a = rng.random() < 0.5
        a, b = ("beam4", "beam1") if beam4_en_a else ("beam1", "beam4")
        key.append({"index": i, "A": a, "B": b})
        cols = []
        for slot, beam in (("A", a), ("B", b)):
            radios = "".join(
                f'<label><input type="radio" name="n-{slot}-{i}" value="{n}" '
                f'data-slot="{slot}" data-i="{i}">{n}</label>'
                for n in range(1, 6)
            )
            cols.append(
                f'<div class="col"><h3>Traducción {slot}</h3>'
                f'<div class="trad" data-slot="{slot}" data-i="{i}">{esc(item[beam]["text"])}</div>'
                f'<div class="notas" role="radiogroup" aria-label="Nota para {slot}, texto {i + 1}">'
                f"{radios}</div></div>"
            )
        blocks.append(
            f'<section class="texto" data-i="{i}">\n'
            f"<h2>Texto {i + 1} de {len(items)}</h2>\n"
            f'<p class="orig">{esc(paragraphs[i])}</p>\n'
            f'<div class="par">\n' + "\n".join(cols) + "\n</div>\n</section>"
        )

    body = "\n".join(blocks)
    page_id = hashlib.sha256(body.encode("utf-8")).hexdigest()[:16]
    html = (
        _TEMPLATE.replace("@@TEXTOS@@", body)
        .replace("@@ID@@", page_id)
        .replace("@@N@@", str(len(items)))
        .replace("@@VELOCIDAD@@", json.dumps(_speed(paragraphs, items)))
    )
    return html, {"version": 1, "key": key}


def main(argv: list[str]) -> int:
    if len(argv) != 4:
        print("Uso: make_eval_html.py textos.txt comparacion.json evaluacion.html evaluacion-clave.json", file=sys.stderr)
        return 2
    textos, comp, out_html, out_key = (Path(a) for a in argv)
    paragraphs = read_bench_paragraphs(textos)
    comparison = json.loads(comp.read_text(encoding="utf-8"))
    html, key = build_eval(paragraphs, comparison)
    out_html.write_text(html, encoding="utf-8")
    out_key.write_text(json.dumps(key, indent=1) + "\n", encoding="utf-8")
    print(f"{len(paragraphs)} textos; página en {out_html}; clave en {out_key}")
    return 0


_TEMPLATE = """<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Evaluación a ciegas de traducciones</title>
<style>
:root { color-scheme: light dark; }
body { font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; line-height: 1.5; max-width: 900px; margin: 0 auto; padding: 16px; }
.instrucciones, .texto, #resultados { border: 1px solid #8886; border-radius: 8px; padding: 12px 16px; margin: 16px 0; }
.orig { font-weight: 600; }
.par { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
@media (max-width: 640px) { .par { grid-template-columns: 1fr; } }
.trad { background: #8881; border-radius: 6px; padding: 8px; }
.notas label { margin-right: 14px; }
table { border-collapse: collapse; }
th, td { border: 1px solid #8886; padding: 4px 10px; text-align: right; }
th:first-child, td:first-child { text-align: left; }
button { font: inherit; padding: 6px 14px; }
</style>
</head>
<body>
<h1>Evaluación a ciegas de traducciones</h1>
<div class="instrucciones">
<p><strong>Cómo usar esta página</strong></p>
<ol>
<li>Lee el original, pon nota 1–5 a A y a B (1 = muy mala, 5 = excelente). No sabes cuál es cuál.</li>
<li>Tu avance se guarda solo en este navegador. Puedes cerrar y volver.</li>
<li>Al terminar, pulsa &laquo;Revelar&raquo; y elige el archivo de clave (evaluacion-clave.json).</li>
</ol>
</div>
@@TEXTOS@@
<div class="instrucciones">
<p><button id="revelar" type="button">Revelar</button>
<label>Archivo de clave: <input type="file" id="clave" accept=".json,application/json"></label></p>
<p id="aviso" role="status"></p>
</div>
<div id="resultados" hidden>
<h2>Resultados</h2>
<div id="puntajes"></div>
<h3>Velocidad</h3>
<div id="velocidad-tabla"></div>
</div>
<script id="velocidad" type="application/json">@@VELOCIDAD@@</script>
<script>
(function () {
  "use strict";
  var ID = "lb-eval-@@ID@@";
  var N = @@N@@;
  var notas = {};
  var claveTexto = null;

  function leer() { try { var s = localStorage.getItem(ID); if (s) notas = JSON.parse(s) || {}; } catch (e) { notas = {}; } }
  function guardar() { try { localStorage.setItem(ID, JSON.stringify(notas)); } catch (e) { /* sin almacenamiento: sigue funcionando */ } }

  leer();
  var radios = document.querySelectorAll('input[type="radio"]');
  Array.prototype.forEach.call(radios, function (r) {
    var k = r.dataset.slot + r.dataset.i;
    if (notas[k] === r.value) r.checked = true;
    r.addEventListener("change", function () { notas[k] = r.value; guardar(); });
  });

  document.getElementById("clave").addEventListener("change", function (ev) {
    var f = ev.target.files && ev.target.files[0];
    if (!f) return;
    var lector = new FileReader();
    lector.onload = function () { claveTexto = String(lector.result); };
    lector.onerror = function () { aviso("No se pudo leer el archivo."); };
    lector.readAsText(f, "utf-8");
  });

  function aviso(t) { document.getElementById("aviso").textContent = t; }
  function num(v) { return String(v).replace(/^beam/, ""); }
  function fmt(x) { return (Math.round(x * 100) / 100).toLocaleString("es"); }

  function celda(tr, texto, tag) { var c = document.createElement(tag); c.textContent = texto; tr.appendChild(c); }
  function tabla(filas) {
    var t = document.createElement("table");
    filas.forEach(function (fila, i) {
      var tr = document.createElement("tr");
      fila.forEach(function (v) { celda(tr, v, i === 0 ? "th" : "td"); });
      t.appendChild(tr);
    });
    return t;
  }

  document.getElementById("revelar").addEventListener("click", function () {
    if (claveTexto === null) { aviso("Primero elige el archivo de clave."); return; }
    var clave;
    try { clave = JSON.parse(claveTexto); } catch (e) { aviso("El archivo de clave no es JSON válido."); return; }
    if (!clave || clave.version !== 1 || !Array.isArray(clave.key) || clave.key.length !== N) {
      aviso("La clave no corresponde a esta página."); return;
    }
    var suma = { "1": 0, "4": 0 }, gana = { "1": 0, "4": 0 }, empates = 0, contados = 0;
    clave.key.forEach(function (e) {
      var sa = notas["A" + e.index], sb = notas["B" + e.index];
      if (!sa || !sb) return;
      var p = {}; p[num(e.A)] = Number(sa); p[num(e.B)] = Number(sb);
      suma["1"] += p["1"]; suma["4"] += p["4"]; contados++;
      if (p["1"] > p["4"]) gana["1"]++; else if (p["4"] > p["1"]) gana["4"]++; else empates++;
    });
    var cont = document.getElementById("puntajes");
    cont.textContent = "";
    if (contados === 0) { aviso("Aún no hay textos con nota para A y B."); return; }
    aviso(contados + " de " + N + " textos con nota.");
    cont.appendChild(tabla([
      ["", "Nota promedio", "Textos ganados"],
      ["Beam 1", fmt(suma["1"] / contados), String(gana["1"])],
      ["Beam 4", fmt(suma["4"] / contados), String(gana["4"])],
      ["Empates", "", String(empates)]
    ]));
    var v = JSON.parse(document.getElementById("velocidad").textContent);
    var vt = document.getElementById("velocidad-tabla");
    vt.textContent = "";
    vt.appendChild(tabla([
      ["", "Palabras/s", "Mediana (ms)", "Peor caso (ms)"],
      ["Beam 1", fmt(v["1"].wps), fmt(v["1"].median_ms), fmt(v["1"].max_ms)],
      ["Beam 4", fmt(v["4"].wps), fmt(v["4"].median_ms), fmt(v["4"].max_ms)]
    ]));
    document.getElementById("resultados").hidden = false;
  });
})();
</script>
</body>
</html>
"""


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
