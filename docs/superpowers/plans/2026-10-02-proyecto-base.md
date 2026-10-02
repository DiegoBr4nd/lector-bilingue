# Proyecto base (Fase 1a) · Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Proyecto Android multi-módulo que compila de forma reproducible, pasa lint y pruebas en CI, y se instala vacío en el Pixel 7.

**Architecture:** Gradle con *version catalog* y plugins de convención en un *included build* (`build-logic`). Cuatro módulos: `:app` (Compose), `:core:text` y `:engine:api` (Kotlin JVM), `:engine:opus` (Android library). CI en GitHub Actions con acciones fijadas por hash.

**Tech Stack:** Gradle 9.8.0 · AGP 9.4.1 (Kotlin integrado) · Kotlin 2.4.20 · Compose BOM 2026.09.00 (Material 3 1.4.0) · activity-compose 1.13.0 · core-ktx 1.19.1 · JUnit 4.13.2 + kotlin-test.

**Spec:** `docs/superpowers/specs/2026-10-02-proyecto-base-design.md`

**Ejecución:** subagent-driven. Juan asignó los subagentes: Tareas 1, 2, 6, 7 → `infraestructura`; Tareas 3, 4, 5 → `estructura`; Tarea 8 → `seguridad` + `diseno` (solo lectura). La Tarea 9 la hace la sesión principal con Juan.

## Global Constraints

- Paquete y `applicationId`: `io.github.diegobr4nd.lectorbilingue`.
- `minSdk 26`, `compileSdk 37`, `targetSdk 37`.
- Bytecode Java/Kotlin 17 (`JavaVersion.VERSION_17`, `JvmTarget.JVM_17`). **Aclaración sobre la spec:** no se usan *toolchains* de Gradle, porque la máquina de Juan solo tiene el JBR 25 de Android Studio y un toolchain 17 obligaría a descargar otro JDK. Local: Gradle corre con `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`. CI: Temurin 17.
- Versiones exactas, sin `+` ni rangos, todas en `gradle/libs.versions.toml`.
- Flavors `fdroid` y `play`, dimensión `distribution`, solo en `:app`.
- Prohibido: Firebase, Play Services, AdMob, Crashlytics, analítica, MuPDF, NLLB, dependencias no libres. Ninguna dependencia fuera de las listadas en Tech Stack.
- Cero `<uses-permission>` en el manifest fusionado.
- Nunca registrar (log) texto de libros ni traducciones.
- `EngineConfig` por defecto: `beamSize = 1`, `threads = 4`.
- Commits con Conventional Commits, terminando en `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Rama `chore/proyecto-base`; nunca `main`.
- Todo comando Gradle en Git Bash se corre como: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew <tareas>` desde la raíz del repo.

## Review Focus

1. `gradlew` con fin de línea CRLF → en el CI de Linux falla con `/usr/bin/env: 'sh\r'`. Esperado: `.gitattributes` fuerza LF; la Tarea 1 lo comprueba con `git ls-files --eol gradlew`.
2. Artefactos que dependen del sistema operativo (`aapt2` de Windows vs Linux) ausentes en `verification-metadata.xml` → el CI falla aunque local pase. Esperado: la Tarea 6 resuelve `aapt2` para los tres sistemas al generar las huellas.
3. Una dependencia agrega permisos al manifest fusionado sin que nadie lo note. Esperado: el CI falla si aparece `uses-permission` (Tarea 7).
4. El flavor `play` deja de compilar porque solo se prueba `fdroid`. Esperado: la Tarea 5 compila `assemblePlayDebug` y el CI corre `assemblePlayRelease`.
5. R8 rompe el arranque del build release. Esperado: la Tarea 5 compila `assembleFdroidRelease`; la verificación en el teléfono en 1a es con debug (el release sin firmar no se instala), y queda anotado como riesgo para la fase 6.

---

### Task 1: Wrapper de Gradle, `.gitattributes` y `.gitignore` (agente `infraestructura`)

**Files:**
- Create: `.gitattributes`, `.gitignore`, `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`

**Interfaces:**
- Produces: `./gradlew` funcional con Gradle 9.8.0 y `distributionSha256Sum` fijado.

- [ ] **Step 1: Crear `.gitattributes`**

```gitattributes
* text=auto eol=lf
*.bat text eol=crlf
*.jar binary
*.png binary
*.webp binary
```

- [ ] **Step 2: Crear `.gitignore`**

```gitignore
.gradle/
build/
/local.properties
.idea/
*.iml
.kotlin/
captures/
.externalNativeBuild/
.cxx/
*.keystore
*.jks
```

- [ ] **Step 3: Descargar Gradle 9.8.0 al scratchpad y verificar su SHA-256**

