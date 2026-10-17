# 6b · Compilación para Play — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Un AAB del flavor `play` listo para Play (firmable solo por Juan), una copia release `…prueba` para probar en el Pixel sin tocar sus datos, y la app auditada (MASVS, Material 3, Compose).

**Architecture:** Todo vive en `app/build.gradle.kts` (firma condicional desde un archivo fuera del repo, tipo de compilación `prueba`, exclusión de Divina, símbolos nativos) más comprobaciones en el CI. El enlace «Código fuente» pasa a calcularse desde `versionName`. Las auditorías son revisiones de toda la app que dejan informes en `docs/auditorias/` y una tarea de arreglos para lo crítico e importante.

**Tech Stack:** AGP 9.4.1, Gradle 9.8.1, Kotlin 2.4.21, R8, `keytool`/`jarsigner`/`apksigner`/`zipalign`/`llvm-readelf` del SDK/NDK, GitHub Actions, Compose M3.

**Spec:** `docs/superpowers/specs/2026-10-17-compilacion-play-6b-design.md`

## Global Constraints

- Reglas de `CLAUDE.md` siempre vigentes; comentarios en español como el resto del repo.
- **Regla 10:** las llaves de Juan (keystore de subida y sus contraseñas) solo las maneja Juan. Ningún archivo de llave, ruta real ni contraseña de Juan en el repo, el CI, los registros de Gradle ni los mensajes. Para probar la firma solo se usa una **llave desechable** creada en el scratchpad y borrada al terminar.
- Sin el archivo de firma, la compilación release sale **sin firmar y no falla**.
- `versionName = "0.9.0"`, `versionCode = 1`. Sin `versionNameSuffix` (el enlace al tag depende de `versionName`).
- Copia de prueba: `applicationIdSuffix = ".prueba"`, nombre visible «Lector (prueba)», firma de debug, R8 igual que release, sin pantalla de desarrollador.
- Enlace al código: `https://github.com/DiegoBr4nd/lector-bilingue/tree/v<versionName>`.
- AAB: solo `arm64-v8a`; `.so` alineadas a 16 KB; sin `assets/readium/divina/`; sin textos ni clases de la pantalla de desarrollador; mismos permisos que la lista del CI.
- Reglas de R8: solo reglas puntuales y justificadas; nunca `-dontshrink`/`-dontobfuscate`/`-dontoptimize` globales.
- Pixel: nunca `connectedAndroidTest`, `uninstall`, `pm clear`, `logcat -c`; comprobar en un comando aparte qué app está al frente antes de instalar o abrir; si es la de Juan (`io.github.diegobr4nd.lectorbilingue`), preguntar. `MSYS_NO_PATHCONV=1` con `adb`. Nunca instalar sobre `io.github.diegobr4nd.lectorbilingue` algo con otra firma.
- Gradle en Windows: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew …`. La lista de licencias se regenera en su propio comando.
- Commits con `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Archivo de firma con datos malos** (ruta inexistente, falta una clave): la compilación debe fallar con un mensaje claro que **no** muestre contraseñas → paso de comprobación en la Tarea 3.
2. **La copia `.prueba` choca con la app de Juan** (un `authorities` o permiso fijo): debe instalarse al lado → comprobación de manifest fusionado en la Tarea 3 (todo `authorities` contiene `.prueba`).
3. **Quitar Divina rompe el lector EPUB** (Readium cargaba algo de esa carpeta): el lector debe abrir igual → lote del Pixel del lector en la Tarea 2.
4. **R8 quita código que el motor nativo (JNI) o Readium usan por reflexión**: la copia `.prueba` debe abrir y traducir → apertura automática (Tarea 8) y lista de Juan.
5. **El CI pasa en vacío** (no encuentra el AAB y los `grep` no fallan): cada comprobación del CI tiene control positivo (Tarea 4).

---

### Task 1: Versión 0.9.0 y enlace al código de esa versión

**Files:**
- Modify: `app/build.gradle.kts` (`versionCode`, `versionName`)
- Modify: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/data/Licenses.kt`
- Modify: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/about/LicensesScreen.kt:~142-146`
- Test: `app/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/data/LicensesTest.kt`

**Interfaces:**
- Produces: `fun Licenses.sourceCodeUrl(versionName: String): String` → `"https://github.com/DiegoBr4nd/lector-bilingue/tree/v$versionName"`.

