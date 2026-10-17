# Auditoría de seguridad y privacidad (OWASP MASVS) · 2026-10-17

- **Alcance:** toda la app en `feat/compilacion-play` (HEAD `5364a48`): manifest y manifest fusionado `playRelease`, red y descarga de modelos, catálogo firmado, importación de EPUB y modelos, saneado HTML y WebView de Readium, Room y archivos, puentes JNI (`ct2bridge`, `slimtbridge`), R8 y registros, CI, configuración de firma (Tarea 3) y guía `docs/publicar-en-play.md`.
- **Método:** lectura del código y de la configuración, historial de git, y desensamblado de `readium-navigator-3.4.0` (`WebViewServer`, `R2EpubPageFragment`) para comprobar cómo Readium atiende las peticiones del WebView. **No** compilé ni corrí pruebas: me apoyo en el CI y en las pruebas en el Pixel que ya existen (`MaliciousEpubOnDeviceTest`, `SecureDeleteOnDeviceTest`, `NoLoggingGuardTest`).
- **Agente:** `seguridad` (solo lectura).

## Resumen

- La promesa central se cumple en el código: **ningún camino de red lleva texto del libro ni de su traducción**. La única red es `HttpFetcher` (solo HTTPS, 3 hosts de GitHub, redirecciones validadas a mano, sin cookies ni caché) y solo descarga el catálogo y los modelos.
- El lector no puede salir a la red: Readium atiende **todas** las peticiones del WebView (cualquier host que no sea `readium_assets` se sirve como recurso del libro, nunca desde internet), `OfflineHttpClient` nunca conecta, el saneado quita scripts y la CSP cierra `connect-src`, formularios y marcos.
- Integridad de modelos fuerte: minisign (Ed25519 + BLAKE2b, algoritmo `ED`, comentario confiable verificado), antirretroceso por fecha, SHA-256 y tamaño fijos por archivo, rutas contenidas y sin enlaces simbólicos.
- Sin respaldos, sin componentes expuestos, sin analítica ni identificadores, sin registros, y ninguna llave ni contraseña en el repositorio, el CI ni el historial.
- Hallazgos: **0 Críticos, 1 Importante, 5 Menores.** El Importante es una verificación pendiente en la copia release (no un fallo de código).

## Estado por categoría MASVS