Run (Git Bash; `$SCRATCH` = scratchpad de la sesión):
```bash
curl -sSLo "$SCRATCH/gradle-9.8.0-bin.zip" https://services.gradle.org/distributions/gradle-9.8.0-bin.zip
echo "bafd5ce9cfaea0fbccfdc8439a1ac42fbd4cd9c89dc9a988228d8a2639a58e6c  $SCRATCH/gradle-9.8.0-bin.zip" | sha256sum -c -
```
Expected: `OK`. Si no dice OK, detenerse y avisar.

- [ ] **Step 4: Generar el wrapper**

```bash
cd "$SCRATCH" && unzip -q gradle-9.8.0-bin.zip
cd /c/Users/JUAN/Documents/lector-bilingue
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" "$SCRATCH/gradle-9.8.0/bin/gradle" wrapper \
  --gradle-version 9.8.0 --distribution-type bin \
  --gradle-distribution-sha256-sum bafd5ce9cfaea0fbccfdc8439a1ac42fbd4cd9c89dc9a988228d8a2639a58e6c
```
Nota: sin `settings.gradle.kts` Gradle puede quejarse; si pasa, crear antes un `settings.gradle.kts` con solo `rootProject.name = "lector-bilingue"` (la Tarea 2 lo reemplaza).

- [ ] **Step 5: Verificar**

```bash
grep distributionSha256Sum gradle/wrapper/gradle-wrapper.properties
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew --version
git add -A && git ls-files --eol gradlew gradlew.bat
```
Expected: la línea con `bafd5ce9…`; `Gradle 9.8.0`; `gradlew` con `i/lf`, `gradlew.bat` con `i/crlf`.

- [ ] **Step 6: Commit**

```bash
git add .gitattributes .gitignore gradlew gradlew.bat gradle/wrapper settings.gradle.kts 2>/dev/null
git commit -m "build: wrapper de Gradle 9.8.0 con SHA-256 y reglas de fin de línea"
```

---

### Task 2: Catálogo de versiones, settings y plugins de convención (agente `infraestructura`)

**Files:**
- Create: `gradle/libs.versions.toml`, `settings.gradle.kts` (reemplaza), `build.gradle.kts`, `gradle.properties`
- Create: `build-logic/settings.gradle.kts`, `build-logic/convention/build.gradle.kts`
- Create: `build-logic/convention/src/main/kotlin/ProjectConfig.kt`
- Create: `build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt`
- Create: `build-logic/convention/src/main/kotlin/AndroidLibraryConventionPlugin.kt`
- Create: `build-logic/convention/src/main/kotlin/AndroidComposeConventionPlugin.kt`
- Create: `build-logic/convention/src/main/kotlin/JvmLibraryConventionPlugin.kt`

**Interfaces:**
- Produces: ids de plugin `lectorbilingue.android.application`, `lectorbilingue.android.library`, `lectorbilingue.android.compose`, `lectorbilingue.jvm.library`. Alias del catálogo usados por las Tareas 3-5: `libs.androidx.core.ktx`, `libs.androidx.activity.compose`, `libs.androidx.compose.bom`, `libs.androidx.compose.material3`, `libs.androidx.compose.ui`, `libs.androidx.compose.ui.tooling.preview`, `libs.androidx.compose.ui.tooling`, `libs.kotlin.test.junit`, `libs.junit`.

- [ ] **Step 1: `gradle/libs.versions.toml`**

```toml
[versions]
agp = "9.4.1"
kotlin = "2.4.20"
composeBom = "2026.09.00"
activityCompose = "1.13.0"
coreKtx = "1.19.1"
junit = "4.13.2"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }
kotlin-test-junit = { group = "org.jetbrains.kotlin", name = "kotlin-test-junit", version.ref = "kotlin" }
junit = { group = "junit", name = "junit", version.ref = "junit" }

# Solo para build-logic
android-gradlePlugin = { group = "com.android.tools.build", name = "gradle", version.ref = "agp" }
kotlin-gradlePlugin = { group = "org.jetbrains.kotlin", name = "kotlin-gradle-plugin", version.ref = "kotlin" }
compose-gradlePlugin = { group = "org.jetbrains.kotlin", name = "compose-compiler-gradle-plugin", version.ref = "kotlin" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

- [ ] **Step 2: `settings.gradle.kts` (raíz)**

```kotlin
pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "lector-bilingue"

include(":app")
include(":core:text")
include(":engine:api")
include(":engine:opus")
```

- [ ] **Step 3: `build.gradle.kts` (raíz)** — pone AGP y Kotlin 2.4.20 en el classpath para que los plugins de convención los apliquen.

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.compiler) apply false
}
```

