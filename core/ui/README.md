# :core:ui

Sistema de diseño compartido (tema, tipografías, íconos, componentes). Solo depende de Compose; no conoce `:models` ni los motores.

## Fuentes (licencia OFL 1.1; textos en `licencias/`)
Descargadas de los repos oficiales, versión fija. Sumas SHA-256 del archivo ya copiado a `src/main/res/font/`.

| Archivo | Origen | SHA-256 |
|---|---|---|
| `inter_variable.ttf` | https://github.com/rsms/inter/releases/download/v4.1/Inter-4.1.zip (`InterVariable.ttf`, v4.1) | `4989b125924991b90d05b2d16e0e388c48f7d5bb8b30539bbf9c755278d0ccaf` |
| `literata_regular.ttf` | https://github.com/googlefonts/literata/releases/download/3.103/3.103.zip (`fonts/ttf/Literata-Regular.ttf`, 3.103) | `0390890de9bb9d5862a6ba4125b82c61792ccc3d66b63e73eee75c1a16fcd208` |
| `literata_semibold.ttf` | idem (`Literata-SemiBold.ttf`) | `ee8f9413ebc974e1c1cfc76f6bdb9d08ddaadc66eeddd7320a65f8c581284d6d` |
| `literata_italic.ttf` | idem (`Literata-Italic.ttf`) | `198f70cc9a17bab578553fa274b81984d58c440efe26bc06f1d841c194b6691a` |
| `atkinson_regular.ttf` | google/fonts `ofl/atkinsonhyperlegible/AtkinsonHyperlegible-Regular.ttf` (commit 95f4904) | `7fb917c89019896d0b52ee84b7cbb3304c18cb90b19a62f5e32712bd23e97669` |
| `atkinson_italic.ttf` | idem (`AtkinsonHyperlegible-Italic.ttf`) | `021beda4d3c6edfc78872e436d74009f9a1bcb294331908fe5747c61d3dcc514` |
| `atkinson_bold.ttf` | idem (`AtkinsonHyperlegible-Bold.ttf`) | `5a3b0c8cc8ca545155150b4512a1fa248298df121c50d6557e651e61fbdab92f` |

Lista completa (también las copias que sirve Readium): `docs/licencias/fuentes.md`.

Zips originales: Inter-4.1.zip `9883fdd4a49d4fb66bd8177ba6625ef9a64aa45899767dde3d36aa425756b11e`; 3.103.zip `f7fb973cafb26cf785cbebaeaf51c18f87c15a3bcf4d82a7d4857564db5b056d`.

### Decisión: variable o estáticas (tamaño comprimido, como va en el APK)
- Inter: variable 462 KB frente a 3 estáticas (400/600/700) 604 KB. Se usa la **variable** (un solo archivo, `R.font.inter_variable`).
- Literata: 3 estáticas (400, 600, cursiva 400) 424 KB frente a variable normal+cursiva ~1,2 MB. Se usan las **estáticas**.
- Los nombres `inter_regular/semibold/bold` del plan no existen: con la variable se escoge el peso al declarar la fuente (minSdk 26 lo permite):
  ```kotlin
  Font(R.font.inter_variable, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400)))
  ```
  (igual con 600 y 700). Las fuentes Literata son `literata_regular`, `literata_semibold`, `literata_italic`.

## Íconos
Material Symbols **Rounded**, peso 400, relleno 0, tamaño óptico 24, vectores de Android ya generados por Google (`symbols/android/<nombre>/materialsymbolsrounded/<nombre>_24px.xml`) en https://github.com/google/material-design-icons, commit `737e3324305806514d7909874fa1818ae1808232`. Licencia Apache-2.0 en `ICONOS-LICENSE.txt`.
Cambio local único: `?attr/colorControlNormal` pasó a `?android:attr/colorControlNormal` (la biblioteca no incluye AppCompat). Nombres: `ic_<nombre>`.
