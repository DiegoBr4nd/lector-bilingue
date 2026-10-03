#!/usr/bin/env python3
"""Genera vectores de PRUEBA en formato minisign para MinisignVerifierTest.

SOLO PARA PRUEBAS. Las semillas de abajo son públicas y fijas: cualquiera puede
regenerar las llaves privadas, así que JAMÁS deben firmar nada real. La llave de
producción de Juan nunca pasa por aquí.

Requisito (fuera del repo, en un venv temporal): pip install cryptography

Uso: python tools/catalog/make_test_vectors.py [carpeta_salida]
Por defecto escribe en models/src/test/resources/minisign/.
Determinista: Ed25519 es determinista, así que dos ejecuciones dan bytes idénticos.
No escribe llaves privadas (se regeneran desde las semillas).
"""
import base64
import hashlib
import sys
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

# --- SOLO PRUEBAS: semillas públicas, nunca usar para firmar nada real ---
TEST_ONLY_SEED_1 = hashlib.sha256(b"lector-bilingue TEST ONLY minisign key 1").digest()
TEST_ONLY_SEED_2 = hashlib.sha256(b"lector-bilingue TEST ONLY minisign key 2").digest()

UNTRUSTED = "untrusted comment: signature from minisign secret key"
TRUSTED = "timestamp:1759449600\tfile:catalog-ok.json\thashed"

CATALOG = (
    "{\n"
    '  "version": 1,\n'
    '  "models": [\n'
    "    {\n"
    '      "id": "opus-en-es-prueba",\n'
    '      "pair": "en-es",\n'
    '      "engine": "opus",\n'
    '      "files": [\n'
    '        {"name": "model.bin", "size": 1024, "sha256": "'
    + "0" * 64
    + '", "url": "https://github.com/DiegoBr4nd/lector-bilingue-modelos/releases/download/prueba/model.bin"}\n'
    "      ]\n"
    "    }\n"
    "  ]\n"
    "}\n"
)


class TestKey:
    def __init__(self, seed: bytes):
        self.sk = Ed25519PrivateKey.from_private_bytes(seed)
        self.pk = self.sk.public_key().public_bytes(
            serialization.Encoding.Raw, serialization.PublicFormat.Raw
        )
        # key id: 8 bytes con aspecto aleatorio derivados de la semilla.
        self.key_id = hashlib.blake2b(b"key-id" + seed, digest_size=8).digest()

    def pub_file(self) -> str:
        kid_hex = self.key_id[::-1].hex().upper()  # minisign imprime el id en little-endian
        b64 = base64.b64encode(b"Ed" + self.key_id + self.pk).decode()
        return f"untrusted comment: minisign public key {kid_hex}\n{b64}\n"

    def sign(self, message: bytes, alg: bytes = b"ED") -> str:
        if alg == b"ED":
            to_sign = hashlib.blake2b(message, digest_size=64).digest()
        elif alg == b"Ed":  # legado: firma el mensaje entero, sin prehash
            to_sign = message
        else:
            raise ValueError(alg)
        sig = self.sk.sign(to_sign)
        global_sig = self.sk.sign(sig + TRUSTED.encode("utf-8"))
        line2 = base64.b64encode(alg + self.key_id + sig).decode()
        line4 = base64.b64encode(global_sig).decode()
        return f"{UNTRUSTED}\n{line2}\ntrusted comment: {TRUSTED}\n{line4}\n"


def write(path: Path, text: str) -> None:
    path.write_bytes(text.encode("utf-8"))  # bytes: siempre LF, también en Windows


def main() -> None:
    root = Path(__file__).resolve().parents[2]
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else root / "models/src/test/resources/minisign"
    out.mkdir(parents=True, exist_ok=True)
    k1, k2 = TestKey(TEST_ONLY_SEED_1), TestKey(TEST_ONLY_SEED_2)
    msg = CATALOG.encode("utf-8")
    write(out / "test.pub", k1.pub_file())
    write(out / "test2.pub", k2.pub_file())
    (out / "catalog-ok.json").write_bytes(msg)
    write(out / "catalog-ok.json.minisig", k1.sign(msg))
    write(out / "catalog-otra-llave.json.minisig", k2.sign(msg))
    write(out / "catalog-legacy-Ed.json.minisig", k1.sign(msg, b"Ed"))
    print(f"vectores de prueba escritos en {out}")


if __name__ == "__main__":
    main()
