# Spec · Fase 2b · Gestor de modelos

- **Fecha:** 2026-10-03
- **Rama:** `feat/gestor-modelos`
- **Estado:** diseño aprobado por Juan en conversación (secciones 1, 2 y 3); pendiente revisión de esta spec.
- **Fase 2 partida en:** 2a calidad (hecha) → **2b modelos** → 2c motor Firefox + EngineSelector → 2d diseño + Bienvenida/Idiomas.
- **Agentes:** `estructura` (módulo `:models`, Kotlin), `infraestructura` (script del catálogo, CI, guía de publicación), `seguridad` (revisión obligatoria: red, archivos, criptografía, permisos, dependencias), `diseno` (revisión rápida de los botones temporales).

## 1. Objetivo

La app obtiene sus modelos de traducción de forma segura, sin depender de copias por USB:
- descargando desde un **catálogo firmado** por Juan, verificando el **SHA-256** de cada archivo;
- o **importando** un `.zip` elegido con el selector del sistema, verificado contra el mismo catálogo.

**Puerta 2b** (con evidencia):
1. En el Pixel 7, el modelo en-es se descarga desde `DiegoBr4nd/lector-bilingue-modelos`, verificado con firma y SHA-256, y el benchmark sigue ≥ 15 palabras/s.
2. Un catálogo alterado (o firmado con otra llave, o más viejo) se rechaza.
3. La importación de un `.zip` válido instala el modelo; uno malicioso se rechaza sin dejar restos.
4. CI en verde; revisión de `seguridad` sin hallazgos críticos/altos abiertos.

### Fuera de alcance
- Pantallas definitivas (Bienvenida, Idiomas): 2d. Aquí solo botones temporales en la pantalla de prueba.
- Modelos de Firefox y `EngineSelector`: 2c (el catálogo ya admite `engine: "firefox"`).
- Publicación automática del catálogo desde CI (la llave nunca sale de la laptop de Juan).

## 2. Decisiones tomadas

| Tema | Decisión | Por qué |
|---|---|---|
| Empaquetado para descargar | **Archivos sueltos**, cada uno con tamaño y SHA-256 en el catálogo | Nada que descomprimir: desaparece zip slip / bomba ZIP en la descarga; reanudar por archivo |
| Empaquetado para importar | Un **`.zip`** por modelo, con defensas completas (§5.1 de seguridad) y verificado contra el catálogo | Un solo archivo es cómodo de compartir |
| Firma del catálogo | **minisign** (Ed25519; firma con prehash BLAKE2b-512 `ED` y comentario confiable) | Herramienta estándar con versión oficial para Windows; llave privada cifrada con contraseña |
| Verificación en la app | **Bouncy Castle** (`bcprov-jdk18on`, licencia MIT): `Ed25519Signer` + `Blake2bDigest` | Librería auditada; R8 elimina lo no usado. Android < 13 no trae Ed25519 |
| Descarga en segundo plano | **WorkManager** (`androidx.work`), trabajo de larga duración con `setForeground` (tipo `dataSync`) | Sobrevive a cerrar la app; reanuda tras cortes |
| Red | HTTPS + **lista blanca de dominios en código** (`github.com`, `objects.githubusercontent.com`, `release-assets.githubusercontent.com`); redirecciones revisadas a mano | §4 Red de `03-seguridad.md` |
| Publicación | Manual por Juan (release en el repo de modelos + script que arma el catálogo + `minisign -Sm`) | Regla 10: llaves solo de Juan |
| Catálogo sin red | Copia del catálogo firmado **dentro del APK** (asset), verificada igual | Importar sin haber tenido nunca internet |
| Rotación de llaves | La app acepta **dos llaves públicas** (actual + reserva) | Rotar sin dejar a nadie sin catálogo |

## 3. Catálogo