- [ ] **Step 4: `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.configuration-cache=true
org.gradle.parallel=true
android.useAndroidX=true
kotlin.code.style=official
```

- [ ] **Step 5: `build-logic/settings.gradle.kts`**

```kotlin
dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
include(":convention")
```

- [ ] **Step 6: `build-logic/convention/build.gradle.kts`**

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

group = "io.github.diegobr4nd.lectorbilingue.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "lectorbilingue.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "lectorbilingue.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "lectorbilingue.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("jvmLibrary") {
            id = "lectorbilingue.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
    }
}
```

- [ ] **Step 7: `ProjectConfig.kt`**

```kotlin
import org.gradle.api.JavaVersion

/** Valores compartidos por todos los módulos. Cambiar aquí cambia todo el proyecto. */
object ProjectConfig {
    const val COMPILE_SDK = 37
    const val TARGET_SDK = 37
    const val MIN_SDK = 26
    val JAVA_VERSION = JavaVersion.VERSION_17
}
```

- [ ] **Step 8: `AndroidApplicationConventionPlugin.kt`**

```kotlin
import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")

        extensions.configure<ApplicationExtension> {
            compileSdk = ProjectConfig.COMPILE_SDK
            defaultConfig {
                minSdk = ProjectConfig.MIN_SDK
                targetSdk = ProjectConfig.TARGET_SDK
            }
            compileOptions {
                sourceCompatibility = ProjectConfig.JAVA_VERSION
                targetCompatibility = ProjectConfig.JAVA_VERSION
            }

            flavorDimensions += "distribution"
            productFlavors {
                create("fdroid") { dimension = "distribution" }
                create("play") { dimension = "distribution" }
            }

            buildTypes {
                getByName("release") {
                    isMinifyEnabled = true
                    proguardFiles(
                        getDefaultProguardFile("proguard-android-optimize.txt"),
                        "proguard-rules.pro",
                    )
                    vcsInfo.include = false
                }
            }

            // F-Droid: sin el bloque cifrado de dependencias que solo Google puede leer.
            dependenciesInfo {
                includeInApk = false
                includeInBundle = false
            }

            lint {
                abortOnError = true
                checkDependencies = true
            }
        }
    }
}
```

- [ ] **Step 9: `AndroidLibraryConventionPlugin.kt`**

```kotlin
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")

        extensions.configure<LibraryExtension> {
            compileSdk = ProjectConfig.COMPILE_SDK
            defaultConfig {
                minSdk = ProjectConfig.MIN_SDK
            }
            compileOptions {
                sourceCompatibility = ProjectConfig.JAVA_VERSION
                targetCompatibility = ProjectConfig.JAVA_VERSION
            }
            lint {
                abortOnError = true
            }
        }
    }
}
```

- [ ] **Step 10: `AndroidComposeConventionPlugin.kt`**

```kotlin
import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        extensions.configure<ApplicationExtension> {
            buildFeatures {
                compose = true
            }
        }

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        val bom = libs.findLibrary("androidx-compose-bom").get()
        dependencies {
            add("implementation", platform(bom))
            add("androidTestImplementation", platform(bom))
        }
    }
}
```

- [ ] **Step 11: `JvmLibraryConventionPlugin.kt`**

```kotlin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = ProjectConfig.JAVA_VERSION
            targetCompatibility = ProjectConfig.JAVA_VERSION
        }
        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        dependencies {
            add("testImplementation", libs.findLibrary("kotlin-test-junit").get())
            add("testImplementation", libs.findLibrary("junit").get())
        }
    }
}
```

- [ ] **Step 12: Verificar que `build-logic` compila**

Crear temporalmente las carpetas vacías de los módulos no hace falta: Gradle acepta proyectos incluidos sin `build.gradle.kts`.

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :build-logic:convention:assemble projects`
Expected: `BUILD SUCCESSFUL` y la lista con `:app`, `:core:text`, `:engine:api`, `:engine:opus`. Si AGP 9.4.1 reporta que alguna propiedad del DSL cambió (p. ej. `minSdk`, `vcsInfo`), consultar `.claude/skills/agp-9-upgrade/` y usar la forma nueva indicada por el error; anotar el cambio en el reporte.

- [ ] **Step 13: Commit**

```bash
git add gradle/libs.versions.toml settings.gradle.kts build.gradle.kts gradle.properties build-logic
git commit -m "build: catálogo de versiones y plugins de convención"
```

---

### Task 3: `:engine:api` con el contrato del motor, guiado por pruebas (agente `estructura`)

