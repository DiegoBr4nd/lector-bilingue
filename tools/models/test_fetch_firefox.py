import hashlib
import json
import shutil
import subprocess
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import urlparse

import fetch_firefox
from fetch_firefox import FetchError, fetch, validate_pair, write_slimt_json


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def fake_compress(data: bytes) -> bytes:
    return data[::-1]


def fake_decompress(src: Path, dest: Path, max_bytes: int) -> None:
    data = src.read_bytes()[::-1]
    if len(data) > max_bytes:
        raise FetchError("descompresión demasiado grande")
    dest.write_bytes(data)


PLAIN = {
    "model.enes.bin": b"modelo-" * 100,
    "vocab.enes.spm": b"vocab-" * 50,
    "lex.enes.bin": b"lista-" * 20,
}


class _Handler(BaseHTTPRequestHandler):
    hits: list = []
    files: dict = {}

    def do_GET(self):
        type(self).hits.append(self.path)
        body = type(self).files.get(self.path)
        if body is None:
            self.send_response(404)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


class FetchFirefoxTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        handler = type("H", (_Handler,), {"hits": [], "files": {}})
        self.handler = handler
        self.server = HTTPServer(("127.0.0.1", 0), handler)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.host = "127.0.0.1"
        self.base = f"http://127.0.0.1:{self.port}"
        self.sources_path = self.tmp / "sources.json"
        self.write_sources()

    def write_sources(self, tweak=None, hosts=None):
        files = []
        for role, (name, data) in zip(("model", "vocabulary", "shortlist"), PLAIN.items()):
            comp = fake_compress(data)
            self.handler.files["/" + name + ".zst"] = comp
            files.append({
                "role": role, "name": name, "url": f"{self.base}/{name}.zst",
                "zst_sha256": sha(comp), "sha256": sha(data), "size": len(data),
            })
        if tweak:
            tweak(files)
        sources = {
            "version": 1, "source": "origen de prueba", "license": "MPL-2.0", "verified_on": "2026-10-03",
            "allowed_hosts": hosts if hosts is not None else [self.host],
            "pairs": {p: {
                "mozilla_version": "3.0", "architecture": "base-memory",
                "slimt": {"encoder_layers": 6, "decoder_layers": 4, "heads": 8, "source": "prueba"},
                "files": files,
            } for p in ("en-es", "es-en")},
        }
        self.sources_path.write_text(json.dumps(sources), encoding="utf-8")

    def run_fetch(self, pair="en-es", out=None):
        # Servidor local: http y su puerto (en producción solo https y el puerto 443).
        return fetch(pair, out or self.tmp / "out", self.sources_path,
                     decompress=fake_decompress, schemes=("http",), ports=(urlparse(self.base).port,))

    def test_descarga_correcta(self):
        out = self.tmp / "out"
        folder = self.run_fetch()
        self.assertEqual(folder, out / "firefox-en-es")
        for name, data in PLAIN.items():
            self.assertEqual((folder / name).read_bytes(), data)
        for extra in ("LICENSE", "ATTRIBUTION.txt", "MODEL_CARD.md", "slimt.json", "SHA256SUMS"):
            self.assertTrue((folder / extra).is_file(), extra)
        self.assertIn("Mozilla Public License Version 2.0", (folder / "LICENSE").read_text(encoding="utf-8"))
        card = (folder / "MODEL_CARD.md").read_text(encoding="utf-8")
        self.assertIn("3,28/5", card)
        self.assertIn("medido en→es, fase 2a", card)
        sums = {}
        for line in (folder / "SHA256SUMS").read_text().splitlines():
            digest, name = line.split("  ", 1)
            sums[name] = digest
        self.assertNotIn("SHA256SUMS", sums)
        self.assertIn("slimt.json", sums)
        for name, digest in sums.items():
            self.assertEqual(sha((folder / name).read_bytes()), digest)
        self.assertEqual(sorted(sums), sorted(p.name for p in folder.iterdir() if p.name != "SHA256SUMS"))
        self.assertEqual([p.name for p in out.iterdir()], ["firefox-en-es"])

    def test_nota_de_calidad_es_en_no_copia_los_numeros_de_en_es(self):
        card = (self.run_fetch("es-en") / "MODEL_CARD.md").read_text(encoding="utf-8")
        self.assertNotIn("3,28", card)
        self.assertIn("aún no medida", card)

    def test_slimt_json_formato_exacto(self):
        folder = self.run_fetch()
        data = json.loads((folder / "slimt.json").read_text(encoding="utf-8"))
        self.assertEqual(data, {
            "model": "model.enes.bin", "vocabulary": "vocab.enes.spm", "shortlist": "lex.enes.bin",
            "encoder_layers": 6, "decoder_layers": 4, "heads": 8,
        })
        self.assertLessEqual(len((folder / "slimt.json").read_bytes()), 4096)

    def test_slimt_json_rechaza_nombres_malos(self):
        spec = {"slimt": {"encoder_layers": 6, "decoder_layers": 4, "heads": 8},
                "files": [{"role": "model", "name": "a/b"}, {"role": "vocabulary", "name": "v"},
                          {"role": "shortlist", "name": "s"}]}
        with self.assertRaises(FetchError):
            write_slimt_json(self.tmp, spec)

    def test_slimt_json_rechaza_capas_fuera_de_rango(self):
        spec = {"slimt": {"encoder_layers": 0, "decoder_layers": 4, "heads": 8},
                "files": [{"role": "model", "name": "m"}, {"role": "vocabulary", "name": "v"},
                          {"role": "shortlist", "name": "s"}]}
        with self.assertRaises(FetchError):
            write_slimt_json(self.tmp, spec)

    def test_huella_comprimida_distinta(self):
        self.handler.files["/vocab.enes.spm.zst"] = b"otra-cosa"
        out = self.tmp / "out"
        with self.assertRaises(FetchError) as cm:
            self.run_fetch()
        self.assertIn("vocab.enes.spm", str(cm.exception))
        self.assertFalse(out.exists() and any(out.iterdir()))

    def test_huella_descomprimida_distinta(self):
        def tweak(files):
            files[0]["sha256"] = "0" * 64
        self.write_sources(tweak)
        out = self.tmp / "out"
        with self.assertRaises(FetchError):
            self.run_fetch()
        self.assertFalse(out.exists() and any(out.iterdir()))

    def test_host_fuera_de_la_lista_no_conecta(self):
        self.write_sources(hosts=["firefox-settings-attachments.cdn.mozilla.net"])
        self.handler.hits.clear()
        with self.assertRaises(FetchError):
            self.run_fetch()
        self.assertEqual(self.handler.hits, [])

    def test_https_obligatorio(self):
        self.handler.hits.clear()
        with self.assertRaises(FetchError):
            fetch("en-es", self.tmp / "out", self.sources_path, decompress=fake_decompress)
        self.assertEqual(self.handler.hits, [])

    def test_puerto_distinto_de_443_no_conecta(self):
        def tweak(files):
            for f in files:
                f["url"] = f"https://{self.host}:{self.port}/{f['name']}.zst"
        self.write_sources(tweak)
        self.handler.hits.clear()
        with self.assertRaises(FetchError) as cm:
            fetch("en-es", self.tmp / "out", self.sources_path, decompress=fake_decompress)
        self.assertIn("hosts permitidos", str(cm.exception))
        self.assertEqual(self.handler.hits, [])

    def test_check_spec_puertos(self):
        def spec(url):
            files = []
            for role, name in zip(fetch_firefox.ROLES, ("m.bin", "v.spm", "s.bin")):
                files.append({"role": role, "name": name, "url": url, "sha256": "0" * 64,
                              "zst_sha256": "0" * 64, "size": 1})
            return {"files": files}
        hosts = ["a.example"]
        # Sin puerto o con 443: aceptado.
        fetch_firefox._check_spec(spec("https://a.example/x.zst"), hosts, ("https",))
        fetch_firefox._check_spec(spec("https://a.example:443/x.zst"), hosts, ("https",))
        for bad in ("https://a.example:8443/x.zst", "https://a.example:80/x.zst",
                    "https://a.example:99999/x.zst", "https://a.example:abc/x.zst"):
            with self.assertRaises(FetchError, msg=bad):
                fetch_firefox._check_spec(spec(bad), hosts, ("https",))

    def test_descarga_cortada_da_fetch_error(self):
        class Short(_Handler):
            protocol_version = "HTTP/1.1"

            def do_GET(self):
                # Bloque chunked que promete 1000 bytes (0x3e8), manda 10 y cierra:
                # http.client lanza IncompleteRead (una HTTPException, no un OSError).
                self.send_response(200)
                self.send_header("Transfer-Encoding", "chunked")
                self.end_headers()
                self.wfile.write(b"3e8\r\n0123456789")
                self.wfile.flush()
                self.close_connection = True

        srv = HTTPServer(("127.0.0.1", 0), Short)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        self.addCleanup(srv.server_close)
        self.addCleanup(srv.shutdown)
        self.base = f"http://127.0.0.1:{srv.server_address[1]}"
        self.write_sources()
        out = self.tmp / "out"
        with self.assertRaises(FetchError) as cm:
            self.run_fetch(out=out)
        self.assertIn("no se pudo descargar", str(cm.exception))
        self.assertFalse(out.exists() and any(out.iterdir()))

    def test_http_exception_da_fetch_error(self):
        import http.client
        from unittest import mock

        class Boom:
            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

            def read(self, n):
                raise http.client.IncompleteRead(b"parcial", 100)

        class Opener:
            def open(self, req, timeout):
                return Boom()

        with mock.patch.object(fetch_firefox.urllib.request, "build_opener", return_value=Opener()):
            with self.assertRaises(FetchError) as cm:
                fetch_firefox._download("https://a.example/x", self.tmp / "x", "x.bin")
        self.assertEqual(str(cm.exception), "x.bin: no se pudo descargar")
        self.assertIsNone(cm.exception.__cause__)

    def test_redireccion_rechazada(self):
        class Redirect(_Handler):
            def do_GET(self):
                self.send_response(302)
                self.send_header("Location", "http://127.0.0.1:1/x")
                self.end_headers()

        srv = HTTPServer(("127.0.0.1", 0), Redirect)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        self.addCleanup(srv.server_close)
        self.addCleanup(srv.shutdown)
        self.base = f"http://127.0.0.1:{srv.server_address[1]}"
        self.write_sources()
        with self.assertRaises(FetchError):
            self.run_fetch()

    def test_pares_invalidos(self):
        for bad in ("../x", "en-es/../..", "EN-ES", "en", "fr-de", ""):
            with self.assertRaises(FetchError, msg=bad):
                validate_pair(bad)
            with self.assertRaises(FetchError, msg=bad):
                self.run_fetch(pair=bad)
        validate_pair("en-es")
        validate_pair("es-en")

    def test_nombre_de_archivo_peligroso_en_el_json(self):
        def tweak(files):
            files[0]["name"] = "../evil"
        self.write_sources(tweak)
        with self.assertRaises(FetchError):
            self.run_fetch()
        self.assertFalse((self.tmp / "evil").exists())

    def test_fuentes_reales_son_coherentes(self):
        data = json.loads(fetch_firefox.SOURCES.read_text(encoding="utf-8"))
        self.assertEqual(set(data["pairs"]), {"en-es", "es-en"})
        for pair, spec in data["pairs"].items():
            self.assertEqual([f["role"] for f in spec["files"]], ["model", "vocabulary", "shortlist"])
            for f in spec["files"]:
                self.assertTrue(f["url"].startswith("https://"))
                self.assertEqual(len(f["sha256"]), 64)
                self.assertEqual(len(f["zst_sha256"]), 64)
            self.assertEqual(spec["slimt"]["encoder_layers"], 6)
        self.assertIn("Mozilla Public License Version 2.0", fetch_firefox.MPL_TEXT.read_text(encoding="utf-8"))

    @unittest.skipUnless(shutil.which("zstd"), "zstd no está instalado")
    def test_descompresor_real_con_zstd(self):
        src, dest = self.tmp / "a.zst", self.tmp / "a"
        subprocess.run(["zstd", "-q", "-o", str(src)], input=b"hola" * 1000, check=True)
        fetch_firefox.zstd_decompress(src, dest, 10_000)
        self.assertEqual(dest.read_bytes(), b"hola" * 1000)
        with self.assertRaises(FetchError):
            fetch_firefox.zstd_decompress(src, self.tmp / "b", 100)


if __name__ == "__main__":
    unittest.main()
