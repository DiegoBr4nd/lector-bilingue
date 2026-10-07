# Compilar lector-bilingue

## Versiones fijas
| Herramienta | Versión | Dónde se fija |
|---|---|---|
| Gradle | 9.8.0 (SHA-256 `bafd5ce9…58e6c`) | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 9.4.1 | `gradle/libs.versions.toml` |
| Kotlin | 2.4.20 | `gradle/libs.versions.toml` |
| Compose BOM | 2026.09.00 | `gradle/libs.versions.toml` |
| Bytecode | Java 17 | `build-logic/.../ProjectConfig.kt` |
| SDK | compile/target 37, min 26 | `build-logic/.../ProjectConfig.kt` |
| NDK | 30.0.16248370 | `build-logic/.../ProjectConfig.kt` |
| CMake | 4.1.2 | `build-logic/.../ProjectConfig.kt` |
| CTranslate2 | v4.8.2 | submódulo `native/third_party/CTranslate2` |
| SentencePiece | v0.2.2 | submódulo `native/third_party/sentencepiece` |
| abseil-cpp | 20260526.0 | submódulo `native/third_party/abseil-cpp` |
| Modelo en-es (OPUS-MT tc-big) | revisión `8f4d4924…fd4d` | `tools/models/convert_opus.py` |
| transformers / torch (solo conversión del modelo) | 4.57.6 / 2.14.1+cpu | `tools/models/requirements.txt` |
| JDK para correr Gradle | local: JBR de Android Studio (25); CI: Temurin 17 | — |

## Módulos
- `:app`: pantallas y arranque.
- `:core:text`, `:core:ui`: texto compartido y diseño.
- `:engine:api`, `:engine:opus`, `:engine:firefox`: motores de traducción.
- `:models`: gestor de modelos y catálogo.
- `:books`: biblioteca: importar y validar EPUB, Room y Readium (sin pantallas).

## Comandos (Git Bash, desde la raíz)
```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew assembleFdroidDebug      # compilar
./gradlew test                     # pruebas unitarias
./gradlew lint                     # revisión estática
./gradlew installFdroidDebug       # instalar en el Pixel por USB
```

