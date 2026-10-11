# Fuentes que lleva la app

Todas con licencia **SIL Open Font License 1.1** (OFL). Cada familia lleva su texto de licencia al lado:
`app/src/main/assets/lector/fuentes/OFL-*.txt` (las que sirve Readium al libro) y `core/ui/licencias/OFL-*.txt` (las de
la interfaz, en `core/ui/src/main/res/font/`). Los archivos de los dos sitios son idénticos (mismas sumas).

Sumas SHA-256 de los archivos tal como están en el repositorio.

## Literata 3.103 (The Literata Project Authors, googlefonts/literata)
Origen: https://github.com/googlefonts/literata/releases/download/3.103/3.103.zip (zip SHA-256
`f7fb973cafb26cf785cbebaeaf51c18f87c15a3bcf4d82a7d4857564db5b056d`), carpeta `fonts/ttf/`.

| En el repo (assets/lector/fuentes y res/font) | Archivo original | SHA-256 |
|---|---|---|
| `literata_regular.ttf` | `Literata-Regular.ttf` | `0390890de9bb9d5862a6ba4125b82c61792ccc3d66b63e73eee75c1a16fcd208` |
| `literata_italic.ttf` | `Literata-Italic.ttf` | `198f70cc9a17bab578553fa274b81984d58c440efe26bc06f1d841c194b6691a` |
| `literata_semibold.ttf` | `Literata-SemiBold.ttf` | `ee8f9413ebc974e1c1cfc76f6bdb9d08ddaadc66eeddd7320a65f8c581284d6d` |
| `OFL-Literata.txt` | licencia OFL del proyecto | `8742963604cd89dc81437811a850018fc03b2bfad686d7422c8235967c87614e` |

## Inter 4.1 (The Inter Project Authors, rsms/inter)
Origen: https://github.com/rsms/inter/releases/download/v4.1/Inter-4.1.zip (zip SHA-256
`9883fdd4a49d4fb66bd8177ba6625ef9a64aa45899767dde3d36aa425756b11e`).

| En el repo | Archivo original | SHA-256 |
|---|---|---|
| `inter_variable.ttf` | `InterVariable.ttf` | `4989b125924991b90d05b2d16e0e388c48f7d5bb8b30539bbf9c755278d0ccaf` |
| `OFL-Inter.txt` | licencia OFL del proyecto | `262481e844521b326f5ecd053e59b98c8b2da78c8ee1bdbb6e8174305e54935a` |

## Atkinson Hyperlegible (Braille Institute of America, 2020)
Origen: repositorio de Google Fonts `google/fonts`, carpeta `ofl/atkinsonhyperlegible/`, commit
`95f4904fc8bcf26d3420fe315560c96417c6dec7` (2026-03-03). Descarga directa:
`https://raw.githubusercontent.com/google/fonts/main/ofl/atkinsonhyperlegible/<archivo>` (2026-10-10).

| En assets/lector/fuentes | En res/font | Archivo original | SHA-256 |
|---|---|---|---|
| `atkinson_hyperlegible_regular.ttf` | `atkinson_regular.ttf` | `AtkinsonHyperlegible-Regular.ttf` | `7fb917c89019896d0b52ee84b7cbb3304c18cb90b19a62f5e32712bd23e97669` |
| `atkinson_hyperlegible_italic.ttf` | `atkinson_italic.ttf` | `AtkinsonHyperlegible-Italic.ttf` | `021beda4d3c6edfc78872e436d74009f9a1bcb294331908fe5747c61d3dcc514` |
| `atkinson_hyperlegible_bold.ttf` | `atkinson_bold.ttf` | `AtkinsonHyperlegible-Bold.ttf` | `5a3b0c8cc8ca545155150b4512a1fa248298df121c50d6557e651e61fbdab92f` |
| `OFL-AtkinsonHyperlegible.txt` | (`core/ui/licencias/`) | `OFL.txt` | `f32d22b3908fcad2c86a74000614ec22e6a7f66ea7e867e616026a27aebdc143` |

Readium trae además sus propias fuentes (AccessibleDfA, iA Writer Duospace, OpenDyslexic) dentro de su biblioteca.
