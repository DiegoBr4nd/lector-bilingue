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
```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew assembleFdroidDebug      # compilar
./gradlew test                     # pruebas unitarias
./gradlew lint                     # revisión estática
./gradlew installFdroidDebug       # instalar en el Pixel por USB
```

En PowerShell: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat assembleFdroidDebug`.

## Verificación de dependencias
Cada librería tiene su huella SHA-256 en `gradle/verification-metadata.xml`. Si una cambia, la compilación falla.

Al agregar o actualizar una dependencia:
1. Cambiar la versión en `gradle/libs.versions.toml`.
2. Si cambió AGP, actualizar el número de aapt2 en `build.gradle.kts` (raíz) con el de
   `https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/maven-metadata.xml`.
3. Regenerar:
```bash
./gradlew --write-verification-metadata sha256 --refresh-dependencies --rerun-tasks resolveAapt2AllPlatforms lint test assembleFdroidDebug assemblePlayDebug assembleFdroidRelease assemblePlayRelease
```
4. Revisar el diff de `verification-metadata.xml` en el PR. Solo deben aparecer los artefactos nuevos.
5. Pedir revisión del agente de seguridad (regla 8 de CLAUDE.md).

## Reproducibilidad
- Sin bloque de dependencias de Google en el APK (`dependenciesInfo`).
- Sin hash de git en el APK (`vcsInfo.include = false`).
- La comparación de dos compilaciones limpias llega en la fase 6 (`release.yml`).
