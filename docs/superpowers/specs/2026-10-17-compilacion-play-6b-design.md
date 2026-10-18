# 6b · Compilación para Play — diseño

Fecha: 2026-10-17 · Rama: `feat/compilacion-play` · Fase 6 (orden acordado: 6a ✅ → **6b** → 6d → prueba cerrada 12 × 14 días → 6c durante la prueba → producción).

## 1. Objetivo

Que exista un AAB del flavor `play` que Google Play acepte, firmado por Juan con su llave de subida, y que la versión release (con R8) funcione en un teléfono real; además, que toda la app pase una auditoría final de seguridad, de Material 3 y de Compose.

Éxito:
- `./gradlew bundlePlayRelease` produce un AAB que cumple las reglas de Play (arm64, 16 KB, permisos, sin desarrollador) y que se firma con la llave de Juan sin que la llave pase por el repositorio, el CI ni Claude.
- La copia `…lectorbilingue.prueba` (release con R8, firma de debug) recorre la lista de Juan en su Pixel sin fallos y sin tocar su app.
- Auditorías: seguridad (MASVS) «aprobado», Material 3 ≥ 80, Compose ≥ 80, con lo crítico e importante arreglado.

## 2. Decisiones de Juan (brainstorming)

| # | Pregunta | Decisión |
|---|---|---|
| 1 | Quién guarda la llave final | **A** · Play App Signing con llave generada por Google; Juan solo tiene la **llave de subida**. Play y F-Droid tendrán firmas distintas (aceptado). |
| 2 | Cómo probar release sin borrar datos | **A** · variante aparte `…lectorbilingue.prueba`, release con R8, firma de debug, junto a la app de Juan |
| 3 | Número de versión | **A** · prueba cerrada desde `0.9.0` (versionCode 1), producción como `1.0.0`; cada arreglo sube el último número y el versionCode |
| 4 | Teléfono con < 4 GB | **No se prueba**: casi todos los teléfonos actuales tienen ≥ 4 GB; la elección de motor por memoria ya tiene pruebas automáticas |
| 5 | Diseño por partes | Partes 1–3 aprobadas («va bien», «todo bien») |

Fuera de alcance: política de privacidad, «Seguridad de los datos», ficha de la tienda, nombre/ASO (6d); interfaz en inglés (6c); F-Droid; cambios de funciones.

## 3. Compilación y firma

**AAB**
- `./gradlew bundlePlayRelease` genera `app/build/outputs/bundle/playRelease/app-play-release.aab`.
- Debe cumplir: solo `arm64-v8a`; librerías nativas alineadas a **16 KB** (requisito de Play para apps nuevas con targetSdk ≥ 35); sin código ni textos de la pantalla de desarrollador; mismos permisos que hoy (lista del CI); sin `assets/readium/divina/` (ver más abajo).
- El CI compila el AAB **sin firmar** en cada PR y comprueba esas reglas (mismo estilo que las comprobaciones actuales del APK).

**Firma (regla 10 de CLAUDE.md: solo Juan toca llaves)**
- La firma de release se configura en Gradle leyendo un archivo **fuera del repositorio** cuya ruta se da por propiedad de Gradle o variable de entorno (por ejemplo `C:\Users\JUAN\llaves\lector-subida.properties` con la ruta del `.jks`, el alias y las contraseñas). Ni el archivo ni el `.jks` viven en el repo (`*.jks`, `*.keystore` ya están en `.gitignore`).
- Sin ese archivo, la compilación produce el AAB/APK **sin firmar** y no falla (así compilan el CI, Claude y F-Droid más adelante).
- Ningún valor de la llave se imprime en registros de Gradle.
- La guía (§6) da a Juan los comandos exactos: `keytool` (del JBR de Android Studio) para crear la llave de subida RSA 4096, validez ≥ 25 años; dónde guardarla; 2 respaldos offline; y el comando de compilación firmada.

**Copia de prueba en el Pixel**
- Tipo de compilación nuevo (nombre de trabajo `prueba`), basado en `release`: R8 y recorte de recursos iguales, sin pantalla de desarrollador, `applicationIdSuffix = ".prueba"`, nombre visible «Lector (prueba)», firmado con la llave de debug.
- Se instala junto a la app de Juan con datos propios. Nunca se instala una compilación con el mismo `applicationId` y otra firma sobre la app de Juan.

**Versión**
- `versionName = "0.9.0"`, `versionCode = 1`.
- Cada versión subida a Play lleva un tag `v<versionName>` en git (lo crea Juan con el comando de la guía).
- El enlace «Código fuente» de «Licencias» apunta a `https://github.com/DiegoBr4nd/lector-bilingue/tree/v<versionName>` (GPL-3.0 §6: el código que corresponde al binario publicado). Una prueba compara ese enlace con `versionName`.

**Divina**
- `assets/readium/divina/` (reproductor de cómics de Readium, que la app no usa) se excluye del APK y del AAB; se quita la fila `readium-divina` de «Licencias» y su aviso. Se comprueba que la lectura de EPUB sigue igual (las pruebas del lector en el Pixel).

**Otros detalles del AAB**
- Símbolos de depuración nativos: Play avisa si no se suben. Se generan los símbolos (`debugSymbolLevel`) solo si no cambia el contenido del AAB de forma que rompa la reproducibilidad; si no, se documenta el aviso como aceptado.

