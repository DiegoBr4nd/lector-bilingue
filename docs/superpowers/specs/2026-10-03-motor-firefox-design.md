# Spec · Fase 2c · Motor Firefox + EngineSelector

- **Fecha:** 2026-10-03
- **Rama:** `feat/motor-firefox`
- **Estado:** diseño aprobado por Juan en conversación (partes 1, 2 y 3). Falta que revise esta spec.
- **Fase 2 partida en:** 2a calidad (hecha) → 2b modelos (hecha, PR #10) → **2c motor Firefox + EngineSelector** → 2d diseño + Bienvenida/Idiomas.
- **Agentes:**
  - `infraestructura`: compilación nativa de slimt, flujos de modelos y CI.
  - `estructura`: `:engine:firefox`, `EngineSelector`, cambios en `:models`, pantalla de prueba.
  - `seguridad`: revisión obligatoria (código nativo, archivos, dependencias, modelos de terceros).
  - `diseno`: revisión de la pantalla de prueba.

## 1. Objetivo

La app traduce con dos motores y elige el adecuado para cada celular:
- **OPUS** (CTranslate2): el principal, mejor calidad, unos 230 MB por par.
- **Firefox** (modelos de Mozilla corridos con **slimt**): el respaldo para celulares con menos de 4 GB de RAM. Pesa entre 17 y 44 MB por par y es más rápido, con algo menos de calidad.

Pares al cerrar la 2c: `en-es` y `es-en`, cada uno con OPUS y con Firefox.

**Puerta 2c** (con evidencia en el Pixel 7):
1. El modelo OPUS `en-es` ya instalado pasa solo a `models/opus/en-es/`, sin volver a descargarlo, y sigue traduciendo.
2. Con Firefox forzado: se descarga Firefox `en-es` desde el catálogo firmado y el benchmark cumple **≥ 15 palabras/s** y **< 2 s por párrafo** (mediana).
3. `es-en` funciona con los dos motores: descarga, traducción de prueba y benchmark.
4. En modo Automático, el Pixel (8 GB) elige OPUS.
5. Pruebas automáticas:
   - regla de RAM (2, 3, 4 y 8 GB);
   - "solo se carga un modelo instalado por el gestor";
   - migración de carpetas.
6. CI en verde. Revisión de `seguridad` sin hallazgos críticos ni altos abiertos. Revisión de `diseno` hecha.

### Fuera de alcance
- Pantallas definitivas (Bienvenida, Idiomas, ajustes del motor): 2d.
- Pantalla "Acerca de / licencias": fase posterior. Mientras tanto, cada modelo lleva su `LICENSE` y su `ATTRIBUTION.txt` en su carpeta.
- Otros pares de idiomas.
- Glosario de modismos.
- Elegir el motor según la RAM libre en el momento: la regla usa la RAM **total**.
- Prueba de la build release en el teléfono (pendiente de la 2b, anotado en el PR #10; bloquea la primera publicación).

## 2. Decisiones tomadas

| Tema | Decisión | Por qué |
|---|---|---|
| Motor nativo para Firefox | **slimt** (`jerinphilip/slimt`, GPL-2.0-or-later), compilado por nosotros con CMake y el NDK, con un puente JNI propio | Pequeño, pensado para los modelos tiny/base de Mozilla y con `ruy` para ARM. Es el motor que usa hoy Offline Translator en Android. Licencia compatible con GPL-3.0 |
| Alternativas descartadas por ahora | `bergamot-translator` (MPL-2.0) y la pila Rust de Offline Translator | El primero arrastra marian y apunta a WebAssembly. La segunda agrega Rust y funciones ajenas (incluye MuPDF, prohibido). Si la prueba rápida falla, se vuelve a decidir con datos |
| Prueba rápida | Es la **Tarea 1** del plan, con decisión de seguir o no (§7) | Evita invertir en el módulo si slimt no carga los modelos actuales de Mozilla o no cumple las metas |
| Regla de RAM | **≥ 4 GB de RAM total → OPUS**; si no, Firefox | Decisión de Juan. Un celular de 3 GB suele tener solo 1 a 1,5 GB libres, y OPUS usa unos 330 MB más el resto de la app. Se corrige `docs/agentes/01-estructura.md`, que decía 3 GB |
| Carpetas | `models/<engine>/<pair>/` | Un modelo Firefox `en-es` ya no pisa al OPUS `en-es` (pendiente de la 2b) |
| Carga de modelos | Solo modelos instalados por el gestor: `.installed.json` válido, con motor coincidente y todos sus archivos presentes | Nada copiado a mano ni borrado a medias llega al código C++ (pendiente de la 2b) |
| Origen de los modelos Firefox | Registro oficial de Mozilla, **sin modificar**. Se copian a releases de `lector-bilingue-modelos` | La app solo habla con GitHub (lista blanca de la 2b). El catálogo firmado fija la huella de cada archivo |
| OPUS `es-en` | `model.yml` recibe el par como parámetro y convierte `Helsinki-NLP/opus-mt-tc-big-cat_oci_spa-en` (revisión fijada, int8, misma receta) | No existe un tc-big es-en; decisión de Juan: el modelo muchos-a-uno cat/oci/spa a inglés, misma familia y licencia CC-BY-4.0. Sin tokens de idioma. Sus pesos son un `.bin` (pickle): se carga solo con `weights_only=True` y se reescribe como safetensors |
| Cambio de motor | Nunca automático tras un error: si el modo es Automático y el motor elegido falla al cargar, se **ofrece** el otro | Juan siempre sabe qué motor traduce |

## 3. Módulo `:engine:firefox`

- Biblioteca Android. Implementa `TranslationEngine` (`id = "firefox"`, con `load`, `translate` y `unload`), igual que `:engine:opus`.
- Código nativo en `native/slimtbridge/`:
  - **Dependencias fijadas:** slimt y las suyas, como `ruy` y `sentencepiece`, son submódulos con commit fijo, igual que CTranslate2.
  - **Compilación:** solo la biblioteca del puente (`targets += "slimtbridge"`), con `arm64-v8a` y `c++_static`.
  - **Avisos de licencia:** se conservan en los archivos copiados o derivados.
  - **Puente JNI:**
    - cargar el modelo desde una carpeta;
    - traducir una lista de oraciones;
    - liberar.
  - **Errores en el puente:** se convierten en excepciones Kotlin con mensajes fijos, sin rutas ni texto.
  - **Registro:** nada de lo que reciba el motor se escribe en el log.
- **Reglas** (las mismas que OPUS):
  - **Hilos:** máximo 4 hilos. Si slimt es de un solo hilo por traducción, se respeta igual el tope.
  - **Cola:** una sola traducción a la vez sobre el motor, en `Dispatchers.Default` con cola única.
  - **Oraciones:** el texto llega ya partido en oraciones (`:core:text`). Nunca se traducen párrafos enteros.
  - **Beam:** `beamSize` se ignora si slimt solo hace búsqueda voraz (beam 1). Se documenta en el KDoc.
- `load` falla con una excepción tipada si el modelo no está instalado por el gestor (§5).

## 4. `EngineSelector`

Es una función pura en `:engine:api`, sin dependencias de Android.

```
choose(pair, installed: Set<EngineId>, totalRamBytes: Long, forced: EngineId?) → Choice
```

- Hay `forced` y ese motor está en `installed` → ese motor, con motivo "forzado".
- `totalRamBytes ≥ 4 GiB` y OPUS instalado → OPUS, con motivo "RAM".
- `totalRamBytes < 4 GiB` y Firefox instalado → Firefox, con motivo "RAM".
- Solo uno instalado → ese motor, con motivo "único instalado".
- Ninguno instalado → `Missing(motorRecomendado)`. El motor recomendado sale de la regla de RAM, para que la pantalla ofrezca el modelo correcto.
- `forced` sin ese motor instalado → `Missing(forced)`.

**La RAM total** sale de `ActivityManager.MemoryInfo.totalMem`, en la capa de la app. El selector recibe solo el número.

**El motivo** es un enum, no texto: la pantalla lo traduce con `strings.xml`.

## 5. Cambios en `:models`

- **Rutas nuevas:** `filesDir/models/<engine>/<pair>/`, más `.installed.json` (el formato ya trae `engine`). `.tmp/<id>/` se queda en `models/.tmp/`, porque los `id` del catálogo ya son únicos entre motores. Las copias viejas `.old-<pair>-<n>` viven dentro de `models/<engine>/`, y la recuperación de la 2b corre en cada carpeta de motor.
- **Migración** (una vez, dentro del candado de `ModelsCoordinator`, antes de la recuperación):
  - **Qué mueve:** cada carpeta de primer nivel `models/<pair>/` cuyo `.installed.json` es válido.
  - **A dónde:** a `models/<engine>/<pair>/`, con renombre atómico.
  - **Lo que no tiene `.installed.json` válido:** se deja quieto.
  - **Si la migración se corta a mitad:** es repetible sin perder datos.
- **`ModelStore`:**
  - `installed()` devuelve `(engine, pair)`;
  - `isInstalled(engine, pair)` comprueba el JSON, el motor y que existan todos los archivos listados;
  - `delete(engine, pair)`.
- **`ModelInstaller` e importador:** instalan en la carpeta del motor del modelo del catálogo.
- **Motores:** reciben la carpeta que devuelve `ModelStore.installedDir`, que es la única puerta de validación: solo da carpetas dentro de la raíz del motor, con nombres válidos y sin enlaces simbólicos (más estricta que la comprobación de raíz que hacía cada motor). Por eso los motores solo comprueban `isDirectory` y no repiten la comprobación de raíz.

## 6. Modelos y publicación

**Firefox (`en-es`, `es-en`):**
- **Flujo nuevo** `.github/workflows/firefox-model.yml`, a mano, con el par como parámetro:
  1. **Descarga:** baja los archivos del modelo desde la fuente oficial de Mozilla: el modelo, el vocabulario (o vocabularios) y la lista corta (`lex`). La URL y la versión exactas las fija la Tarea 1.
  2. **Verificación:** comprueba las huellas que publica Mozilla. Si Mozilla no publica huellas para algún archivo, se registra la huella de la primera descarga en el repo y el flujo falla si cambia.
  3. **Archivos agregados:** `LICENSE` (MPL-2.0), `ATTRIBUTION.txt` (Mozilla, con enlace a la fuente) y `MODEL_CARD.md`.
  4. **Artefacto:** `firefox-<pair>`, con un `SHA256SUMS`.
- **Lo hace Juan:** sube los archivos a los releases `firefox-en-es-v1` y `firefox-es-en-v1`.

**OPUS `es-en`:** `model.yml` recibe el par como parámetro y convierte `Helsinki-NLP/opus-mt-tc-big-cat_oci_spa-en` (no existe un tc-big es-en; decisión de Juan). Juan sube a `opus-es-en-v1`. La calidad es-en se comprueba en la puerta.

**Catálogo:**
- **Armado:** Juan corre `build_catalog.py` (`--engine firefox` / `opus`), que ya admite los dos motores, y vuelve a firmar el catálogo.
- **En el APK:** el catálogo nuevo reemplaza al incrustado (`app/src/main/assets/catalog/`), junto con sus copias de prueba. La prueba `copiasDePruebaSonIgualesAlAssetRealDelApk` lo vigila.
- **Teléfonos ya instalados:** lo reciben por red.

**Guía:** `docs/catalogo.md` agrega cómo publicar modelos Firefox y OPUS de otro par.

## 7. Prueba rápida (Tarea 1)

Es desechable: su código vive en una rama aparte o en el scratchpad. Lo que se aprenda pasa al módulo.

1. Compilar slimt y sus dependencias para `arm64-v8a` con el NDK fijado (30.0.16248370) y CMake 4.1.2.
2. Bajar el modelo Firefox `en-es` oficial de Mozilla.
3. En el Pixel, con un ejecutable de prueba o una prueba instrumentada:
   - cargar el modelo;
   - traducir los 25 textos privados (oración por oración);
   - medir palabras/s, la mediana y el peor caso por párrafo, y la RAM.
4. Guardar las traducciones solo en `private/` de la laptop (nunca en el repo, nunca en la conversación) para que Juan compare.

**Seguir con slimt si se cumple todo:**
- compila;
- carga los modelos actuales de Mozilla sin parches grandes;
- ≥ 15 palabras/s y < 2 s por párrafo de mediana;
- las traducciones se ven sensatas al revisar una muestra de oraciones propias, no los textos privados.

**Si no:** el controlador para y presenta los datos a Juan para elegir entre `bergamot-translator` y la pila Rust.

## 8. Pantalla de prueba (temporal)

- **Interruptor de motor:** Automático / OPUS / Firefox. Debajo, el motor en uso y el motivo, por ejemplo "OPUS (8 GB de RAM)" o "Firefox (forzado)".
- **Selector de par:** `en → es` / `es → en`.
- **Lista de modelos del catálogo:**
  - cada fila con el motor, el par y el tamaño;
  - Descargar, Cancelar o Borrar en cada fila;
  - Importar `.zip` como hoy.
- **Benchmark** con el motor y el par elegidos. Solo números: nunca se muestra ni se registra el texto.
- **Mensajes:** siempre fijos, desde `strings.xml`:
  - "Descarga el modelo de Firefox en → es";
  - "El motor no pudo cargar el modelo";
  - ofrecer el otro motor en Automático.
- **Accesibilidad:** los mismos criterios de la 2b (48 dp, regiones vivas, sin depender del color).

## 9. Pruebas

- **`EngineSelector`:** tabla de casos (RAM de 2, 3, 4 y 8 GB × motores instalados × forzado), incluido el borde exacto de 4 GiB.
- **`ModelStore`:**
  - `isInstalled` falso sin JSON, con otro motor, si falta un archivo o si un archivo es un enlace;
  - migración idempotente, cortada a mitad y con carpetas sin JSON;
  - `delete` por motor sin tocar el otro.
- **Instalador e importador:** instalan en `models/<engine>/<pair>/`.
- **`:engine:firefox`:**
  - pruebas JVM del lado Kotlin (validación de carpeta y cola única);
  - prueba instrumentada en el Pixel que carga el modelo y traduce una oración propia, con `installFdroidDebugAndroidTest` y `am instrument`. Nunca `connectedAndroidTest`.
- **CI:**
  - compila el `.so` nuevo;
  - comprueba que el APK release trae `libslimtbridge.so` para arm64;
  - mantiene la lista exacta de permisos sin cambios.

## 10. Riesgos

| Riesgo | Mitigación |
|---|---|
| slimt (sin cambios desde 2024) no carga los modelos actuales de Mozilla | La prueba rápida lo descubre primero. Opciones: parche pequeño propio, tomar el parche del fork del autor de Offline Translator (GPL, con aviso) o pasar a la alternativa B |
| Mozilla cambia URLs o formatos | Copia en releases propios, versión fijada en el flujo y huellas en el catálogo firmado |
| Código C++ nuevo con entrada de archivos | Solo carga modelos instalados por el gestor y verificados por SHA-256. Revisión de `seguridad` |
| El APK crece | Medir antes y después. Los modelos van aparte; solo crece el `.so` |
| La migración de carpetas pierde el modelo de Juan | Renombre atómico, prueba de corte a mitad y comprobación en la puerta (punto 1). Su modelo se puede volver a descargar igual |
| Licencias mezcladas (GPL-2.0+, MPL-2.0, Apache-2.0 de dependencias) | Avisos conservados, `LICENSE` por modelo y revisión de `seguridad` (dependencias) |

## 11. Lo que hace Juan

1. Revisar y aprobar esta spec y luego el plan.
2. Tras la Tarea 1: ver los números de la prueba rápida y, si quiere, comparar las traducciones en `private/`.
3. **Publicar los modelos:**
   - correr los flujos `firefox-model.yml` (`en-es`, `es-en`) y `model.yml` (`es-en`);
   - subir los artefactos a sus releases;
   - armar el catálogo con los 4 modelos y firmarlo con su llave;
   - publicarlo en el release `catalogo`.
4. En la puerta: tener el Pixel conectado y desbloqueado, con tiempo de pantalla largo.