### 3.1 `catalog.json` (versión 1)
```json
{
  "version": 1,
  "generated": "2026-10-03T00:00:00Z",
  "models": [
    {
      "id": "opus-en-es-tcbig-2026.10",
      "pair": "en-es",
      "engine": "opus",
      "modelVersion": "tc-big-2026.10",
      "license": "CC-BY-4.0",
      "attribution": "Helsinki-NLP / OPUS-MT, Tiedemann et al.",
      "files": [
        {"name": "model.bin", "size": 235883903, "sha256": "<64 hex>",
         "url": "https://github.com/DiegoBr4nd/lector-bilingue-modelos/releases/download/opus-en-es-v1/model.bin"}
      ]
    }
  ]
}
```
Reglas de validación (`CatalogParser`), cualquier violación rechaza el catálogo entero:
- `version == 1`; `generated` en ISO-8601 UTC.
- `id` único, `^[a-z0-9][a-z0-9.-]{0,63}$` y sin `..` (se usa como nombre de carpeta); `pair` = `^[a-z]{2,3}-[a-z]{2,3}$` (mismas reglas que `LanguagePair`); `engine` ∈ {`opus`, `firefox`}.
- `files`: 1–32 entradas; `name` = `^[A-Za-z0-9._-]{1,128}$`, sin `..`, único dentro del modelo; `size` entre 1 byte y 2 GiB; `sha256` = 64 hex minúsculas; `url` HTTPS con host `github.com` y ruta que empiece por `/DiegoBr4nd/lector-bilingue-modelos/releases/download/`.
- Tamaño del JSON ≤ 1 MiB; ≤ 200 modelos.

### 3.2 Firma
- Archivos publicados: `catalog.json` y `catalog.json.minisig` en el release fijo **`catalogo`** de `DiegoBr4nd/lector-bilingue-modelos`. URLs:
  `https://github.com/DiegoBr4nd/lector-bilingue-modelos/releases/download/catalogo/catalog.json` (y `.minisig`).
- `MinisignVerifier` acepta solo el algoritmo `ED` (prehash BLAKE2b-512); verifica la firma del archivo **y** la firma global del comentario confiable; el `key id` debe coincidir con una de las llaves públicas incrustadas (`BuildConfig`/constante en `:models`).
- Antirretroceso: se guarda el `generated` del último catálogo aceptado; uno con `generated` menor se rechaza. El catálogo incrustado en el APK cuenta como punto de partida.

### 3.3 Herramientas para Juan
- `tools/catalog/build_catalog.py` (biblioteca estándar): a partir de `SHA256SUMS`, el id/par/motor/versión y la etiqueta del release, escribe `catalog.json` (combinándolo con el catálogo existente). No maneja llaves. TDD.
- `docs/catalogo.md` (en español, paso a paso): instalar minisign (`winget install jedisct1.minisign`), `minisign -G` con contraseña, dos respaldos offline, crear el repo, subir assets, armar el catálogo, firmar, subir; rotación de llave si se compromete o se pierde.

## 4. Módulo `:models` (Android library)

| Pieza | Responsabilidad |
|---|---|
| `MinisignVerifier` | Verificar `.minisig` contra bytes + llaves públicas. Kotlin puro (JVM-testeable) |
| `CatalogParser` | JSON → `Catalog` validado (§3.1), con `org.json` (ver §4.1) |
| `CatalogRepository` | Bajar `catalog.json` + `.minisig`, verificar firma y antirretroceso, guardar el último aceptado en `filesDir/catalog/`, caer al incrustado |
| `HttpFetcher` | GET con lista blanca, redirecciones manuales (máx. 5, cada salto validado), HTTPS, sin cookies, `User-Agent` genérico, tiempos de espera, `Range` para reanudar, tope de bytes |
| `ModelDownloader` | Por archivo: descarga a `filesDir/models/.tmp/<id>/<name>.part`, reanuda, SHA-256 incremental, verifica tamaño y huella |
| `ModelInstaller` | Si todos los archivos coinciden: renombrado atómico de la carpeta temporal a `filesDir/models/<pair>/` (reemplazo seguro del modelo anterior) |
| `ModelImporter` | `.zip` desde `Uri` (SAF): defensas §5.1 + verificación contra el catálogo |
| `ModelStore` | Listar instalados (par, motor, versión, tamaño), borrar, saber si un par está instalado |
| `DownloadWorker` | WorkManager `CoroutineWorker` con `setForeground`, progreso, reintentos con espera exponencial |

### 4.1 JSON
`CatalogParser` usa **`org.json`**, que ya viene en Android (sin dependencia nueva en el APK). En la JVM las clases de Android son stubs que lanzan excepción, así que las pruebas unitarias agregan **`org.json:json`** solo como `testImplementation` (dominio público; no entra al APK). Validación propia posterior (§3.1).

### 4.2 Interfaces para probar sin Android
`HttpFetcher` y el acceso a archivos se inyectan (interfaces pequeñas), de modo que `CatalogRepository`, `ModelDownloader`, `ModelInstaller` y `ModelImporter` se prueban en la JVM con **MockWebServer** (solo pruebas) y carpetas temporales.