| Categoría | Estado | Lo comprobado |
|---|---|---|
| **STORAGE** | Cumple | Todo en `filesDir` y `databases/` (privado). `allowBackup="false"`, `fullBackupContent="false"` y `data_extraction_rules.xml` excluye todos los dominios en nube y en transferencia entre teléfonos. Caché de traducciones con clave SHA-256 del párrafo; `secure_delete=ON` + checkpoint del WAL al borrarla. Temporales de importación en `books/tmp/` borrados siempre (`BookImporter.kt:42-49`) y al arrancar. Nada en almacenamiento externo, `cacheDir` ni portapapeles. Ver M3. |
| **CRYPTO** | Cumple | Solo SHA-256 (`MessageDigest`) y Ed25519/BLAKE2b de Bouncy Castle 1.86; nada casero. Llave actual + de reserva incrustadas (`TrustedKeys.kt`), punto Ed25519 validado al leer, legado `Ed` rechazado, firma ≤ 4 KiB, catálogo ≤ 1 MiB, re-verificado en cada lectura. |
| **AUTH** | No aplica | Sin cuentas ni sesiones. |
| **NETWORK** | Cumple | `cleartextTrafficPermitted="false"`, solo CA del sistema (no las del usuario), `usesCleartextTraffic="false"`. `GitHubHostPolicy` (`HttpFetcher.kt:28-42`): https, puerto 443, sin `usuario@`, host exacto. Redirecciones manuales (máx. 5) validadas antes de conectar. Plazo total, topes de tamaño, errores sin URL completa. URLs del catálogo con prefijo fijo (`CatalogParser.kt:301-308`). Sin *pinning*: aceptable, porque la integridad la da la firma y el contenido no es secreto. |
| **PLATFORM** | Cumple | Solo `MainActivity` (lanzador) exportada; `ReaderActivity` no exportada y valida el id como UUID. Los demás exportados del manifest fusionado los protege un permiso de sistema (`BIND_JOB_SERVICE`, `DUMP`). `BluetoothValidationActivity` de media3 y `EmojiCompatInitializer` quitados; `RECEIVE_BOOT_COMPLETED` quitado. Permisos: lista exacta vigilada por el CI. WebView: sin métricas ni Safe Browsing, depuración apagada por Readium, saneado jsoup + CSP, enlaces externos solo `http/https/mailto` y con diálogo de confirmación, `target` quitado. Los scripts propios van como dato JSON y escriben con `textContent` (`ParagraphScripts.kt:593-596`). Ver M2 y la observación O1. |
| **CODE** | Cumple | EPUB: zip slip, bomba ZIP (500 MB total, 32 MB por entrada, 10 000 entradas, bytes reales), DRM, XXE y *billion laughs* cubiertos por pruebas en el Pixel. Modelos importados: ZIP en flujo con topes del catálogo y hashes antes de instalar. JNI: topes (1000 caracteres, lote 64, hilos 8/4, beam 8, decodificación 512), handles en registro con `shared_ptr`, toda excepción C++ capturada, UTF-8/UTF-16 con validación, registros internos apagados en `JNI_OnLoad`; `slimt.json` con analizador estricto y nombres sin `..`. Dependencias: versiones fijas, `verification-metadata.xml` con SHA-256 (`verify-metadata=true`), wrapper con `distributionSha256Sum`, acciones del CI fijadas por SHA. R8 activo en release y `prueba`; `Log.*` eliminado por R8 y prohibido por `NoLoggingGuardTest` (incluye `console.*`). |
| **RESILIENCE** | Razonable | R8 ofusca, `.so` sin símbolos en el APK, `debuggable` falso en release y `prueba`, pantalla de desarrollador fuera del release (comprobado en el CI). Sin detección de *root* ni Play Integrity: correcto para una app sin cuentas ni secretos (y Play Integrity exigiría Play Services). |
| **PRIVACY** | Cumple | Sin Firebase, Play Services, AdMob, Crashlytics ni analítica en las dependencias verificadas. Sin identificadores (ni ANDROID_ID ni publicidad). El correo de comentarios solo lleva versión, *flavor*, versión de Android, fabricante y modelo, y lo envía la persona desde su app de correo (`Feedback.kt:12-48`). Notificación genérica (par de idiomas y %). Catálogo solo se refresca en Bienvenida e Idiomas, nunca al leer. Ver M2 y M3. |
| **Firma (Tarea 3)** | Cumple | La ruta llega por `lector.firma` (Gradle de usuario) o `LECTOR_FIRMA`; nunca del repo (`app/build.gradle.kts:75-110`). Los mensajes de error no muestran rutas ni valores. Sin propiedad, release sale sin firmar (CI, F-Droid). `prueba` firma con debug y otro `applicationId`. `.gitignore` cubre `*.jks`, `*.keystore`, `*.key` y `lector-subida.properties`; el historial de git no tiene llaves ni contraseñas. El CI no usa *secrets*. La caché de configuración de Gradle guarda las contraseñas **cifradas** en `.gradle/` (ignorada por git), con la llave en `~/.gradle`. Ver M4 y M5. |

## Hallazgos

### Críticos
Ninguno.

### Importantes

#### I1 · Falta comprobar «cero conexiones al leer» en la copia release (R8)
- **Archivo:** `docs/publicar-en-play.md:155-179` (recorrido de la copia de prueba)
- **Riesgo:** las pruebas de cero fugas (`MaliciousEpubOnDeviceTest#rNingunaConexionDeRed`) corren sobre la versión debug, sin R8. Lo que se sube a Play es la versión recortada por R8, y nadie ha medido todavía que esa versión tampoco conecte al leer y traducir. Es una verificación pendiente, no un fallo encontrado (ya figura como bloqueante del lanzamiento).
- **Arreglo:** añadir al recorrido del paso 8, en la copia `prueba`:
  1. Descargar los modelos.
  2. Anotar el dato de Ajustes > Apps > «Lector (prueba)» > Datos móviles y Wi‑Fi.
  3. Con el Wi‑Fi encendido, leer y traducir unos 20 párrafos en los tres modos y abrir un enlace del libro sin confirmarlo.
  4. Comprobar que el contador no cambió.
  5. Repetir la lectura en modo avión: todo debe funcionar igual.
  Guardar la evidencia (capturas sin texto con derechos de autor) antes de subir el AAB.
