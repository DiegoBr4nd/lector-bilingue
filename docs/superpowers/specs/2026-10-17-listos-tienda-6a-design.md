# 6a · Listos para la tienda — diseño

Fecha: 2026-10-17 · Rama: `feat/listos-tienda` · Fase 6 (publicación, al inicio solo en Google Play).

## 0. La Fase 6 completa (decidido con Juan)

Juan tiene cuenta personal de Play Console ya verificada. Por eso aplica la prueba cerrada de 12 probadores × 14 días seguidos antes de producción.

| Orden | Subfase | Contenido |
|---|---|---|
| 1 | **6a · Listos para la tienda** (este documento) | «Acerca de», «Licencias», «Enviar comentarios», fuentes sin duplicar, lint limpio |
| 2 | 6b · Compilación para Play | AAB del flavor `play`, prueba de la versión release en el Pixel sin borrar datos, auditorías MASVS, Material 3 y Compose (≥ 80) |
| 3 | 6d · Tienda y privacidad | Política de privacidad publicada (y su enlace en «Acerca de»), «Seguridad de los datos», clasificación, ficha es-419 (y en-US si da tiempo), **nombre y título de la app con criterio ASO**, guía para Juan |
| — | ▶ Empieza la prueba cerrada | 12 probadores × 14 días |
| 4 | 6c · Interfaz en inglés | Se hace durante los 14 días; se sube como actualización |
| — | ▶ Acceso a producción y publicación | — |

F-Droid, la Fase 4 (libro completo) y la Fase 5 (PDF/DOCX) van después del lanzamiento. El flavor `fdroid` debe seguir compilando.

## 1. Objetivo

Que la app cumpla lo que la tienda y las licencias exigen y tenga un canal de contacto, sin cambiar cómo se lee ni cómo se traduce.

Éxito:
- Desde la Biblioteca se llega a «Acerca de» y a «Licencias». Cada componente de terceros aparece con su licencia completa, legible sin internet.
- «Enviar comentarios» abre el correo con el mensaje preparado. Juan recibe un correo de prueba en su Pixel.
- El APK pesa unos 1 MB menos porque las fuentes ya no están duplicadas. El texto se ve igual que antes.
- `./gradlew lint` da 0 avisos en ambos flavors.

## 2. Decisiones de Juan (brainstorming)

| # | Pregunta | Decisión |
|---|---|---|
| 1 | Partición y orden de la Fase 6 | §0, opción A (6a → 6b → 6d → prueba cerrada → 6c) |
| 2 | Cómo armar la lista de licencias | **A** · plugin AboutLibraries solo al compilar + pantalla propia + archivo a mano para lo que Gradle no ve |
| 3 | Dirección de «Enviar comentarios» | **A** · un correo nuevo solo para la app (lo crea Juan; ver §8) |
| 4 | Contenido del correo | **A** · asunto + versión, Android y modelo del teléfono, visibles y editables |
| 5 | Nombre de la app | Se decide en la 6d con criterio ASO. En la 6a todo usa `@string/app_name` |
| 6 | Código fuente (GPL) | **No** como sección de «Acerca de». Va como **primera fila de «Licencias»** (opción A) |
| 7 | Diseño por partes | Partes 1–3 aprobadas («va bien») |

Fuera de alcance: política de privacidad y su enlace (6d), nombre y título definitivos (6d), interfaz en inglés (6c), AAB y firma (6b), cambios en el lector o en la traducción.

## 3. Pantalla «Acerca de»

- Entrada: ítem «Acerca de» en el menú ⋮ de la Biblioteca, debajo de «Cómo se usa». Ruta nueva `Route.About` en la navegación de la app.
- Barra superior con atrás y título «Acerca de». Contenido desplazable, con el mismo ancho máximo que «Cómo se usa» en tabletas.
- De arriba abajo:
  1. Ícono de la app (decorativo), `app_name` y «Versión X.Y.Z», leída de la versión real (`versionName`).
  2. Frase de privacidad: «Traduce dentro del teléfono. Tus libros y lo que lees nunca salen de él.»
  3. **Enviar comentarios** (botón): abre el correo (§5).
  4. **Modelos de traducción** (sección con encabezado):
     - OPUS-MT, Universidad de Helsinki (Language Technology Research Group), licencia CC-BY-4.0, con enlace a su fuente.
     - Firefox Translations, Mozilla, licencia MPL-2.0, con enlace a su fuente.
     - Las URL exactas de cada modelo se toman del catálogo y de los releases de `DiegoBr4nd/lector-bilingue-modelos`, y se fijan en el plan.
  5. **Licencias de código abierto** (botón): abre «Licencias» (§4).
- **No** hay sección «Código fuente» en esta pantalla (decisión 6).
- Enlaces: la función `openExternal` de la 3c-3. Se abre el navegador; la app no hace ninguna conexión.

## 4. Pantalla «Licencias»