## 5. Importación `.zip` (defensas)
- Se lee con `ZipInputStream` sobre el `InputStream` del `Uri` (sin copiar el zip entero antes).
- El modelo se identifica por el conjunto de archivos: debe existir en el catálogo un modelo cuyos `files` coincidan **exactamente** en nombres; si hay varios candidatos, gana el que coincida en todos los SHA-256.
- Cada entrada: nombre sin directorios ni `..` ni barras; debe estar en la lista del modelo; no repetida; se escribe con tope = `size` del catálogo (se aborta al superarlo); SHA-256 al vuelo; al final tamaño y huella exactos.
- Tope de entradas: número de archivos del modelo + 0 (cualquier extra → rechazo). Tope global de bytes = suma de tamaños del catálogo.
- Cualquier fallo: se borra la carpeta temporal; mensaje claro sin rutas internas.

## 6. Permisos, manifest y red
- Nuevos: `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`. El paso del CI pasa de "sin permisos salvo el interno de AndroidX" a **lista permitida exacta** (esos 4 + el interno).
- El servicio de primer plano de WorkManager (`SystemForegroundService`) se declara con `foregroundServiceType="dataSync"` vía `tools:node="merge"`.
- `network_security_config.xml` sigue con `cleartextTrafficPermitted="false"`; la lista blanca de dominios va en `HttpFetcher` (un solo lugar).
- `data_extraction_rules.xml` ya excluye todo (modelos y catálogo no se respaldan).
- Ninguna petición lleva texto del usuario ni identificadores.

## 7. Integración temporal
`EngineTestScreen` gana dos botones: **"Descargar modelo en-es"** (encola el trabajo; muestra progreso y resultado) e **"Importar modelo (.zip)"** (`ActivityResultContracts.OpenDocument` con `application/zip`). Al terminar, recarga el motor. Revisión rápida de `diseno`.
`OpusEngine` no cambia: sigue leyendo `filesDir/models/en-es/`.

## 8. Pruebas

| Qué | Tipo |
|---|---|
| `MinisignVerifier`: válida; byte alterado; firma de otra llave; key id desconocido; comentario confiable alterado; algoritmo no `ED`; formato roto | JVM, TDD, vectores de prueba con una **llave de pruebas** (en `src/test/resources`, nunca la de Juan) |
| `CatalogParser`: cada regla de §3.1 | JVM, TDD |
| `CatalogRepository`: acepta válido; rechaza firma inválida; rechaza más viejo; cae al incrustado sin red | JVM + MockWebServer |
| `HttpFetcher`: redirección a dominio prohibido; HTTP plano; más bytes que el tope; `Range` 206 y 200 | JVM + MockWebServer (con lista blanca inyectable para usar `localhost` solo en pruebas) |
| `ModelDownloader` + `ModelInstaller`: reanudar; SHA-256 distinto → nada instalado; renombrado atómico | JVM, carpetas temporales |
| `ModelImporter`: zip válido; zip slip; entrada extra; entrada repetida; bomba (más bytes que `size`); huella distinta; modelo desconocido | JVM, zips generados en la prueba |
| `build_catalog.py` | Python, TDD |
| Flujo real en el Pixel | Manual guiado en la puerta |

## 9. Lo que hace Juan
1. `winget install jedisct1.minisign`; `minisign -G` (contraseña fuerte); dos respaldos offline de la llave privada.
2. Crear el repo público `lector-bilingue-modelos`.
3. Release `opus-en-es-v1` con los 9 archivos del artefacto `model` (subida web).
4. `python tools/catalog/build_catalog.py …`, `minisign -Sm catalog.json`, release `catalogo` con `catalog.json` y `.minisig`.
5. Pegar la llave **pública** (`minisign.pub`) en el chat para incrustarla (la privada nunca).

## 10. Riesgos

| Riesgo | Mitigación |
|---|---|
| GitHub cambia los dominios de descarga | Lista blanca en un solo lugar; error claro "dominio no permitido" |
| Juan pierde la llave privada | Dos respaldos + llave de reserva en la app; procedimiento en `docs/catalogo.md` |
| Llave privada comprometida | Rotación a la llave de reserva y publicación de un catálogo nuevo; la app deja de aceptar la llave vieja en la siguiente versión |
| Catálogo incrustado envejece | Solo es punto de partida; el descargado más nuevo lo reemplaza |
| F-Droid marca "NonFreeNet"/red | Esperado: solo descarga modelos públicos; se declara en metadatos (fase 6) |
| Descarga grande con datos móviles | Aviso de tamaño antes de descargar (2d hará la pantalla; aquí el botón temporal muestra el tamaño) |