- **Referencia:** MASVS-PRIVACY, MASVS-NETWORK; `03-seguridad.md` §5.3; regla 7 de CLAUDE.md.

### Menores

#### M1 · Comentario desactualizado en la configuración de red
- **Archivo:** `app/src/main/res/xml/network_security_config.xml:2`
- **Riesgo:** dice «La app no tiene permiso de internet en la fase 1a». Ya no es así, y confunde a quien revise la seguridad de la red.
- **Arreglo:** cambiar a «Solo HTTPS y solo certificados del sistema. La red solo se usa para bajar el catálogo y los modelos (GitHub).»
- **Referencia:** MASVS-NETWORK.

#### M2 · El menú de selección de texto puede mandar texto a otras apps
- **Archivo:** `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/reader/ReaderActivity.kt:59-75` (no hay `selectionActionModeCallback`); promesa en `app/src/main/res/values/strings.xml:254` y `:264`
- **Riesgo:** al mantener pulsado el texto, el WebView de Readium muestra las acciones del sistema (Copiar, Compartir y, en muchos teléfonos, «Traducir» o «Buscar», que abren apps en línea). Si la persona elige una de esas acciones, otra app recibe el texto. Lo decide ella, pero choca con el «nunca sale» literal.
- **Arreglo (elegir uno):**
  - (a) Dar a Readium un `selectionActionModeCallback` que deje solo «Copiar».
  - (b) Matizar la política de privacidad y la guía: «La app nunca envía tu texto. Si tú eliges Compartir o Traducir con otra app, esa app lo recibe.»
  - Recomiendo (b) para la 0.9.0 y valorar (a) después.
- **Referencia:** MASVS-PRIVACY-1/3.

#### M3 · Borrar un libro no borra sus traducciones ni vacía el WAL
- **Archivo:** `books/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/books/BookRepository.kt:25-28`
- **Riesgo:** al borrar un libro, sus traducciones siguen en `lector.db` hasta «Borrar caché». La caché es por párrafo, no por libro. Además, título, autor y posición (el `locator` de Readium lleva fragmentos del texto) quedan en `lector.db-wal` hasta el siguiente checkpoint automático. Todo sigue en almacenamiento privado, pero no es lo que espera quien borra.
- **Arreglo:**
  - Tras `dao.delete(id)`, llamar al checkpoint (`PRAGMA wal_checkpoint(TRUNCATE)`, como ya hace `clearCache`).
  - Decir en el diálogo de borrar libro, y en la política de privacidad (ya acordado con Juan), que las traducciones guardadas se quitan con «Borrar caché».
- **Referencia:** MASVS-STORAGE-2, MASVS-PRIVACY.

#### M4 · Las contraseñas de firma quedan junto al keystore
- **Archivo:** `docs/publicar-en-play.md:40` y `:56-63`
- **Riesgo:** la guía pide guardar las contraseñas «separadas del archivo `.jks`», pero luego crea `lector-subida.properties`, con las contraseñas en texto plano, en la misma carpeta `C:\Users\JUAN\llaves\`. Quien copie esa carpeta, por ejemplo al USB de respaldo, se lleva la llave y su contraseña juntas. El impacto es bajo, porque es la llave de **subida** (Google puede cambiarla) y la llave de la app la guarda Play.
- **Arreglo:**
  - Poner el `.properties` en otra carpeta (p. ej. `C:\Users\JUAN\.gradle\lector-firma\`).
  - Añadir a la guía: «Al respaldo va solo el `.jks`; nunca el `.properties`.»
- **Referencia:** MASVS-CODE (gestión de llaves), amenaza A7.

#### M5 · `.gitignore` no cubre otros formatos de llave
- **Archivo:** `.gitignore:10-11`
- **Riesgo:** si Juan convierte la llave a PKCS#12 (`.p12`/`.pfx`) o genera el archivo de PEPK para Play (`.pepk`), git no los ignora y un `git add .` los subiría al repositorio público.
- **Arreglo:** añadir `*.p12`, `*.pfx` y `*.pepk` junto a `*.jks` y `*.keystore`.
- **Referencia:** MASVS-CODE, amenaza A7.

## Observaciones (sin cambio requerido)

- **O1 · El WebView lo configura Readium.** Readium 3.4.0 activa JavaScript y añade sus propias interfaces JS (`R2EpubPageFragment`). `allowContentAccess` queda en su valor por defecto; `allowFileAccess` ya es falso por defecto con targetSdk ≥ 30. Hoy no es explotable, por tres barreras:
  - el saneado quita todo script y manejador `on…`;
  - la CSP sin `'unsafe-inline'` frena un script que se cuele;
  - `WebViewServer.shouldInterceptRequest` atiende cualquier URL con host (incluidas `content://` y `http://`) como recurso del libro, sin red ni proveedor.

  **En cada actualización de Readium:** volver a correr `MaliciousEpubOnDeviceTest` completo y revisar `WebViewServer`.
