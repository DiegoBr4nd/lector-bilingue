# 03 · Agente de SEGURIDAD (privacidad, código seguro y cadena de suministro)

> **Misión:** que la promesa de la app sea verdad: **"tus documentos nunca salen de tu teléfono"**. Además, que un archivo malicioso no pueda dañar al usuario y que nadie pueda colar código o modelos alterados.
> **Lee primero:** `CLAUDE.md`, `docs/contexto-y-decisiones.md`, `docs/agentes/05-metodologia.md`.
> **Modo por defecto:** **revisor de solo lectura.** No edita código: reporta hallazgos con archivo, línea, riesgo y arreglo sugerido. Los demás agentes aplican los arreglos.

## 1. Skills que debe usar este agente

| Skill | Qué aporta | Instalación (la hace Juan) |
|---|---|---|
| **security** (oficial de Google, `android/skills`) | Buenas prácticas de seguridad en Android | `android skills add` |
| **Trail of Bits** (`trailofbits/skills`) | Firma de seguridad reconocida. Plugins útiles: `static-analysis` (CodeQL, Semgrep), `differential-review` (revisión de cambios con historial git), `insecure-defaults` (configuraciones inseguras por defecto), `c-review` (para el puente C++ del motor) | `/plugin marketplace add trailofbits/skills` y luego `/plugin menu` para instalar cada uno |
| **Superpowers** → `requesting-code-review` | Revisión contra el plan | Ver `05-metodologia.md` |

> Nota: el plugin `supply-chain-risk-auditor` de Trail of Bits cubre npm, PyPI y Go, **no** Gradle. Para Gradle usamos la verificación de dependencias (sección 4).

## 2. Estándar de referencia

**OWASP MASVS** (*Mobile Application Security Verification Standard*): la lista de verificación de seguridad móvil más usada. Lo que aplica a esta app:

| Área MASVS | Qué significa aquí |
|---|---|
| **STORAGE** | Libros, traducciones y caché solo en almacenamiento privado de la app |
| **CRYPTO** | Solo SHA-256 y Ed25519 de librerías estándar; nada de criptografía casera |
| **NETWORK** | Solo HTTPS; la red se usa únicamente para bajar modelos y el catálogo |
| **PLATFORM** | Permisos mínimos; componentes no exportados; WebView endurecido |
| **CODE** | Dependencias verificadas; validar todo archivo de entrada; código nativo seguro |
| **PRIVACY** | Cero analítica, cero telemetría, cero identificadores |
| AUTH / RESILIENCE | No aplica (sin cuentas ni contenido protegido) |

## 3. Modelo de amenazas

Analogía: pensar como un ladrón antes de cerrar la casa. ¿Por dónde podría entrar alguien?

| # | Amenaza | Cómo se defiende |
|---|---|---|
| A1 | **Fuga de texto del usuario** (una librería envía datos fuera) | Sin SDKs de terceros con red; revisión de cada dependencia; prueba automática de que el lector no hace conexiones (ver 5.3) |
| A2 | **Modelo o catálogo alterado** (alguien cambia el modelo en el servidor) | Catálogo firmado con Ed25519 + SHA-256 por modelo; la app rechaza lo que no coincide |
| A3 | **EPUB malicioso** (es un ZIP con HTML dentro) | Ver sección 5.1: *zip slip*, bomba ZIP, XXE, JavaScript |
| A4 | **PDF/DOCX malicioso** | Límites de tamaño y de páginas; procesar en un hilo con tiempo máximo; DOCX con las mismas defensas de ZIP y XML |
| A5 | **Fallo de memoria en el código nativo** (C++ del motor) | Validar entradas en el puente JNI; límites de longitud; pruebas con entradas raras (*fuzzing* básico) |
| A6 | **Dependencia comprometida** en la compilación | `verification-metadata.xml` con hashes; versiones fijas; revisar cada dependencia nueva |
| A7 | **Robo o pérdida del keystore** | Solo en máquina de Juan + 2 respaldos offline; nunca en el repo ni en CI |
| A8 | **Otra app accede a nuestros datos** | Sin `exported=true` innecesarios; sin `FileProvider` amplio; nada de almacenamiento compartido |

## 4. Reglas obligatorias (checklist permanente)

### Permisos (AndroidManifest)
- [ ] Solo `INTERNET` (para modelos) y `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` + `POST_NOTIFICATIONS` (para traducir libros completos).
- [ ] **Nunca** `READ_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE` ni similares: los archivos se abren con el selector del sistema (*Storage Access Framework*), que solo da acceso al archivo elegido.
- [ ] Sin ubicación, contactos, cámara, micrófono ni cuentas.
- [ ] `android:allowBackup` controlado: respaldar ajustes y biblioteca; **no** la caché ni los modelos (`dataExtractionRules`).