- Ruta nueva `Route.Licenses`. Barra superior con atrás y título «Licencias».
- **Primera fila, fija:** «Lector bilingüe · GPL-3.0-or-later». Al tocarla se ve el texto de la GPL-3.0 y, arriba, «Código fuente: https://github.com/DiegoBr4nd/lector-bilingue» como enlace.
- Después, tres grupos con encabezado:
  1. **Librerías:** generadas por AboutLibraries a partir de las dependencias de Gradle del flavor que se compila (AndroidX, Compose, Readium, Room, Kotlin, Bouncy Castle…).
  2. **Motores de traducción (código nativo):** a mano. Como mínimo: CTranslate2 (MIT), SentencePiece (Apache-2.0), Abseil (Apache-2.0), slimt (y su licencia exacta según `native/third_party/slimt/LICENSE` y `COPYRIGHT`), ruy y cpuinfo, y XHScanner y cualquier otro código enlazado que lleve su propio aviso. El plan fija la lista exacta leyendo los `CMakeLists.txt` y los archivos `LICENSE`/`COPYRIGHT` de `native/`.
  3. **Fuentes e íconos:** a mano. Literata, Inter y Atkinson Hyperlegible (OFL-1.1, con los autores de `docs/licencias/fuentes.md`) y Material Symbols (Apache-2.0).
- Cada fila muestra el nombre y la licencia. Al tocarla se abre el **texto completo** de la licencia, guardado dentro de la app; funciona sin internet. Los avisos de copyright propios de cada componente (por ejemplo, el `COPYRIGHT` de slimt) se muestran junto al texto.
- La lista a mano vive en `licencias-extra.json` (en assets) y los textos de licencia en archivos aparte, uno por licencia.

## 5. «Enviar comentarios»

- Intent `ACTION_SENDTO` con `mailto:`. No hace falta ningún permiso nuevo y la app no envía nada sola.
- Destinatario: la dirección nueva de la app (§8), fija en un solo lugar del código.
- Asunto: `<app_name> · comentarios (<versionName>)`.
- Cuerpo:
  ```
  Escribe aquí tu comentario:


  ---
  Versión de la app: <versionName> (<flavor>)
  Android: <versión de Android>
  Teléfono: <fabricante> <modelo>
  ```
- **Nunca** van texto de libros, títulos de libros, rutas de archivos, idiomas descargados, RAM ni registros (*logs*).
- La construcción del mensaje es una función pura (sin Android) para poder probarla.

## 6. Limpieza técnica

**Fuentes sin duplicar**
- Se quedan solo las de `app/src/main/assets/lector/fuentes/`, porque el lector las sirve como archivos.
- Las pantallas de la app las cargan desde `assets` (`Font(path, assetManager, …)`), con los mismos pesos, estilos y ejes variables que hoy.
- Se borran `core/ui/src/main/res/font/*.ttf` y la copia duplicada `core/ui/licencias/`. Se actualiza `docs/licencias/fuentes.md`.
- Comprobación: capturas de la interfaz antes y después, iguales.

**Lint en 0 avisos**
- Actualizar Gradle a 9.8.1 y Kotlin a 2.4.21 (versiones de parche).
- Corregir `UsableSpace` y `UseKtx`.
- Marcar «momento» como falso positivo del corrector, solo para esa palabra.
- Quitar el string sin uso y resolver el aviso `Recycle` del código de debug.
- Si una actualización de parche rompe algo, se queda la versión actual y se marca solo ese aviso, con el motivo escrito.

**AboutLibraries**
- Solo el plugin de Gradle, con licencia Apache-2.0. **No** se añade ninguna librería suya al APK.
- Debe ser determinista (sin fechas ni rutas locales en el archivo generado) para no romper la compilación reproducible que pedirá F-Droid más adelante.
- Sin acceso a red durante la compilación para obtener licencias (modo offline o con los datos locales de Gradle).

## 7. Errores

- **Sin app de correo:** no se cierra la app. Debajo del botón se muestra la dirección, seleccionable para copiarla.
- **Sin navegador:** el enlace no hace nada visible y no hay cierre (igual que en la 3c-3).
- **Archivo de licencias ausente o ilegible:** la lista muestra un mensaje breve y no se cierra la app. Las pruebas impiden que pase en una compilación real.

## 8. Lo que necesito de Juan

- **Antes de empezar el código:** la dirección del correo nuevo de la app.
- Puerta final en su Pixel: abrir «Acerca de», enviar un comentario de prueba al correo nuevo y revisar «Licencias».

## 9. Pruebas y revisiones

- **JVM:**
  - Mensaje de comentarios: asunto, campos del cuerpo y que no haya nada más.
  - Licencias: cada entrada (generada o a mano) tiene texto; están todos los componentes nativos, fuentes y modelos de §3–§4; la primera fila es la app con GPL-3.0 y el enlace al código; el archivo generado no tiene fechas ni rutas locales.
  - Archivo de licencias roto → estado de error, sin excepción.
- **Pixel** (sin borrar datos de Juan; KeepTips y las reglas de siempre):
  - Biblioteca ⋮ → «Acerca de» → «Licencias» → una licencia completa; atrás vuelve en cada paso.
  - Letra al 200 %: textos enteros y botones ≥ 48 dp.
  - Fuentes: capturas antes y después iguales en Biblioteca, «Cómo se usa» y hoja Aa.
- **Compilación:** lint con 0 avisos, `./gradlew test` en ambos flavors, release de ambos flavors; el APK baja unos 1 MB.
- **`seguridad`:** plugin nuevo, actualización de Gradle y Kotlin, intent `mailto:` (sin datos del libro).
- **`diseno`:** «Acerca de» y «Licencias» en claro y oscuro, letra 100 % y 200 %, tableta; auditoría Material 3 ≥ 80.