- **O2 · `verify-signatures=false`** en la verificación de dependencias: aceptable, porque cada artefacto lleva su SHA-256 fijo. Revisar a mano cada hash nuevo cuando cambie una dependencia.
- **O3 · Modelos nativos:** slimt y CTranslate2 no validan a fondo los tensores del `.bin`. La defensa es que solo se cargan archivos con SHA-256 del catálogo firmado (también los importados por ZIP). Aceptado (pendiente de 2c).
- **O4 · Fallos de la app y Android vitals:** si la app se cierra, Google Play/Android puede recoger el informe del fallo por su cuenta, según los ajustes del usuario. La app no integra ningún SDK de fallos, y sus mensajes de error son fijos y sin texto del libro (`ModelFileException`, `ReadError.Decoding`, JNI). Mantener esa regla.
- **O5 · Sin `FLAG_SECURE`:** se permiten capturas y la vista de recientes. Es lo esperado en un lector. Las ve solo la persona; no es un cambio necesario.

## Propuesta para «Seguridad de los datos» de Play (fase 6d)

| Pregunta del formulario | Respuesta propuesta | Por qué |
|---|---|---|
| ¿La app recopila o comparte alguno de los tipos de datos de usuario requeridos? | **No** | Ningún dato sale del teléfono hacia el desarrollador ni a terceros. Libros, traducciones, ajustes y biblioteca se quedan en almacenamiento privado. |
| Descarga de modelos (GitHub) | Sin declarar | Es contenido que la app baja, no datos del usuario que envía. La petición solo lleva lo propio de cualquier conexión (IP, `User-Agent: lector-bilingue`). El desarrollador no recibe ni guarda nada de eso. |
| Botón «Enviar comentarios» | Sin declarar (recomendado) | Abre la app de correo de la persona con un borrador. La app no transmite nada: la persona revisa, edita y envía con su propia app. *Alternativa conservadora, si Juan prefiere:* declarar «Información personal > Dirección de correo» y «Información y rendimiento de la app > Otros (modelo y versión)» como **recopilados, opcionales, para asistencia, no compartidos**. |
| ¿Los datos se cifran en tránsito? | No se pregunta con «No» arriba. Si apareciera: **Sí** | Toda la red es HTTPS (cleartext prohibido). |
| ¿Se puede pedir que se borren los datos? | No aplica | Sin cuentas ni datos en servidores. Todo se borra al desinstalar; las traducciones también con «Borrar caché». |
| Política de privacidad (URL) | **Obligatoria aunque no se recopile nada** | Pendiente para la 6d: una página simple que diga que todo ocurre en el teléfono, que la red solo baja idiomas desde GitHub, qué lleva el correo de comentarios, que las traducciones guardadas siguen tras borrar un libro (M3) y lo de compartir o traducir con otras apps (M2). |

Juan debe confirmar en la ayuda vigente de Play Console estos dos puntos antes de enviar el formulario: el criterio del correo por `mailto` y el de Android vitals (O4).

## Veredicto

**Aprobado.** Sin hallazgos críticos. Los menores (M1–M5) son cambios pequeños de texto o de configuración y pueden ir en este PR o en la 6d. **Condición para subir el AAB a Play:** cerrar **I1**, la comprobación de cero conexiones en la copia `prueba`, con evidencia.