**Files:**
- Create: `engine/api/build.gradle.kts`
- Create: `engine/api/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/engine/api/TranslationEngine.kt`
- Create: `engine/api/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/engine/api/EngineConfig.kt`
- Create: `engine/api/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/engine/api/LanguagePair.kt`
- Test: `engine/api/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/engine/api/EngineConfigTest.kt`
- Test: `engine/api/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/engine/api/LanguagePairTest.kt`

**Interfaces:**
- Consumes: plugin `lectorbilingue.jvm.library` (Tarea 2).
- Produces (paquete `io.github.diegobr4nd.lectorbilingue.engine.api`):
  - `interface TranslationEngine { val id: String; suspend fun load(pair: LanguagePair, config: EngineConfig); suspend fun translate(sentences: List<String>): List<String>; fun unload() }`
  - `data class EngineConfig(val beamSize: Int = 1, val threads: Int = 4)`
  - `data class LanguagePair(val source: String, val target: String)`

- [ ] **Step 1: `engine/api/build.gradle.kts`**

```kotlin
plugins {
    id("lectorbilingue.jvm.library")
}
```

- [ ] **Step 2: Escribir las pruebas que fallan**

`EngineConfigTest.kt`:
```kotlin
package io.github.diegobr4nd.lectorbilingue.engine.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EngineConfigTest {
    @Test
    fun `por defecto usa beam 1 y 4 hilos`() {
        val config = EngineConfig()
        assertEquals(1, config.beamSize)
        assertEquals(4, config.threads)
    }

    @Test
    fun `rechaza cero hilos`() {
        assertFailsWith<IllegalArgumentException> { EngineConfig(threads = 0) }
    }

    @Test
    fun `rechaza beam cero`() {
        assertFailsWith<IllegalArgumentException> { EngineConfig(beamSize = 0) }
    }
}
```

`LanguagePairTest.kt`:
```kotlin
package io.github.diegobr4nd.lectorbilingue.engine.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LanguagePairTest {
    @Test
    fun `acepta un par valido`() {
        val pair = LanguagePair("en", "es")
        assertEquals("en", pair.source)
        assertEquals("es", pair.target)
    }

    @Test
    fun `rechaza origen vacio`() {
        assertFailsWith<IllegalArgumentException> { LanguagePair(" ", "es") }
    }

    @Test
    fun `rechaza destino vacio`() {
        assertFailsWith<IllegalArgumentException> { LanguagePair("en", "") }
    }

    @Test
    fun `rechaza origen igual a destino`() {
        assertFailsWith<IllegalArgumentException> { LanguagePair("en", "en") }
    }
}
```

- [ ] **Step 3: Correr y ver que fallan**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :engine:api:test`
Expected: FAIL de compilación: `Unresolved reference 'EngineConfig'` y `'LanguagePair'`.

- [ ] **Step 4: Implementación mínima**

`EngineConfig.kt`:
```kotlin
package io.github.diegobr4nd.lectorbilingue.engine.api

/**
 * Ajustes del motor.
 * threads: nunca más que los núcleos rápidos; en el Pixel 7, 8 hilos es 4 veces más lento que 4.
 */
data class EngineConfig(
    val beamSize: Int = 1,
    val threads: Int = 4,
) {
    init {
        require(beamSize >= 1) { "beamSize debe ser >= 1" }
        require(threads >= 1) { "threads debe ser >= 1" }
    }
}
```

`LanguagePair.kt`:
```kotlin
package io.github.diegobr4nd.lectorbilingue.engine.api

/** Par de idiomas con códigos ISO 639-1, por ejemplo en → es. */
data class LanguagePair(val source: String, val target: String) {
    init {
        require(source.isNotBlank()) { "source vacío" }
        require(target.isNotBlank()) { "target vacío" }
        require(source != target) { "source y target deben ser distintos" }
    }
}
```

`TranslationEngine.kt`:
```kotlin
package io.github.diegobr4nd.lectorbilingue.engine.api

/** Contrato común de todos los motores (OPUS, Firefox). Un solo motor cargado a la vez. */
interface TranslationEngine {
    val id: String
    suspend fun load(pair: LanguagePair, config: EngineConfig)
    suspend fun translate(sentences: List<String>): List<String>
    fun unload()
}
```

- [ ] **Step 5: Correr y ver que pasan**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :engine:api:test`
Expected: `BUILD SUCCESSFUL`; 7 pruebas pasan (ver `engine/api/build/test-results/test/*.xml`).

- [ ] **Step 6: Commit**

```bash
git add engine/api
git commit -m "feat(engine-api): contrato TranslationEngine, EngineConfig y LanguagePair"
```

---

### Task 4: Módulos `:core:text` y `:engine:opus` vacíos (agente `estructura`)