## 4. Auditorías (toda la app)

| Auditoría | Quién | Meta |
|---|---|---|
| Seguridad y privacidad (OWASP MASVS: almacenamiento, red, plataforma, código, resiliencia, privacidad) | agente `seguridad` | «aprobado», sin hallazgos ALTO |
| Material 3 (pantalla por pantalla: Bienvenida, Biblioteca, Idiomas, Cómo se usa, Acerca de, Licencias, Lector y sus hojas) | agente `diseno` | ≥ 80/100 |
| Compose (recomposiciones, estado, `remember`/claves, listas perezosas, efectos, accesibilidad semántica) | agente `estructura` | ≥ 80/100 |

- Hallazgos **críticos e importantes**: se arreglan dentro de la 6b, con pruebas.
- Hallazgos **menores**: quedan anotados para después del lanzamiento.
- Cada auditoría deja un informe en `docs/auditorias/2026-10-<día>-<tema>.md`.

## 5. Prueba de la versión release

**Automática (Claude, en el Pixel)**
- Antes: guardar el estado de la app de Juan (versión instalada, `shared_prefs` de ajustes, lista de libros y modelos por tamaño, sin leer contenidos de libros).
- Instalar la copia `.prueba` (y solo esa) con `adb install`; abrirla; comprobar que llega a la Bienvenida sin cerrarse.
- Después: comprobar que el estado de la app de Juan no cambió.
- Las reglas de siempre: comprobar qué app está al frente antes de instalar o abrir; nunca `uninstall`, `pm clear` ni `logcat -c`.

**Manual (Juan, ~15 min, en la guía)**
1. Bienvenida → descargar un idioma con el motor rápido (Firefox) y otro con el de calidad (OPUS).
2. Añadir un libro propio.
3. Tocar un párrafo y ver la traducción; probar Intercalado y Solo traducción.
4. Ajustes de lectura, Índice y cambio de capítulo.
5. «Acerca de», «Licencias» (el enlace al código abre el tag) y «Enviar comentarios».
6. Borrar la copia `.prueba` desde Ajustes de Android; su app sigue igual.

Si algo falla solo en release: se analiza con la skill `r8-analyzer`, se añaden reglas de R8 mínimas y justificadas, y se repite.

## 6. Guía `docs/publicar-en-play.md`

Para Juan, paso a paso, con los comandos exactos para pegar:
1. Crear la llave de subida (`keytool`), dónde guardarla y los 2 respaldos offline; qué hacer si se pierde (Play permite cambiar la llave de subida).
2. Crear el archivo de propiedades fuera del repo.
3. Compilar y firmar el AAB con un comando; verificar la firma (`jarsigner -verify` / `apksigner`).
4. Play Console: crear la app con el paquete `io.github.diegobr4nd.lectorbilingue`, aceptar Play App Signing con llave generada por Google, subir el AAB a **prueba interna**. Si Play exige antes formularios de la 6d, la guía lo indica y la subida pasa a la 6d.
5. Leer el informe previo al lanzamiento (cierres, accesibilidad) y traerlo a Claude.
6. Etiquetar la versión: `git tag v0.9.0` y `git push origin v0.9.0`.
7. Verificación de desarrolladores de Google: qué hace Play Console por sí solo para apps publicadas en Play y qué tendría que hacer Juan aparte.

La guía no contiene ninguna contraseña ni ruta real de llaves de Juan: usa ejemplos.

## 7. Errores

- Sin archivo de llave → AAB/APK sin firmar, compilación correcta, mensaje claro en la guía de cómo firmar.
- Archivo de llave con ruta o contraseña incorrecta → la compilación falla con un mensaje que no muestra la contraseña.
- La copia `.prueba` no puede reemplazar la app de Juan (otro `applicationId`).
- Una regla de R8 que falte → la detecta la lista de Juan o la apertura automática; se arregla con una regla específica, nunca con `-dontobfuscate`/`-dontshrink` globales sin justificarlo.

## 8. Pruebas y revisiones

- **JVM / Gradle:** versión 0.9.0 / 1; enlace al código = tag de `versionName`; catálogo de licencias sin `readium-divina`; la configuración de firma sin archivo deja la compilación sin firmar.
- **CI:** `bundlePlayRelease` sin firmar; comprobaciones del AAB (arm64, 16 KB con `zipalign -c -P 16` o la herramienta equivalente del SDK, sin divina, sin desarrollador, permisos); compilación de `prueba`; lista de licencias al día; todo lo que ya existe.
- **Pixel:** apertura automática de la copia `.prueba` con estado de Juan intacto; las pruebas del lector que ya existen sobre la variante de debug (siguen pasando sin Divina).
- **`seguridad`:** auditoría MASVS completa + revisión de la configuración de firma (que ninguna llave ni contraseña pueda acabar en el repo, el CI o los registros).
- **`diseno`:** auditoría Material 3 ≥ 80 y nombre visible de la copia de prueba.
- **`estructura`:** auditoría Compose ≥ 80.
- **Puerta con Juan:** su lista en el Pixel con la copia `.prueba`; llave de subida creada por él; AAB firmado aceptado por Play en prueba interna (si Play lo permite antes de la 6d).