- [ ] **Step 1: Prueba que falla** — en `LicensesTest.kt`:
```kotlin
    @Test fun `el enlace al codigo apunta al tag de la version`() {
        assertEquals("https://github.com/DiegoBr4nd/lector-bilingue/tree/v0.9.0", Licenses.sourceCodeUrl("0.9.0"))
        // La fila de la app sigue guardando la raíz del repositorio; la pantalla añade el tag.
        assertTrue(Licenses.sourceCodeUrl("1.0.0").startsWith(entries.first().url!! + "/tree/"))
    }

    @Test fun `la version de la app es la de la prueba cerrada`() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(Regex("""versionName = "0\.9\.0"""").containsMatchIn(gradle), "versionName")
        assertTrue(Regex("""versionCode = 1\b""").containsMatchIn(gradle), "versionCode")
        assertFalse("versionNameSuffix" in gradle, "un sufijo rompería el enlace al tag")
    }
```
Run: `JAVA_HOME=… ./gradlew :app:testFdroidDebugUnitTest --tests "*LicensesTest"` → FAIL (no compila: falta `sourceCodeUrl`).

- [ ] **Step 2: Código** — en `object Licenses`:
```kotlin
    /** Código fuente de ESTA versión (GPL-3.0 §6): el tag `v<versionName>` que Juan crea al publicar. */
    fun sourceCodeUrl(versionName: String): String = "https://github.com/DiegoBr4nd/lector-bilingue/tree/v$versionName"
```
En `LicenseTextScreen`, para la fila `APP`: usar `Licenses.sourceCodeUrl(BuildConfig.VERSION_NAME)` en el `LinkButton` y en el texto mostrado (en vez de `s.entry.url`). `app/build.gradle.kts`: `versionCode = 1`, `versionName = "0.9.0"`.
Si `AboutOnDeviceTest.laLicenciaDeLaAppTraeElCodigoFuente` u otra prueba espera la URL raíz, cambiarla a la del tag.

- [ ] **Step 3:** Run LicensesTest → PASS; `./gradlew lint test` → BUILD SUCCESSFUL, lint 0.
- [ ] **Step 4: Commit** `feat(6b): versión 0.9.0; el código fuente enlaza al tag de la versión`.

---

### Task 2: Sin Divina en el APK ni en el AAB

**Files:**
- Modify: `app/build.gradle.kts` (bloque `android {}`)
- Modify: `app/src/main/assets/licencias/extra.json` (quitar fila `readium-divina`)
- Delete: `app/src/main/assets/licencias/avisos/readium-divina.txt`
- Modify: `docs/licencias/textos.md` (mover Divina a «Componentes revisados que NO van en la lista», motivo: excluido del APK)
- Modify: `app/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/data/LicensesTest.kt` (si nombra `readium-divina`)

- [ ] **Step 1: Medir antes** — `./gradlew assembleFdroidRelease` y `unzip -l app/build/outputs/apk/fdroid/release/app-fdroid-release-unsigned.apk | grep -c "assets/readium/divina/"` (> 0) y tamaño del APK.
- [ ] **Step 2: Excluir** — en `android {}`:
```kotlin
    // Readium trae su reproductor de cómics (Divina) en assets/readium/divina/; la app solo lee EPUB, así que no viaja.
    androidResources { ignoreAssetsPatterns += "<dir>divina" }
```
Si la sintaxis de AGP 9.4 no lo acepta o no excluye la carpeta de un AAR, usar la alternativa que funcione (p. ej. `aaptOptions`/`androidResources.ignoreAssetsPattern` con el patrón aapt `!divina` o un filtro de la Variant API sobre los assets fusionados) y anotarlo; **no** excluir nada más.
- [ ] **Step 3: Licencias** — quitar la fila y el aviso; `textos.md` actualizado. Regenerar la lista (`exportLibraryDefinitions` en su propio comando) y comprobar que `librerias.json` no cambia.
- [ ] **Step 4: Comprobar** — `./gradlew lint test assembleFdroidRelease assemblePlayRelease bundlePlayRelease`; `unzip -l` del APK y del AAB: 0 entradas `divina`; anotar tamaño antes/después.
- [ ] **Step 5: Lector en el Pixel** (app al frente comprobada en comando aparte): `installFdroidDebug installFdroidDebugAndroidTest`; `am instrument -w -e class io.github.diegobr4nd.lectorbilingue.ReaderOnDeviceTest,io.github.diegobr4nd.lectorbilingue.ReadiumBooksOnDeviceTest,io.github.diegobr4nd.lectorbilingue.ReadingSettingsOnDeviceTest io.github.diegobr4nd.lectorbilingue.test/androidx.test.runner.AndroidJUnitRunner` → OK. (El debug también excluye Divina: el patrón está en `android {}`.)
- [ ] **Step 6: Commit** `build(6b): sin el reproductor de cómics de Readium (Divina) en el APK`.