**Files:**
- Create: `core/text/build.gradle.kts`
- Create: `core/text/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/core/text/package-info.kt`
- Create: `engine/opus/build.gradle.kts`
- Create: `engine/opus/src/main/AndroidManifest.xml`
- Create: `engine/opus/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/engine/opus/package-info.kt`

**Interfaces:**
- Consumes: `lectorbilingue.jvm.library`, `lectorbilingue.android.library` (Tarea 2); `:engine:api` (Tarea 3).
- Produces: proyectos `:core:text` (JVM) y `:engine:opus` (Android library, namespace `io.github.diegobr4nd.lectorbilingue.engine.opus`) que `:app` puede declarar como dependencias.

- [ ] **Step 1: `core/text/build.gradle.kts`**

```kotlin
plugins {
    id("lectorbilingue.jvm.library")
}
```

- [ ] **Step 2: `core/text/.../package-info.kt`**

```kotlin
/**
 * Utilidades de texto: partir párrafos en oraciones y normalizar.
 * Se implementa en la fase 1b (ver docs/agentes/01-estructura.md §4).
 */
package io.github.diegobr4nd.lectorbilingue.core.text
```

- [ ] **Step 3: `engine/opus/build.gradle.kts`**

```kotlin
plugins {
    id("lectorbilingue.android.library")
}

android {
    namespace = "io.github.diegobr4nd.lectorbilingue.engine.opus"
}

dependencies {
    api(project(":engine:api"))
}
```

- [ ] **Step 4: `engine/opus/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 5: `engine/opus/.../package-info.kt`**

```kotlin
/**
 * Motor OPUS-MT vía CTranslate2 (puente JNI). Se implementa en la fase 1b.
 */
package io.github.diegobr4nd.lectorbilingue.engine.opus
```

- [ ] **Step 6: Verificar**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :core:text:build :engine:opus:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add core/text engine/opus
git commit -m "feat: módulos core:text y engine:opus vacíos"
```

---

### Task 5: `:app` con pantalla vacía, manifest endurecido y configuración de red (agente `estructura`)

**Files:**
- Create: `app/build.gradle.kts`, `app/proguard-rules.pro`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/strings.xml`, `app/src/main/res/values/themes.xml`, `app/src/main/res/values/colors.xml`
- Create: `app/src/main/res/xml/network_security_config.xml`, `app/src/main/res/xml/data_extraction_rules.xml`
- Create: `app/src/main/res/drawable/ic_launcher_foreground.xml`, `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Create: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/MainActivity.kt`
- Create: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/theme/Theme.kt`
- Create: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/HomeScreen.kt`

**Interfaces:**
- Consumes: plugins `lectorbilingue.android.application` y `lectorbilingue.android.compose`; proyectos `:engine:api`, `:engine:opus`, `:core:text`; alias del catálogo de la Tarea 2.
- Produces: APK `fdroidDebug` instalable; `@Composable fun LectorBilingueTheme(content: @Composable () -> Unit)`; `@Composable fun HomeScreen(modifier: Modifier = Modifier)`.

- [ ] **Step 1: `app/build.gradle.kts`**

```kotlin
plugins {
    id("lectorbilingue.android.application")
    id("lectorbilingue.android.compose")
}

