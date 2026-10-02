# Spec · Fase 1a · Proyecto base

- **Fecha:** 2026-10-02
- **Rama:** `chore/proyecto-base`
- **Estado:** diseño aprobado por Juan en conversación; pendiente revisión de esta spec.
- **Agentes:** `infraestructura` (Gradle, CI), `estructura` (módulos, código), `seguridad` (revisión), `diseno` (revisión rápida de la pantalla).

## 1. Objetivo

Crear el proyecto Android base que compila, se prueba y se instala en el Pixel 7, con CI en GitHub Actions.

**Puerta 1a:** la app vacía se instala y abre en el Pixel 7, y el CI del PR está en verde.

### Fuera de alcance (llega después)
- Partidor de oraciones, puente JNI, CTranslate2 (fase 1b).
- Hilt, Room, DataStore, Navigation 3 (cuando se usen).
- `native.yml`, `release.yml` con doble compilación, EPUBCheck, firma.
- Permiso `INTERNET`.

## 2. Decisiones tomadas

| Tema | Decisión | Por qué |
|---|---|---|
| Nombre de paquete | `io.github.diegobr4nd.lectorbilingue` | Regla `io.github.<usuario>`; F-Droid lo acepta sin dominio propio. Permanente. |
| Organización de Gradle | Plugins de convención en `build-logic/` | Un solo lugar para la configuración común de ~15 módulos futuros. Patrón de *Now in Android*. |
| Flavors | `fdroid` y `play` (dimensión `distribution`), idénticos por ahora | `CLAUDE.md` usa `assembleFdroidDebug`; evita renombrar tareas después. |
| Versiones | Las últimas estables a la fecha (AGP 9.x, Kotlin 2.4.x, Gradle 9.x, Compose BOM), verificadas contra fuentes oficiales al implementar y fijadas sin `+` ni rangos | Reproducibilidad. |
| JDK | 17 vía toolchain de Gradle | Requisito de AGP 9 y Gradle 9. |
| SDK | `minSdk 26`; `compileSdk` y `targetSdk` = 37 (instalada en la máquina de Juan) | `contexto-y-decisiones.md` §5. |
| Dependencias de `:app` | Compose BOM, Material 3, `activity-compose`, `core-ktx` | YAGNI: nada que no se use en 1a. |
| Documentos sin commit | Movidos a la rama `docs/actualizar-guias` | Una tarea = una rama. |

## 3. Estructura

```
lector-bilingue/
├─ settings.gradle.kts            → incluye build-logic y los 4 módulos; repositorios fijos (google, mavenCentral)
├─ build.gradle.kts               → solo declara plugins (apply false)
├─ gradle.properties              → caché de configuración, sin daemon en CI
├─ gradle/
│  ├─ libs.versions.toml          → todas las versiones
│  ├─ verification-metadata.xml   → SHA-256 de cada artefacto (Windows + Linux)
│  └─ wrapper/gradle-wrapper.properties → con distributionSha256Sum
├─ gradlew, gradlew.bat
├─ build-logic/convention/        → plugins de convención
├─ app/                           → :app
├─ core/text/                     → :core:text
├─ engine/api/                    → :engine:api
├─ engine/opus/                   → :engine:opus
├─ .github/workflows/ci.yml
└─ docs/build.md
```

### 3.1 Plugins de convención (`build-logic/convention`)
| Id | Aplica a | Configura |
|---|---|---|
| `lectorbilingue.android.application` | `:app` | AGP application, compileSdk/minSdk/targetSdk, JDK 17, flavors `fdroid`/`play`, `dependenciesInfo` y `vcsInfo` desactivados, R8 en release |
| `lectorbilingue.android.library` | `:engine:opus` | AGP library, SDKs, JDK 17. Sin flavors: una librería sin flavors sirve a cualquier flavor de `:app` |
| `lectorbilingue.android.compose` | `:app` | Plugin del compilador de Compose + BOM |
| `lectorbilingue.jvm.library` | `:core:text`, `:engine:api` | Kotlin JVM, JDK 17, JUnit |

### 3.2 Módulos
| Módulo | Tipo | Contenido en 1a |
|---|---|---|
| `:app` | Android application | `MainActivity` con Compose; pantalla con el texto "Lector bilingüe" centrado; tema Material 3; borde a borde. Depende de `:engine:api`, `:engine:opus`, `:core:text`. |
| `:core:text` | Kotlin JVM | Paquete vacío con un archivo marcador. Se convertirá en módulo Android en 1b si necesita `android.icu`. |
| `:engine:api` | Kotlin JVM | `TranslationEngine`, `EngineConfig`, `LanguagePair` según `01-estructura.md` §2. |
| `:engine:opus` | Android library | Paquete vacío; namespace propio. Sin código nativo todavía. |

