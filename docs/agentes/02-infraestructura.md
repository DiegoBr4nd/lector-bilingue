# 02 · Agente de INFRAESTRUCTURA (compilación, código nativo, modelos y publicación)

> **Misión:** que el proyecto compile igual en cualquier máquina, que los motores nativos funcionen en Android, que los modelos lleguen al teléfono de forma segura y que la app se publique en F-Droid y Play.
> **Lee primero:** `CLAUDE.md`, `docs/contexto-y-decisiones.md`, `docs/agentes/05-metodologia.md`.
> **Entrega a:** *estructura* (librerías `.so` y modelos listos), *seguridad* (pipeline verificable).

## 1. Skills que debe usar este agente

| Skill | Qué aporta | Instalación (la hace Juan) |
|---|---|---|
| Skill `android-cli` (oficial de Google) | Referencia de la Android CLI. **Opcional:** en Windows con Smart App Control la CLI no carga; usar Gradle y Android Studio | Ya copiada en `.claude/skills/` (Paso 0.5 del plan maestro) |
| **agp-9-upgrade** (oficial) | Configuración moderna de Gradle/AGP | Ya copiada en `.claude/skills/` (Paso 0.5 del plan maestro) |
| **r8-analyzer** (oficial) | Reglas de R8 (minificación) sin romper JNI | Ya copiada en `.claude/skills/` (Paso 0.5 del plan maestro) |
| **testing-setup** (oficial) | Configuración de pruebas | Ya copiada en `.claude/skills/` (Paso 0.5 del plan maestro) |
| **Superpowers** | Proceso | Ver `05-metodologia.md` |

> Repositorio oficial: `github.com/android/skills` (Apache-2.0). En el Windows de Juan, *Smart App Control* bloquea la Android CLI (un `.dll` sin firma reconocida): **no dependas de ella**. Usa Gradle y las herramientas del SDK de Android Studio (`adb`, `sdkmanager`). Las skills se copiaron a mano.

## 2. Piezas que construye este agente

### 2.1 Proyecto Gradle reproducible
- **Gradle wrapper** con `distributionSha256Sum` fijado.
- Versiones fijas (sin `+` ni rangos) en `gradle/libs.versions.toml`.
- **Verificación de dependencias:** `gradle/verification-metadata.xml` con hashes SHA-256. Si una librería cambia, la compilación falla. Esto protege contra ataques a la cadena de suministro.
- JDK, NDK y CMake con versión fija, documentada en `docs/build.md` (lo crea este agente en la fase 1a).
- Sin marcas de tiempo ni rutas absolutas dentro del APK (requisito de builds reproducibles).

**Glosario**
- **Gradle:** el "maestro de obra" que compila el proyecto.
- **NDK:** kit para compilar código C/C++ para Android.
- **Build reproducible:** compilar dos veces el mismo código da exactamente el mismo archivo. Así F-Droid puede comprobar que el APK de Juan sale del código público.

### 2.2 Motor OPUS nativo: CTranslate2 para Android ⭐ (fase 1)
**El mayor riesgo técnico del proyecto.** CTranslate2 no publica un paquete para Android, pero hay reportes de compilación exitosa en ARM64 (en Termux).

Plan:
1. Compilar CTranslate2 con el NDK para `arm64-v8a` (y `armeabi-v7a` solo si sale fácil).
   - Punto de partida, a verificar: `-DWITH_MKL=OFF`, `-DWITH_RUY=ON` (backend de matrices optimizado para ARM), `-DOPENMP_RUNTIME=COMP` o `NONE`, `-DBUILD_CLI=OFF`, `-DCMAKE_POLICY_VERSION_MINIMUM=3.5`.
   - Usar `-ffile-prefix-map` para no filtrar rutas locales en el binario.
2. Compilar **SentencePiece** (tokenizador de los modelos) con el NDK.
3. Escribir `libct2bridge.so`: un puente JNI (*Java Native Interface*, el "enchufe" entre Kotlin y C++) mínimo:
   - `load(modelDir, threads, beam)`, `translate(String[]) → String[]`, `unload()`.
   - Tokenizar y destokenizar con SentencePiece **dentro** del puente.
   - Validar entradas: longitud máxima por oración (p. ej. 1.000 caracteres), UTF-8 válido, nada de punteros colgantes. Revisión obligatoria de SEGURIDAD.
4. **Compilar en GitHub Actions** (Linux), no en la laptop de Juan: así el resultado es reproducible y Juan no necesita toolchains de C++ en Windows.
5. Entregar los `.so` como artefacto de CI y en `engine/opus/src/main/jniLibs/` vía tarea Gradle.

**Puerta (con estructura):** el benchmark de la fase 1 da ≥ 15 palabras/s en el Pixel 7.

**Plan B:** ONNX Runtime para Android (paquete oficial) + modelo exportado con Hugging Face Optimum. Medir lo mismo y decidir con datos.

### 2.3 Motor Firefox nativo (fase 2)
- Revisar cómo Offline Translator (`github.com/DavidVentura/offline-translator`, GPL-3.0) compila su motor para los modelos de Firefox y replicarlo (o reutilizar su módulo).
- Modelos desde el registro oficial de Mozilla (MPL-2.0). Mirarlos tal cual, sin modificar.