android {
    namespace = "io.github.diegobr4nd.lectorbilingue"
    defaultConfig {
        applicationId = "io.github.diegobr4nd.lectorbilingue"
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation(project(":core:text"))
    implementation(project(":engine:api"))
    implementation(project(":engine:opus"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
```

- [ ] **Step 2: `app/proguard-rules.pro`**

```proguard
# Reglas de R8 propias de la app. Vacío en la fase 1a.
# En la fase 1b se agregan las reglas para mantener los métodos JNI de :engine:opus.
```

- [ ] **Step 3: `AndroidManifest.xml`** (sin ningún `<uses-permission>`)

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <application
        android:allowBackup="false"
        android:fullBackupContent="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:networkSecurityConfig="@xml/network_security_config"
        android:usesCleartextTraffic="false"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.LectorBilingue"
        tools:targetApi="31">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 4: Recursos XML**

`res/values/strings.xml`:
```xml
<resources>
    <string name="app_name">Lector bilingüe</string>
</resources>
```

`res/values/colors.xml`:
```xml
<resources>
    <color name="ic_launcher_background">#1B4D89</color>
</resources>
```

`res/values/themes.xml` (tema de ventana sin AppCompat; Compose dibuja el resto):
```xml
<resources>
    <style name="Theme.LectorBilingue" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

`res/xml/network_security_config.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Solo HTTPS y solo certificados del sistema. La app no tiene permiso de internet en la fase 1a. -->
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

`res/xml/data_extraction_rules.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Libros y traducciones nunca salen del teléfono: nada va a la nube ni a otro dispositivo. -->
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="root" path="." />
        <exclude domain="file" path="." />
        <exclude domain="database" path="." />
        <exclude domain="sharedpref" path="." />
        <exclude domain="external" path="." />
    </cloud-backup>
    <device-transfer>
        <exclude domain="root" path="." />
        <exclude domain="file" path="." />
        <exclude domain="database" path="." />
        <exclude domain="sharedpref" path="." />
        <exclude domain="external" path="." />
    </device-transfer>
</data-extraction-rules>
```

`res/drawable/ic_launcher_foreground.xml` (libro abierto simple, provisional hasta que DISEÑO entregue el ícono):
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M30,38 L52,42 L52,72 L30,68 Z M56,42 L78,38 L78,68 L56,72 Z" />
</vector>
```

`res/mipmap-anydpi-v26/ic_launcher.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

- [ ] **Step 5: Tema Compose** (`ui/theme/Theme.kt`)

```kotlin
package io.github.diegobr4nd.lectorbilingue.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Tema provisional. DISEÑO lo reemplaza en :core:ui más adelante. */
@Composable
fun LectorBilingueTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
```

- [ ] **Step 6: Pantalla** (`ui/HomeScreen.kt`)

```kotlin
package io.github.diegobr4nd.lectorbilingue.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.github.diegobr4nd.lectorbilingue.R
import io.github.diegobr4nd.lectorbilingue.ui.theme.LectorBilingueTheme

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    LectorBilingueTheme { HomeScreen() }
}
```

- [ ] **Step 7: `MainActivity.kt`**

```kotlin
package io.github.diegobr4nd.lectorbilingue

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.diegobr4nd.lectorbilingue.ui.HomeScreen
import io.github.diegobr4nd.lectorbilingue.ui.theme.LectorBilingueTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LectorBilingueTheme {
                HomeScreen()
            }
        }
    }
}
```

- [ ] **Step 8: Compilar ambos flavors y release**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleFdroidDebug assemblePlayDebug assembleFdroidRelease`
Expected: `BUILD SUCCESSFUL`; existen `app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk`, `app/build/outputs/apk/play/debug/app-play-debug.apk` y `app/build/outputs/apk/fdroid/release/app-fdroid-release-unsigned.apk`.
Si `compileSdk = 37` no encuentra la plataforma (carpeta instalada `android-37.0`), revisar el error de AGP y usar la sintaxis que indique (p. ej. `compileSdk { version = release(37) }`) en `ProjectConfig`/convenciones; anotar el cambio.

- [ ] **Step 9: Verificar que el manifest fusionado no tiene permisos**

Run:
```bash
find app/build/intermediates -path '*fdroidRelease*' -name AndroidManifest.xml | xargs grep -c "uses-permission" || true
```
Expected: `0` en cada archivo listado (o ninguna coincidencia). Si AndroidX agrega `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (permiso interno con firma que crea `core`), **reportarlo** al orquestador en vez de quitarlo: lo decide la revisión de seguridad.

- [ ] **Step 10: Lint y pruebas de todo el proyecto**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew lint test`
Expected: `BUILD SUCCESSFUL`. Advertencias permitidas; errores no.

- [ ] **Step 11: Commit**

```bash
git add app
git commit -m "feat(app): pantalla inicial, manifest sin permisos y red solo HTTPS"
```

---

### Task 6: Verificación de dependencias con SHA-256 y `docs/build.md` (agente `infraestructura`)

**Files:**
- Modify: `build.gradle.kts` (raíz) — agrega la tarea `resolveAapt2AllPlatforms`
- Create: `gradle/verification-metadata.xml` (generado)
- Create: `docs/build.md`

**Interfaces:**
- Consumes: todo el build de las Tareas 1-5.
- Produces: `gradle/verification-metadata.xml` con `<verify-metadata>true</verify-metadata>`; cualquier artefacto sin huella hace fallar la compilación.

- [ ] **Step 1: Tarea que resuelve `aapt2` para Windows, Linux y macOS** (añadir al final de `build.gradle.kts` raíz)