En PowerShell: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat assembleFdroidDebug`.

## Código nativo y modelo (fase 1b)
El motor está escrito en C++ (CTranslate2 + SentencePiece). Gradle lo compila con el **NDK** (el compilador de C++ para Android) y **CMake** (el programa que ordena cómo compilar). Solo se genera para `arm64-v8a`.

### Instalar NDK y CMake (una vez)
En el Windows de Juan, `sdkmanager.bat` redirige a la nueva Android CLI y corta el texto en `;`, así que se usa `android.exe` con `/`.

Git Bash:
```bash
"$LOCALAPPDATA/Android/Sdk/cmdline-tools/latest/bin/android.exe" sdk install ndk/30.0.16248370 cmake/4.1.2
```
PowerShell:
```powershell
& "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\android.exe" sdk install ndk/30.0.16248370 cmake/4.1.2
```
(El `sdkmanager` clásico con `"ndk;30.0.16248370" "cmake;4.1.2"` solo se usa en Linux, es decir, en el CI.)

### Clonar con submódulos
```bash
git clone https://github.com/DiegoBr4nd/lector-bilingue.git
cd lector-bilingue
tools/native/init-submodules.sh
```
No uses `--recursive` ni `git submodule update --init --recursive`: CTranslate2 trae submódulos de CUDA que pesan varios GB y no se necesitan. El script baja solo lo necesario.

### Obtener el modelo (para publicarlo; ver docs/catalogo.md)
1. En GitHub: pestaña *Actions* -> *model* -> *Run workflow* (o abre el run del PR).
2. Descarga el artefacto `modelo-en-es` y descomprime el `.zip`.
3. Descomprime el modelo con el `tar` que trae Windows (bsdtar 3.8.8 con zstd, comprobado con `--version`; Git Bash no trae `zstd`, por eso no se usa su `tar`):
   - PowerShell: `tar.exe -xf en-es.tar.zst`
   - Git Bash: `"/c/Windows/System32/tar.exe" -xf en-es.tar.zst`

   Resultado esperado: una carpeta `en-es/` con `model.bin`, `source.spm`, `target.spm`, `config.json`, `LICENSE`, `ATTRIBUTION.txt`, `SHA256SUMS`, etc.
4. Dentro de `en-es/`, verifica: `sha256sum -c SHA256SUMS`.

### Textos privados
Convierte tu HTML en el formato del benchmark (el archivo queda en `private/`, que git ignora):
```bash
python tools/bench/html_to_txt.py textos_para_firefox.html private/textos.txt
```

### Instalar el modelo en el teléfono
Ya no se copia el modelo con `adb`. La app solo carga modelos que instaló su gestor (carpeta `files/models/<motor>/<par>/` con un archivo `.installed.json`); una carpeta copiada a mano se ignora.
- En la app, pantalla de modelos: botón **Descargar modelo** (baja el modelo del catálogo firmado) o **Importar modelo (.zip)** (para un `.zip` que ya tienes en el teléfono; la app lo revisa contra el catálogo).
- Cómo se publican los modelos y el catálogo: `docs/catalogo.md`.

### Copiar los textos privados al teléfono
Pixel por USB con depuración activada y la app *debug* instalada (`./gradlew installFdroidDebug`).

Git Bash:
```bash
# Git Bash convierte rutas que empiezan con / en rutas de Windows; esto lo desactiva
export MSYS_NO_PATHCONV=1
adb push private/textos.txt /data/local/tmp/textos.txt
adb shell run-as io.github.diegobr4nd.lectorbilingue mkdir -p files/bench
adb shell run-as io.github.diegobr4nd.lectorbilingue cp /data/local/tmp/textos.txt files/bench/textos.txt
adb shell rm /data/local/tmp/textos.txt
```
PowerShell (no cambia las rutas, no hace falta la variable):
```powershell
adb push private/textos.txt /data/local/tmp/textos.txt
adb shell run-as io.github.diegobr4nd.lectorbilingue mkdir -p files/bench
adb shell run-as io.github.diegobr4nd.lectorbilingue cp /data/local/tmp/textos.txt files/bench/textos.txt
adb shell rm /data/local/tmp/textos.txt
```

### Prueba en el teléfono
```bash
./gradlew installFdroidDebug installFdroidDebugAndroidTest
export MSYS_NO_PATHCONV=1
adb shell am force-stop io.github.diegobr4nd.lectorbilingue
adb shell am instrument -w io.github.diegobr4nd.lectorbilingue.test/androidx.test.runner.AndroidJUnitRunner
```
Cierra la app antes (`force-stop`): la pantalla de prueba carga su propio motor en el mismo proceso y falsea la medición de memoria.

No uses `connectedAndroidTest`: desinstala la app al terminar y con ella se borra el modelo instalado. Sin el modelo, las pruebas que lo necesitan se saltan solas.

### Pruebas de las herramientas Python
```bash
(cd tools/bench && python -m unittest -v)
(cd tools/models && python -m unittest -v test_convert_opus test_fetch_firefox)
(cd tools/catalog && python -m unittest -v)
```

## Comparación de calidad beam 1 vs beam 4
Beam es cuántas "opciones" prueba el motor mientras traduce: con beam 1 toma siempre la más probable (rápido); con beam 4 compara cuatro caminos y elige el mejor (más lento, quizá mejor). Aquí lo medimos a ciegas con tus textos: tú pones notas sin saber cuál es cuál.

Todo queda en `private/` (ignorada por git). Los textos y sus traducciones nunca se suben.

### 1. Generar las traducciones en el teléfono
Requiere el modelo instalado desde la app (ver "Instalar el modelo en el teléfono"), los textos ya copiados (ver "Copiar los textos privados al teléfono") y la app instalada.
```bash
export MSYS_NO_PATHCONV=1
adb shell am force-stop io.github.diegobr4nd.lectorbilingue
adb shell am instrument -w -e class io.github.diegobr4nd.lectorbilingue.CalidadBeamTest -e calidad 1 io.github.diegobr4nd.lectorbilingue.test/androidx.test.runner.AndroidJUnitRunner
adb exec-out run-as io.github.diegobr4nd.lectorbilingue cat files/bench/comparacion.json > private/comparacion.json
```
Corre estos comandos en Git Bash (no en PowerShell 5.1: su `>` escribe UTF-16 y dañaría el archivo).
La prueba traduce cada texto con beam 1 y con beam 4 y guarda el resultado y los tiempos en `comparacion.json`.

### 2. Generar la página de evaluación
```bash
python tools/bench/make_eval_html.py private/textos.txt private/comparacion.json private/evaluacion.html private/evaluacion-clave.json
```
Solo imprime conteos. Crea dos archivos: la página (sin la clave dentro) y la clave aparte.
Si los archivos ya existen, el script se niega a reemplazarlos; hay que añadir `--force`. Ojo: regenerar crea una clave nueva y las notas guardadas en la página anterior se pierden (la clave vieja ya no sirve).

### 3. Evaluar y revelar
- Abre `private/evaluacion.html` en el navegador (doble clic).
- Lee el original y pon nota de 1 a 5 a la traducción A y a la B. Tu avance se guarda solo.
- Al terminar, pulsa "Revelar" y elige `private/evaluacion-clave.json`. Verás el promedio de cada beam, cuántos textos ganó cada uno y la velocidad (palabras por segundo, mediana y peor caso).

## Catálogo firmado de modelos

Cómo publicar un modelo, armar `catalog.json` (`tools/catalog/build_catalog.py`) y firmarlo con minisign: ver [catalogo.md](catalogo.md).

## Verificación de dependencias
Cada librería tiene su huella SHA-256 en `gradle/verification-metadata.xml`. Si una cambia, la compilación falla.

Al agregar o actualizar una dependencia:
1. Cambiar la versión en `gradle/libs.versions.toml`.
2. Si cambió AGP, actualizar el número de aapt2 en `build.gradle.kts` (raíz) con el de
   `https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/maven-metadata.xml`.
3. Regenerar:
```bash
./gradlew --write-verification-metadata sha256 --refresh-dependencies --rerun-tasks resolveAapt2AllPlatforms lint test assembleFdroidDebug assemblePlayDebug assembleFdroidRelease assemblePlayRelease assembleFdroidDebugAndroidTest
```
   Ojo: este comando también recompila el código nativo (CTranslate2), así que tarda bastante más.
4. Revisar el diff de `verification-metadata.xml` en el PR. Solo deben aparecer los artefactos nuevos.
5. Pedir revisión del agente de seguridad (regla 8 de CLAUDE.md).

## Reproducibilidad
- Sin bloque de dependencias de Google en el APK (`dependenciesInfo`).
- Sin hash de git en el APK (`vcsInfo.include = false`).
- La comparación de dos compilaciones limpias llega en la fase 6 (`release.yml`).