### 3.3 Contrato del motor (`:engine:api`)
```kotlin
interface TranslationEngine {
    val id: String
    suspend fun load(pair: LanguagePair, config: EngineConfig)
    suspend fun translate(sentences: List<String>): List<String>
    fun unload()
}

data class LanguagePair(val source: String, val target: String)

data class EngineConfig(
    val beamSize: Int = 1,
    val threads: Int = 4,
) {
    init {
        require(beamSize >= 1)
        require(threads >= 1)
    }
}
```
Pruebas (TDD, JUnit + kotlin.test):
- `EngineConfig()` → beam 1 y 4 hilos.
- `threads = 0` → `IllegalArgumentException`.
- `beamSize = 0` → `IllegalArgumentException`.
- `LanguagePair` rechaza códigos vacíos o idénticos (`en`→`en`).

`kotlinx-coroutines` no hace falta en `:engine:api`: `suspend` es del lenguaje.

## 4. Reproducibilidad y verificación

- Wrapper con `distributionSha256Sum` (SHA-256 oficial publicado por Gradle).
- `verification-metadata.xml` con `verify-metadata=true` y `verify-signatures=false`, generado con `--write-verification-metadata sha256`.
  - Debe incluir artefactos de Windows **y** Linux (p. ej. `aapt2`). Se genera en local y se completa en CI (o con una tarea que resuelva ambos) hasta que el CI pase sin descargas no verificadas.
- `dependenciesInfo { includeInApk = false; includeInBundle = false }`.
- `vcsInfo.include = false` en release.
- R8 (`isMinifyEnabled = true`) en release; sin reglas extra en 1a.
- Repositorios solo `google()` y `mavenCentral()`, con filtros de grupo en `google()`; `RepositoriesMode.FAIL_ON_PROJECT_REPOS`.
- `docs/build.md`: versiones de JDK, SDK, Gradle, AGP, Kotlin; cómo compilar, probar, instalar y regenerar la verificación.

## 5. CI (`.github/workflows/ci.yml`)

- Disparadores: `pull_request` y `push` a `main`.
- `permissions: contents: read`.
- `ubuntu-latest` → se fija la imagen (p. ej. `ubuntu-24.04`).
- Pasos:
  1. `actions/checkout` (fijada por hash de commit).
  2. `gradle/actions/wrapper-validation` (fijada por hash).
  3. `actions/setup-java` con Temurin 17 (fijada por hash).
  4. `gradle/actions/setup-gradle` (fijada por hash), sin caché de lectura/escritura en PRs de forks.
  5. `./gradlew lint test assembleFdroidRelease --no-daemon`.
  6. `actions/upload-artifact` con el APK release sin firmar (fijada por hash).
- Sin secretos.

## 6. Manifest y red

- Sin `<uses-permission>`. Se verifica en el manifest **fusionado** (*merged manifest*) que ninguna dependencia agrega permisos.
- `android:allowBackup="false"`, `android:fullBackupContent="false"`, `android:dataExtractionRules` excluyendo todo (nube y transferencia entre dispositivos).
- `android:usesCleartextTraffic="false"` y `android:networkSecurityConfig="@xml/network_security_config"` con `cleartextTrafficPermitted="false"` y solo CA del sistema.
- `MainActivity` es la única actividad `exported="true"`, con el filtro `MAIN/LAUNCHER`.
- Nada de `debuggable` en release; `android:localeConfig` no hace falta todavía.

## 7. Pantalla

- `MainActivity` llama `enableEdgeToEdge()` y `setContent { LectorBilingueTheme { … } }`.
- `Scaffold` con `Text("Lector bilingüe")` centrado, respetando *insets*.
- Tema Material 3 con colores dinámicos (Android 12+) y respaldo claro/oscuro.
- Textos en `strings.xml` (es por defecto) para no tener cadenas fijas en código.
- Revisión rápida de `diseno`.

## 8. Verificación y cierre

1. `./gradlew lint test assembleFdroidDebug` → salida mostrada a Juan.
2. `./gradlew installFdroidDebug` con el Pixel conectado → salida mostrada.
3. Juan abre la app en el Pixel y confirma.
4. Revisión del subagente `seguridad`: manifest fusionado, red, dependencias, `ci.yml`.
5. Push de la rama; Juan abre el PR en GitHub; CI en verde.
6. Juan actualiza "Fase actual" en `CLAUDE.md` al pasar la puerta.

## 9. Riesgos

| Riesgo | Mitigación |
|---|---|
| Huellas de dependencias distintas entre Windows y Linux | Generar/completar la verificación en ambos sistemas antes de pedir el CI verde |
| AGP 9 con `compileSdk 37` u otras incompatibilidades nuevas | El agente de infraestructura usa la skill `agp-9-upgrade` y verifica en las notas oficiales |
| Antivirus / Smart App Control de Windows bloquea herramientas | Usar el JDK de Android Studio (`jbr`) y Gradle wrapper; no depender de la Android CLI |