```kotlin
// aapt2 es un binario distinto por sistema operativo. Esta tarea baja las tres variantes
// para que verification-metadata.xml tenga sus huellas y el CI de Linux no falle.
// Uso: ./gradlew --write-verification-metadata sha256 resolveAapt2AllPlatforms ...
val aapt2AllPlatforms: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    val aapt2 = "com.android.tools.build:aapt2:${libs.versions.agp.get()}-15978811"
    listOf("windows", "linux", "osx").forEach { os ->
        aapt2AllPlatforms("$aapt2:$os")
    }
}

tasks.register("resolveAapt2AllPlatforms") {
    val files = aapt2AllPlatforms
    doLast { files.resolve() }
}
```
Nota: `15978811` es el número de compilación de aapt2 que corresponde a AGP 9.4.1 (de `maven-metadata.xml` de Google). Al subir AGP hay que actualizarlo; se documenta en `docs/build.md`. Como el bloque `dependencies` de la raíz necesita un repositorio y `FAIL_ON_PROJECT_REPOS` lo impide en proyectos, los repositorios ya vienen de `settings.gradle.kts`. Si la configuración de caché se queja de `doLast { files.resolve() }`, cambiar a una tarea con `@InputFiles` o correr con `--no-configuration-cache` y anotarlo.

- [ ] **Step 2: Generar las huellas**

Run:
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew --write-verification-metadata sha256 \
  resolveAapt2AllPlatforms lint test assembleFdroidDebug assemblePlayDebug assembleFdroidRelease assemblePlayRelease
```
Expected: `BUILD SUCCESSFUL` y aparece `gradle/verification-metadata.xml`.

- [ ] **Step 3: Revisar el archivo**

```bash
grep -c "<component " gradle/verification-metadata.xml
grep -E "aapt2.*(linux|windows|osx)" gradle/verification-metadata.xml | head
grep "<verify-metadata>" gradle/verification-metadata.xml
```
Expected: cientos de componentes; tres archivos de aapt2 (linux, windows, osx); `<verify-metadata>true</verify-metadata>`.

- [ ] **Step 4: Comprobar que la verificación funciona** (prueba de "ROJO")

1. Copiar `gradle/verification-metadata.xml` al scratchpad.
2. Cambiar a mano un dígito del `sha256` de `junit-4.13.2.jar`.
3. Run: `JAVA_HOME=… ./gradlew :engine:api:test --rerun-tasks --refresh-dependencies`
   Expected: FAIL con `Dependency verification failed`.
4. Restaurar la copia y correr de nuevo. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Escribir `docs/build.md`**

```markdown
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
| JDK para correr Gradle | local: JBR de Android Studio (25); CI: Temurin 17 | — |

## Comandos (Git Bash, desde la raíz)
    export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
    ./gradlew assembleFdroidDebug      # compilar
    ./gradlew test                     # pruebas unitarias
    ./gradlew lint                     # revisión estática
    ./gradlew installFdroidDebug       # instalar en el Pixel por USB

En PowerShell: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat assembleFdroidDebug`.

## Verificación de dependencias
Cada librería tiene su huella SHA-256 en `gradle/verification-metadata.xml`. Si una cambia, la compilación falla.

Al agregar o actualizar una dependencia:
1. Cambiar la versión en `gradle/libs.versions.toml`.
2. Si cambió AGP, actualizar el número de aapt2 en `build.gradle.kts` (raíz) con el de
   `https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/maven-metadata.xml`.
3. Regenerar:
       ./gradlew --write-verification-metadata sha256 resolveAapt2AllPlatforms lint test assembleFdroidDebug assemblePlayDebug assembleFdroidRelease assemblePlayRelease
4. Revisar el diff de `verification-metadata.xml` en el PR. Solo deben aparecer los artefactos nuevos.
5. Pedir revisión del agente de seguridad (regla 8 de CLAUDE.md).

## Reproducibilidad
- Sin bloque de dependencias de Google en el APK (`dependenciesInfo`).
- Sin hash de git en el APK (`vcsInfo.include = false`).
- La comparación de dos compilaciones limpias llega en la fase 6 (`release.yml`).
```

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts gradle/verification-metadata.xml docs/build.md
git commit -m "build: verificación de dependencias con SHA-256 y guía de compilación"
```

---

### Task 7: CI en GitHub Actions (agente `infraestructura`)

**Files:**
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: `./gradlew` y tareas de las Tareas 1-6.
- Produces: check `ci / build` en cada PR; artefacto `app-fdroid-release-unsigned`.

- [ ] **Step 1: `.github/workflows/ci.yml`** (acciones fijadas por hash de commit; el comentario dice la versión)

```yaml
name: ci

on:
  pull_request:
  push:
    branches: [main]