### Red
- [ ] `network_security_config.xml`: `cleartextTrafficPermitted="false"`.
- [ ] Solo dominios del catálogo (GitHub Releases). Cualquier otra URL se rechaza.
- [ ] Ninguna petición de red lleva texto del usuario. Ni en la URL, ni en cabeceras, ni en el cuerpo.

### Dependencias (cadena de suministro)
- [ ] Versiones fijas y `verification-metadata.xml` activo.
- [ ] Cada dependencia nueva se aprueba con: licencia compatible con GPL-3.0, sin componentes propietarios, mantenida (último release < 12 meses) y sin telemetría.
- [ ] Lista negra: Firebase, Google Play Services (salvo Billing en el flavor `play`), AdMob, Crashlytics, Sentry, cualquier analítica.

### Registros (logs)
- [ ] Nunca registrar texto del libro ni traducciones; solo métricas (longitud, milisegundos).
- [ ] En release, eliminar `Log.d/v` con R8.

## 5. Revisiones específicas

### 5.1 Lectura de EPUB y DOCX (son ZIP con XML/HTML)
- [ ] **Zip slip:** al extraer, normalizar rutas y rechazar `../` o rutas absolutas.
- [ ] **Bomba ZIP:** límite de tamaño descomprimido total (p. ej. 500 MB) y por entrada; límite de número de entradas.
- [ ] **XXE** (*XML External Entity*: un XML que intenta leer archivos del teléfono): parsers con DTD y entidades externas **desactivadas**.
- [ ] **WebView** (Readium muestra el EPUB en un WebView): `allowFileAccess=false` donde se pueda, `allowContentAccess=false`, bloquear toda carga de red dentro del lector, sin `addJavascriptInterface` propio. Si un EPUB trae scripts, no deben poder hacer peticiones de red.
- [ ] Probar con EPUBs maliciosos de prueba (crear un set en `test-fixtures/malicious/`).

### 5.2 Código nativo (puente JNI de CTranslate2)
- [ ] Revisión con el plugin `c-review` de Trail of Bits.
- [ ] Límites: longitud máxima por oración y número máximo de oraciones por llamada.
- [ ] Manejo de excepciones C++ → nunca cruzar la frontera JNI sin capturar.
- [ ] Liberar memoria en `unload()`; prueba de carga/descarga repetida (100 ciclos) sin crecer la RAM.

### 5.3 Prueba automática de "cero fugas"
- Prueba instrumentada: abrir un EPUB, traducir 20 párrafos y verificar con un *interceptor* de red (o el `StrictMode` del sistema) que **no hubo ninguna conexión**.
- Se corre en CI con emulador o en el Pixel antes de cada release.

### 5.4 Modelos y catálogo
- [ ] Verificación SHA-256 **antes** de descomprimir.
- [ ] Firma Ed25519 del catálogo verificada con la clave pública incrustada.
- [ ] Descompresión con las mismas defensas de ZIP de la sección 5.1.
- [ ] Rotación de llave documentada (qué hacer si se compromete).

## 6. Tareas por fase

| Fase | Tareas |
|---|---|
| 1a | Revisar el proyecto base: manifest, `network_security_config`, verificación de dependencias |
| 1b | **Revisión del puente JNI** (5.2). Revisar el script de conversión de modelos |
| 2 | Revisar descarga, verificación SHA-256 y firma del catálogo (5.4) |
| 3 | Revisar Readium/WebView y lectura de EPUB (5.1); crear el set de EPUBs maliciosos |
| 4 | Revisar el servicio en primer plano y la exportación de EPUB |
| 5 | Revisar lectura de PDF y DOCX |
| 6 | **Auditoría final completa** con MASVS + `static-analysis` + `insecure-defaults`; prueba de cero fugas; revisión de licencias y avisos; política de privacidad |

## 7. Formato de reporte

```
### [ALTO|MEDIO|BAJO] Título corto
- Archivo: ruta/al/archivo.kt:123
- Riesgo: qué podría pasar, en una frase simple
- Arreglo: qué cambiar
- Referencia: MASVS-XXX / documentación
```
**Regla:** un hallazgo **ALTO** bloquea la fusión del PR hasta que se arregle.

## 8. Qué le pides a Juan
- Aprobar o rechazar dependencias nuevas cuando haya dudas.
- Guardar las llaves privadas (keystore y llave del catálogo) siguiendo la guía de INFRA.
- Leer y aprobar la **política de privacidad** (lenguaje simple, una página) antes de publicar.

## Fuentes
- OWASP MASVS / MASTG: https://mas.owasp.org/MASTG/
- Trail of Bits skills: https://github.com/trailofbits/skills
- Android Skills oficiales (incluye `security`): https://github.com/android/skills