---

### Task 3: Firma condicional, copia `prueba` y símbolos nativos

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/prueba/res/values/strings.xml`
- Modify: `.gitignore` (si hace falta: `*.properties` de firma no viven en el repo; añadir `lector-subida.properties` por si Juan lo copia por error)

**Interfaces:**
- Produces: tipo de compilación `prueba`; tareas `assemblePlayPrueba`/`assembleFdroidPrueba`; propiedad Gradle `lector.firma` / variable `LECTOR_FIRMA` (ruta a un `.properties` con `storeFile`, `storePassword`, `keyAlias`, `keyPassword`).

- [ ] **Step 1: Firma** — en `app/build.gradle.kts`, antes de `android {}`:
```kotlin
// Firma de release con la llave de SUBIDA de Juan (docs/publicar-en-play.md). La ruta a su archivo de propiedades
// llega por ~/.gradle/gradle.properties (lector.firma=…) o la variable LECTOR_FIRMA: nunca desde el repositorio.
// Sin ella, release sale sin firmar y la compilación sigue (CI, Claude, F-Droid).
val firmaArchivo = providers.gradleProperty("lector.firma").orElse(providers.environmentVariable("LECTOR_FIRMA"))
```
y dentro de `android {}`:
```kotlin
    signingConfigs {
        if (firmaArchivo.isPresent) {
            val ruta = file(firmaArchivo.get())
            check(ruta.isFile) { "No existe el archivo de firma indicado en lector.firma / LECTOR_FIRMA" }
            val props = java.util.Properties().apply { ruta.reader().use { load(it) } }
            fun valor(clave: String): String = props.getProperty(clave)?.takeIf { it.isNotBlank() }
                ?: error("Falta «$clave» en el archivo de firma") // nunca se muestra el valor
            create("subida") {
                storeFile = file(valor("storeFile"))
                storePassword = valor("storePassword")
                keyAlias = valor("keyAlias")
                keyPassword = valor("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (firmaArchivo.isPresent) signingConfig = signingConfigs.getByName("subida")
            // Símbolos de las .so dentro del AAB (Play los usa para leer cierres nativos); el APK no cambia.
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
        }
        // Copia release para probar en el Pixel JUNTO a la app de Juan (spec 6b §3): R8 como release, firma de debug,
        // otro applicationId (datos propios). Nunca se instala encima de io.github.diegobr4nd.lectorbilingue.
        create("prueba") {
            initWith(getByName("release"))
            applicationIdSuffix = ".prueba"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    // La copia de prueba usa el mismo «sin pantalla de desarrollador» que release.
    sourceSets.getByName("prueba").kotlin.srcDir("src/release/kotlin")
```
(Si en AGP 9.4 la API de source sets es `kotlin.directories.add(...)` u otra, usar la que compile y anotarlo. Si el bloque `buildTypes { getByName("release") { … } }` choca con el de `AndroidApplicationConventionPlugin`, se suma, no se reemplaza: R8 y `proguardFiles` siguen viniendo del convention plugin.)

- [ ] **Step 2: Nombre visible** — `app/src/prueba/res/values/strings.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Copia release de prueba (spec 6b §3): se distingue de la app de Juan bajo el ícono. -->
    <string name="app_name">Lector (prueba)</string>
</resources>
```
- [ ] **Step 3: Sin archivo de firma** — `./gradlew bundlePlayRelease assemblePlayRelease assemblePlayPrueba` → BUILD SUCCESSFUL. `jarsigner -verify app/build/outputs/bundle/playRelease/app-play-release.aab` → «jar is unsigned»; `apksigner verify --print-certs app/build/outputs/apk/play/prueba/app-play-prueba.apk` → certificado `CN=Android Debug`. El AAB contiene `BUNDLE-METADATA/com.android.tools.build.debugsymbols/` (si no, anotar por qué y seguir: el aviso de Play se acepta).
- [ ] **Step 4: Con una llave DESECHABLE** (nunca la de Juan): en el scratchpad, `keytool -genkeypair -v -keystore <scratch>/desechable.jks -alias prueba -keyalg RSA -keysize 2048 -validity 1 -storepass desechable123 -keypass desechable123 -dname "CN=Desechable"` y un `<scratch>/desechable.properties` con esos 4 valores. `./gradlew bundlePlayRelease -Plector.firma=<scratch>/desechable.properties` → `jarsigner -verify` «jar verified». Luego: con una ruta inexistente → falla con «No existe el archivo de firma…»; con `keyPassword` vacío → falla con «Falta «keyPassword»…»; en ninguno de los dos aparece `desechable123` en la salida (`grep -c desechable123` = 0). **Borrar** `desechable.jks` y `desechable.properties` y volver a compilar sin la propiedad para no dejar un AAB firmado con ella.
- [ ] **Step 5: Manifest de `prueba`** — en `app/build/intermediates/merged_manifests/playPrueba/**/AndroidManifest.xml` (o donde AGP lo deje): `package`/`applicationId` termina en `.prueba`, todo `android:authorities` contiene `.prueba`, no hay `android:debuggable="true"`, y la lista de permisos es la misma que en release.
- [ ] **Step 6:** `./gradlew lint :app:lintPlayDebug test` → lint 0, verde. Commit `build(6b): firma con la llave de subida (fuera del repo), copia «prueba» y símbolos nativos`.

---

### Task 4: CI — AAB sin firmar y sus comprobaciones

**Files:**
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1:** En el paso «Lint, pruebas y APKs» añadir `bundlePlayRelease assemblePlayPrueba` al mismo `./gradlew`.
- [ ] **Step 2:** Paso nuevo después del de los motores arm64:
```yaml
      # Reglas de Play para el AAB (spec 6b §3). Cada grep tiene control positivo: sin AAB, el paso falla.
      - name: El AAB cumple las reglas de Play
        run: |
          aab=app/build/outputs/bundle/playRelease/app-play-release.aab
          test -f "$aab" || { echo "::error::No existe el AAB"; exit 1; }
          unzip -l "$aab" > "$RUNNER_TEMP/aab.txt"
          grep -q "base/lib/arm64-v8a/libct2bridge.so" "$RUNNER_TEMP/aab.txt"
          grep -q "base/lib/arm64-v8a/libslimtbridge.so" "$RUNNER_TEMP/aab.txt"
          if grep -E "base/lib/" "$RUNNER_TEMP/aab.txt" | grep -v "arm64-v8a" | grep -q .; then echo "::error::ABI distinta de arm64"; exit 1; fi
          if grep -q "readium/divina" "$RUNNER_TEMP/aab.txt"; then echo "::error::Divina en el AAB"; exit 1; fi
          unzip -p "$aab" 'base/dex/*.dex' > "$RUNNER_TEMP/aabdex.bin"
          unzip -p "$aab" base/resources.pb > "$RUNNER_TEMP/aabres.bin"
          test -s "$RUNNER_TEMP/aabdex.bin" && test -s "$RUNNER_TEMP/aabres.bin"
          if grep -a -q -e 'ui/enginetest' -e 'EngineTestScreen' "$RUNNER_TEMP/aabdex.bin"; then echo "::error::Desarrollador en el AAB"; exit 1; fi
          if grep -a -q -e 'Prueba del motor' -e 'Mostrar Bienvenida otra vez' "$RUNNER_TEMP/aabres.bin"; then echo "::error::Textos de desarrollador en el AAB"; exit 1; fi

      # 16 KB: Play exige que las .so carguen en teléfonos con páginas de 16 KB.
      - name: Las librerías nativas están alineadas a 16 KB
        run: |
          readelf="$ANDROID_HOME/ndk/30.0.16248370/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
          bt=$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)
          apk=app/build/outputs/apk/play/release/app-play-release-unsigned.apk
          test -f "$apk"
          "$ANDROID_HOME/build-tools/$bt/zipalign" -c -P 16 -v 4 "$apk" > /dev/null
          mkdir -p "$RUNNER_TEMP/so" && unzip -o -q "$apk" 'lib/arm64-v8a/*.so' -d "$RUNNER_TEMP/so"
          n=0
          for so in "$RUNNER_TEMP"/so/lib/arm64-v8a/*.so; do
            n=$((n+1))
            "$readelf" -l -W "$so" | awk '$1=="LOAD" { if (strtonum($NF) < 16384) bad=1 } END { exit bad }' \
              || { echo "::error::$so no está alineada a 16 KB"; exit 1; }
          done
          test "$n" -ge 2
```
(Ajustar la ruta del APK si `assemblePlayRelease` lo llama distinto; comprobar localmente con los mismos comandos en Git Bash, usando el `llvm-readelf` del NDK de Windows.)
- [ ] **Step 3:** El paso «El manifest fusionado solo pide los permisos permitidos» también debe cubrir `prueba`: cambiar el `find … -path '*Release*'` para incluir `*Prueba*` y comprobar que sigue encontrando archivos.
- [ ] **Step 4:** Probar localmente los comandos de los pasos (Git Bash) sobre las salidas reales; anotar la salida. Commit `ci(6b): AAB sin firmar, reglas de Play y alineación de 16 KB`.

---

### Task 5: Guía `docs/publicar-en-play.md`

**Files:**
- Create: `docs/publicar-en-play.md`
- Modify: `docs/build.md` (enlace a la guía y cómo compilar la copia de prueba), `CLAUDE.md` «Comandos útiles» (una línea: `./gradlew assemblePlayPrueba` = copia release de prueba)

Contenido (en español, frases cortas, cada término nuevo definido en una línea; comandos listos para pegar en **Git Bash** de Windows con rutas de ejemplo `C:\Users\JUAN\llaves\`, nunca valores reales):
1. **Crear la llave de subida:**
   ```bash
   mkdir -p /c/Users/JUAN/llaves
   "/c/Program Files/Android/Android Studio/jbr/bin/keytool" -genkeypair -v \
     -keystore /c/Users/JUAN/llaves/lector-subida.jks -alias subida \
     -keyalg RSA -keysize 4096 -validity 10000 \
     -dname "CN=Diego Guevara, O=Lector bilingue, C=CO"
   ```
   (pide dos contraseñas; cómo elegirlas; `C=` es el país — Juan lo cambia si no es Colombia). Dos respaldos offline (USB + otro lugar físico), junto con las contraseñas guardadas aparte. Qué pasa si se pierde: Play permite pedir el cambio de llave de subida (enlace a la ayuda de Play Console).
2. **Archivo de propiedades** `C:\Users\JUAN\llaves\lector-subida.properties` (ejemplo con `storeFile=C:/Users/JUAN/llaves/lector-subida.jks`, `storePassword=…`, `keyAlias=subida`, `keyPassword=…`) y una línea en `C:\Users\JUAN\.gradle\gradle.properties`: `lector.firma=C:/Users/JUAN/llaves/lector-subida.properties`. Advertencia: nunca copiar estos archivos dentro del repositorio.
3. **Compilar y verificar:** `./gradlew bundlePlayRelease` → `app/build/outputs/bundle/playRelease/app-play-release.aab`; `"/c/Program Files/Android/Android Studio/jbr/bin/jarsigner" -verify -verbose -certs <aab> | tail -5` → «jar verified».
4. **Play Console:** crear la app (idioma por defecto español (Latinoamérica), app gratuita), paquete `io.github.diegobr4nd.lectorbilingue`; en «Firma de apps» aceptar la llave generada por Google; Pruebas → **Prueba interna** → crear versión → subir el AAB → notas de la versión. Si Play exige antes formularios (política de privacidad, Seguridad de los datos, clasificación…), parar: eso es la 6d.
5. **Informe previo al lanzamiento:** dónde verlo y qué traerle a Claude (cierres, avisos de accesibilidad; nunca capturas con texto de libros con derechos).
6. **Tag:** `git tag v0.9.0 && git push origin v0.9.0` (después de que el PR de la 6b esté en `main` y antes de subir el AAB que sale de ese `main`).
7. **Verificación de desarrolladores de Google:** qué cubre Play Console solo para apps de Play (registro del paquete con la llave de subida), con el enlace oficial; qué haría falta aparte para distribuir fuera de Play (F-Droid, después).
8. **Copia de prueba en el Pixel** (la lista de ~15 min del spec §5, con «Lector (prueba)» y cómo borrarla desde Ajustes de Android sin tocar la app principal).

- [ ] **Step 1:** Escribir la guía; verificar cada comando que no use llaves reales ejecutándolo en seco donde se pueda (p. ej. `keytool -help`, rutas del JBR existen).
- [ ] **Step 2:** `grep -n -i "password=\|storePassword=[^…]" docs/publicar-en-play.md` solo muestra ejemplos con `…`. Commit `docs(6b): guía para firmar y subir a Play`.

---

### Task 6: Auditorías de toda la app (informes)

Tres revisiones **de solo lectura** de toda la app en `main` + esta rama, cada una deja su informe:
- `docs/auditorias/2026-10-17-seguridad-masvs.md` — agente `seguridad`: MASVS (STORAGE, CRYPTO, AUTH n/a, NETWORK, PLATFORM, CODE, RESILIENCE, PRIVACY), manifest (`allowBackup`/`dataExtractionRules`, exported, `usesCleartextTraffic`), WebView del lector, JNI, descarga y verificación de modelos, la configuración de firma de la Tarea 3. Veredicto «aprobado» / «requiere cambios».
- `docs/auditorias/2026-10-17-material3.md` — agente `diseno`: pantalla por pantalla (Bienvenida, Biblioteca, Idiomas, Cómo se usa, Acerca de, Licencias, Lector y sus hojas), capturas con una prueba temporal (sin cambiar ajustes del sistema; borrada al terminar), puntaje /100.
- `docs/auditorias/2026-10-17-compose.md` — agente `estructura`: recomposiciones, estabilidad, `remember` y claves, listas perezosas, efectos y corrutinas, `collectAsStateWithLifecycle`, semántica; puntaje /100.

Cada hallazgo: severidad (Crítico/Importante/Menor), archivo:línea, arreglo propuesto. El controlador hace commit de los tres informes (`docs(6b): informes de auditoría`).

---

### Task 7: Arreglos de las auditorías (crítico e importante)

- El controlador junta en un solo archivo los hallazgos **críticos e importantes** de los tres informes y despacha **una** implementación con TDD donde haya comportamiento (prueba que falla → arreglo → verde).
- Los menores se anotan en el informe correspondiente como «después del lanzamiento».
- Verificación: `./gradlew lint :app:lintPlayDebug test assembleFdroidRelease assemblePlayRelease bundlePlayRelease assemblePlayPrueba assembleFdroidDebugAndroidTest`; si tocan pantallas o el lector, las clases del Pixel afectadas.
- Revisión: re-revisión acotada por el mismo agente que auditó (¿quedó resuelto?). Puntajes finales anotados en cada informe.

---

### Task 8: Copia `.prueba` en el Pixel y verificación final (controlador)

- [ ] **Step 1: Estado de Juan antes** (comando aparte para la app al frente primero):
```bash
MSYS_NO_PATHCONV=1 adb shell dumpsys package io.github.diegobr4nd.lectorbilingue | grep -m2 -e versionName -e lastUpdateTime
MSYS_NO_PATHCONV=1 adb shell run-as io.github.diegobr4nd.lectorbilingue sh -c 'md5sum shared_prefs/* databases/*; du -s files' > <scratch>/estado-antes.txt
```
(solo sumas y tamaños; nunca contenidos).
- [ ] **Step 2: Instalar y abrir la copia:** `./gradlew assemblePlayPrueba`; `adb install app/build/outputs/apk/play/prueba/app-play-prueba.apk` (paquete `…lectorbilingue.prueba`, nunca `-r` sobre la app de Juan); `adb shell am start -n io.github.diegobr4nd.lectorbilingue.prueba/io.github.diegobr4nd.lectorbilingue.MainActivity`; esperar 5 s; `pidof io.github.diegobr4nd.lectorbilingue.prueba` existe y `dumpsys activity activities | grep -m1 topResumedActivity` muestra `.prueba`; `adb shell am force-stop io.github.diegobr4nd.lectorbilingue.prueba`.
- [ ] **Step 3: Estado de Juan después:** repetir el Step 1 a `estado-despues.txt`; `diff` vacío (la app de Juan no se tocó; `lastUpdateTime` igual).
- [ ] **Step 4: Verificación completa** (lint 0, pruebas, todas las compilaciones, lista de licencias al día en su propio comando, comprobaciones del CI en local) y revisión final de toda la rama (modelo más capaz), con un solo lote de arreglos si hace falta.
- [ ] **Step 5: Puerta de Juan:** su lista de la guía (§8) con «Lector (prueba)»; crear su llave de subida con la guía; si Play lo permite, AAB firmado aceptado en prueba interna.