permissions:
  contents: read

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build:
    runs-on: ubuntu-24.04
    timeout-minutes: 45
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
        with:
          persist-credentials: false

      - uses: gradle/actions/wrapper-validation@3f5f9adaf7d9fecd50b5935e54106014257a94e6 # v6.4.0

      - uses: actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6 # v6.0.1
        with:
          distribution: temurin
          java-version: "17"

      - uses: gradle/actions/setup-gradle@3f5f9adaf7d9fecd50b5935e54106014257a94e6 # v6.4.0
        with:
          cache-read-only: ${{ github.ref != 'refs/heads/main' }}

      - name: Lint, pruebas y APKs
        run: ./gradlew --no-daemon lint test assembleFdroidRelease assemblePlayRelease

      - name: El manifest fusionado no tiene permisos
        run: |
          files=$(find app/build/intermediates -path '*Release*' -name AndroidManifest.xml)
          test -n "$files"
          if grep -l "uses-permission" $files; then
            echo "::error::Apareció un permiso en el manifest fusionado"
            exit 1
          fi

      - name: Reporte de verificación de dependencias (si falló)
        if: failure()
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1
        with:
          name: dependency-verification-report
          path: build/reports/dependency-verification/
          if-no-files-found: ignore

      - uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1
        with:
          name: app-fdroid-release-unsigned
          path: app/build/outputs/apk/fdroid/release/app-fdroid-release-unsigned.apk
          if-no-files-found: error
```

- [ ] **Step 2: Confirmar que los hashes corresponden a las etiquetas**

Run:
```bash
for r in actions/checkout:v7.0.1 actions/setup-java:v6.0.1 gradle/actions:v6.4.0 actions/upload-artifact:v7.0.1; do
  repo=${r%%:*}; tag=${r##*:}
  echo "$repo $tag $(git ls-remote https://github.com/$repo refs/tags/$tag^{} refs/tags/$tag | head -1 | cut -f1)"
done
```
Expected: cada hash coincide con el del `ci.yml` (si la etiqueta es anotada, usar la línea `^{}`).

- [ ] **Step 3: Simular localmente el paso del manifest**

Run: `JAVA_HOME=… ./gradlew assembleFdroidRelease assemblePlayRelease` y luego el script del paso "El manifest fusionado no tiene permisos" en Git Bash.
Expected: termina con código 0 (sin salida de `grep -l`).

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: lint, pruebas y APK release en GitHub Actions"
```

---

### Task 8: Revisiones de seguridad y diseño (agentes `seguridad` y `diseno`, solo lectura)

**Files:** ninguno se modifica en esta tarea; los hallazgos se corrigen después por el agente dueño del archivo.

- [ ] **Step 1: `seguridad`** revisa, con las skills `android-permissions-security` y `android-intent-security`:
  - Manifest fusionado de `fdroidRelease` (`app/build/intermediates/.../AndroidManifest.xml`): permisos, componentes exportados, `allowBackup`, `dataExtractionRules`, `usesCleartextTraffic`.
  - `network_security_config.xml` y `data_extraction_rules.xml`.
  - `gradle/libs.versions.toml`, `settings.gradle.kts` (repositorios y filtros), `gradle/verification-metadata.xml`, `gradle-wrapper.properties`.
  - `.github/workflows/ci.yml` (permisos, acciones fijadas, `persist-credentials`, inyección en `run:`).
  - Entrega: lista de hallazgos con severidad (crítico/alto/medio/bajo) y archivo:línea.
- [ ] **Step 2: `diseno`** revisa `HomeScreen.kt`, `Theme.kt`, ícono y `MainActivity.kt` (borde a borde, *insets*, contraste, textos en recursos). Entrega: hallazgos breves.
- [ ] **Step 3:** Corregir hallazgos críticos/altos con el agente dueño; volver a correr `./gradlew lint test assembleFdroidDebug`; commit `fix: hallazgos de revisión de seguridad/diseño`. Hallazgos medios/bajos: se listan para Juan.

---

### Task 9: Puerta 1a con Juan (sesión principal)

- [ ] **Step 1:** `JAVA_HOME=… ./gradlew lint test assembleFdroidDebug` → mostrar la salida a Juan.
- [ ] **Step 2:** `adb devices` muestra el Pixel; `JAVA_HOME=… ./gradlew installFdroidDebug` → mostrar `Installed on 1 device`.
- [ ] **Step 3:** Juan abre "Lector bilingüe" en el Pixel y confirma que ve el texto centrado (captura opcional: `adb exec-out screencap -p > scratchpad/pantalla.png`).
- [ ] **Step 4:** Con permiso de Juan: `git push -u origin chore/proyecto-base`. Darle el enlace `https://github.com/DiegoBr4nd/lector-bilingue/compare/main...chore/proyecto-base?expand=1` y el texto del PR.
- [ ] **Step 5:** Esperar el CI. Si falla por huellas de Linux, descargar el artefacto `dependency-verification-report`, agregar las huellas faltantes, commit y push.
- [ ] **Step 6:** CI en verde → Juan fusiona y actualiza "Fase actual" en `CLAUDE.md`.