### 2.4 Tubería de modelos (fase 1-2)
**Regla:** nunca usar modelos convertidos por terceros. En las pruebas usamos una conversión de un usuario desconocido de Hugging Face; para la app, **convertimos nosotros desde la fuente oficial.**

Script `tools/models/convert_opus.py`:
1. Descarga `Helsinki-NLP/opus-mt-tc-big-en-es` (CC-BY-4.0, ~0,2 mil millones de parámetros).
2. Convierte con CTranslate2: `ct2-transformers-converter --model Helsinki-NLP/opus-mt-tc-big-en-es --output_dir out/en-es --quantization int8 --copy_files source.spm target.spm`.
3. Empaqueta `en-es.tar.zst` + `LICENSE` (CC-BY-4.0) + `ATTRIBUTION.txt`.
4. Calcula SHA-256.
5. Corre el **set de 25 frases** y guarda las salidas como "salidas de referencia" (pruebas de regresión de calidad).

**Catálogo de modelos** (`catalog.json`):
```json
{
  "version": 1,
  "models": [
    {
      "pair": "en-es",
      "engine": "opus",
      "modelVersion": "tc-big-2026.10",
      "url": "https://github.com/<usuario>/lector-bilingue-modelos/releases/download/en-es-v1/en-es.tar.zst",
      "sizeBytes": 0,
      "sha256": "…",
      "license": "CC-BY-4.0",
      "attribution": "Helsinki-NLP / OPUS-MT, Tiedemann et al."
    }
  ]
}
```
- El catálogo va **firmado** (Ed25519 con `minisign` o `signify`). La app trae la clave pública incrustada y **rechaza** un catálogo sin firma válida.
- Alojamiento: **GitHub Releases** de un repo aparte `lector-bilingue-modelos`. Evitar depender de servicios que F-Droid marque como *NonFreeNet*.
- Pares iniciales: `en-es` (OPUS + Firefox) y `es-en` (OPUS + Firefox). El resto, Firefox primero y OPUS donde exista *tc-big*.

### 2.5 Integración continua (GitHub Actions)
Flujo `ci.yml` en cada PR:
1. `./gradlew lint test` (unitarias).
2. Compilar APK `fdroidRelease` **sin firmar**.
3. Verificar dependencias (falla si cambia un hash).
4. EPUBCheck sobre un EPUB bilingüe exportado de prueba (desde la fase 4).
5. Subir el APK como artefacto.

Flujo `native.yml` (cuando cambian `native/`):
- Compila CTranslate2 + SentencePiece + puente y publica los `.so` como artefacto con su SHA-256.

Flujo `release.yml` (al crear una etiqueta `v*`):
- Compila el APK sin firmar dos veces en máquinas limpias y compara: si difieren, **falla** (control de reproducibilidad).

### 2.6 Firma y publicación (fase 6)
- **Keystore** (el archivo con la llave de firma): lo crea **Juan**, en su máquina, con contraseña fuerte. Nunca en el repo ni en la nube. Dos copias de respaldo offline (USB + otro lugar).
- Firma local por Juan con `apksigner` sobre el APK reproducible de CI.
- **F-Droid:** metadatos en `fastlane/metadata/android/es-419/` y `en-US/` (descripción, capturas, changelogs). Envío como *merge request* a `fdroiddata` en GitLab, pidiendo publicar con la firma del desarrollador (builds reproducibles).
- **Google Play:** flavor `play`, AAB firmado. Registrar el paquete en la verificación de desarrolladores de Google con la cuenta de Juan.
- **Cambio de firma = desastre:** si se pierde el keystore, los usuarios no pueden actualizar. Por eso los respaldos.

## 3. Tareas por fase (resumen)

| Fase | Tareas |
|---|---|
| 1a | Gradle reproducible, `libs.versions.toml`, CI básico (lint + test) |
| 1b | CTranslate2 + SentencePiece + puente JNI para arm64; script de conversión del modelo en-es; instrucciones para copiar el modelo al Pixel por `adb` |
| 2 | Catálogo firmado; repo de modelos; motor Firefox nativo; pares es-en |
| 3-5 | Mantener CI; EPUBCheck; benchmarks automáticos en el Pixel cuando esté conectado |
| 6 | Reproducibilidad verificada, metadatos fastlane, guía de firma para Juan, envío a F-Droid y Play |

## 4. Qué le pides a Juan
- **Fase 1:** conectar el Pixel por USB con depuración activada; correr el comando `adb push` que le des.
- **Fase 2:** crear el repo `lector-bilingue-modelos` y generar la llave de firma del catálogo (le das el comando exacto; la **llave privada** la guarda él).
- **Fase 6:** crear el keystore, firmar el APK y enviar a F-Droid y Play siguiendo tu guía paso a paso.

## Fuentes
- Android Skills oficiales: https://github.com/android/skills
- Android CLI: https://android-developers.googleblog.com/2026/04/build-android-apps-3x-faster-using-any-agent.html
- Compilar CTranslate2 en Android (issue #1683): https://github.com/OpenNMT/CTranslate2/issues/1683
- Modelo OPUS-MT tc-big en-es: https://huggingface.co/Helsinki-NLP/opus-mt-tc-big-en-es
- F-Droid, builds reproducibles: https://f-droid.org/docs/Reproducible_Builds/
