# Gestor de modelos (Fase 2b) · Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** La app descarga modelos desde un catálogo firmado con minisign (o los importa desde un `.zip`), verificando cada archivo con SHA-256, sin depender de copias por USB.

**Architecture:** Módulo Android library `:models` con piezas pequeñas inyectables (verificador minisign, parser, fetcher con lista blanca, repositorio del catálogo, descargador, instalador, importador, almacén, worker). Lógica probada en la JVM con MockWebServer y carpetas temporales; WorkManager para la descarga en segundo plano; botones temporales en la pantalla de prueba.

**Tech Stack:** Kotlin · AGP 9.4.1 · Bouncy Castle `org.bouncycastle:bcprov-jdk18on:1.86` (MIT) · WorkManager `androidx.work:work-runtime-ktx:2.12.0` (Apache-2.0) · pruebas: `com.squareup.okhttp3:mockwebserver3:5.5.0` (Apache-2.0), `org.json:json:20260814` (dominio público) · minisign 0.12 (herramienta de Juan) · Python 3.12 (stdlib).

**Spec:** `docs/superpowers/specs/2026-10-03-gestor-modelos-design.md`

**Ejecución:** subagent-driven. Tareas 1, 9 → `infraestructura`; 2–8, 10 → `estructura`; 11 → `seguridad` + `diseno` (solo lectura) y arreglos por el dueño; 12 → sesión principal con Juan.

## Global Constraints

- Paquete `io.github.diegobr4nd.lectorbilingue`; módulo nuevo `:models`, paquete `io.github.diegobr4nd.lectorbilingue.models`. Rama `feat/gestor-modelos`; nunca `main`; nunca push sin permiso de Juan.
- Repo de modelos: `DiegoBr4nd/lector-bilingue-modelos`. URL del catálogo: `https://github.com/DiegoBr4nd/lector-bilingue-modelos/releases/download/catalogo/catalog.json` y `…/catalog.json.minisig`. Prefijo obligatorio de URLs de archivos: `https://github.com/DiegoBr4nd/lector-bilingue-modelos/releases/download/`.
- Lista blanca de hosts (producción): `github.com`, `objects.githubusercontent.com`, `release-assets.githubusercontent.com`. Solo HTTPS. Máximo 5 redirecciones, cada salto validado. Sin cookies; `User-Agent: lector-bilingue`; sin cabeceras con datos del usuario.
- Topes: catálogo ≤ 1 MiB; firma ≤ 4 KiB; archivo ≤ 2 GiB y ≤ `size` del catálogo; ≤ 200 modelos; ≤ 32 archivos por modelo.
- Validación del catálogo exactamente como spec §3.1 (`id` `^[a-z0-9][a-z0-9.-]{0,63}$` sin `..`, `pair` `^[a-z]{2,3}-[a-z]{2,3}$`, `engine` ∈ {opus, firefox}, `name` `^[A-Za-z0-9._-]{1,128}$` sin `..`, `sha256` `^[0-9a-f]{64}$`, `size` 1..2^31).
- minisign: solo algoritmo `ED` (prehash BLAKE2b-512); se verifican la firma del archivo **y** la firma global (`firma64 || trusted_comment_bytes`); `key id` (8 bytes, little-endian como los imprime minisign) debe ser de una llave incrustada.
- Las llaves de **prueba** viven en `models/src/test/resources/minisign/`; jamás la llave de Juan. Las llaves públicas de producción se incrustan solo en la Tarea 12.
- Rutas en el teléfono: catálogo aceptado `filesDir/catalog/catalog.json` (+ `.minisig`); temporales `filesDir/models/.tmp/<id>/`; instalados `filesDir/models/<pair>/` + `filesDir/models/<pair>/.installed.json` (id, modelVersion, engine, archivos).
- Permisos finales permitidos (CI): `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, `ACCESS_NETWORK_STATE` y `<paquete>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. WorkManager necesita `WAKE_LOCK` (wake lock al pasar a primer plano) y `ACCESS_NETWORK_STATE` (restricción de red en Android 14+); `RECEIVE_BOOT_COMPLETED` se quita con `tools:node="remove"` por decisión de Juan (la descarga sigue al abrir la app).
- Nunca registrar ni enviar texto de libros/traducciones. Mensajes de error sin rutas internas ni contenido de archivos.
- Gradle: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew …`. Teléfono: `export MSYS_NO_PATHCONV=1`; nunca desinstalar, `pm clear` ni `connectedAndroidTest` (borran el modelo y los textos privados copiados).
- Commits Conventional Commits en español, terminando en `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. Firma válida pero de **otra llave**, o con el comentario confiable alterado (antirretroceso falsificable) → debe rechazarse. Pruebas en Tarea 2.
2. Redirección de GitHub hacia un host no permitido o HTTP plano → rechazo. Tarea 4.
3. Un archivo que crece más que su `size` (servidor malicioso o zip bomba) → abortar sin llenar el disco. Tareas 4, 6, 7.
4. Corte de red a mitad → reanudar sin corromper; un modelo a medias nunca queda "instalado". Tareas 6, 8.
5. Catálogo viejo (rollback) o el incrustado más nuevo que el descargado → gana el más nuevo válido. Tarea 5.

---

### Task 1: Módulo `:models` y dependencias (agente `infraestructura`)

**Files:**
- Modify: `settings.gradle.kts` (`include(":models")`), `gradle/libs.versions.toml`, `gradle/verification-metadata.xml`, `app/build.gradle.kts`
- Create: `models/build.gradle.kts`, `models/src/main/AndroidManifest.xml`, `models/consumer-rules.pro`, `models/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/models/package-info.kt`

**Interfaces:**
- Produces: proyecto `:models` (plugin `lectorbilingue.android.library`, namespace `io.github.diegobr4nd.lectorbilingue.models`), con `implementation` de bcprov, work-runtime-ktx, coroutines-core; `testImplementation` de kotlin-test-junit, junit, coroutines-test, mockwebserver3, org.json. `:app` depende de `:models`.

- [ ] **Step 1: Catálogo de versiones**
```toml
[versions]
bouncycastle = "1.86"
work = "2.12.0"
mockwebserver = "5.5.0"
orgjson = "20260814"

[libraries]
bouncycastle-bcprov = { group = "org.bouncycastle", name = "bcprov-jdk18on", version.ref = "bouncycastle" }
androidx-work-runtime-ktx = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "work" }
mockwebserver3 = { group = "com.squareup.okhttp3", name = "mockwebserver3", version.ref = "mockwebserver" }
orgjson = { group = "org.json", name = "json", version.ref = "orgjson" }
```
- [ ] **Step 2: `models/build.gradle.kts`**
```kotlin
plugins {
    id("lectorbilingue.android.library")
}

android {
    namespace = "io.github.diegobr4nd.lectorbilingue.models"
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
    testOptions { unitTests.isReturnDefaultValues = false }
}

dependencies {
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver3)
    testImplementation(libs.orgjson)
}
```
`models/src/main/AndroidManifest.xml`: `<manifest />`. `consumer-rules.pro`: comentario + `-dontwarn org.bouncycastle.**` solo si R8 lo exige (documentar). `app/build.gradle.kts`: `implementation(project(":models"))`.
- [ ] **Step 3:** Regenerar huellas con el comando de `docs/build.md`; listar en el reporte los componentes nuevos (deben ser solo bouncycastle, androidx.work y transitivas —room, sqlite, lifecycle-service…— y, solo en pruebas, okhttp3/okio/mockwebserver3 y org.json). Si aparece algo inesperado, BLOCKED con la lista.
- [ ] **Step 4:** `./gradlew :models:assembleDebug assembleFdroidRelease lint test` → BUILD SUCCESSFUL. Reportar cuánto creció el APK release (`ls -l` antes/después).
- [ ] **Step 5: Commit** `build(models): módulo :models con Bouncy Castle y WorkManager`.

---

### Task 2: `MinisignVerifier` con TDD (agente `estructura`)

**Files:**
- Create: `models/src/main/kotlin/.../models/MinisignVerifier.kt`
- Create: `tools/catalog/make_test_vectors.py` (genera llaves y firmas de **prueba** en formato minisign con `cryptography` + `hashlib.blake2b`)
- Create: `models/src/test/resources/minisign/{test.pub,test2.pub,catalog-ok.json,catalog-ok.json.minisig,catalog-otra-llave.json.minisig,catalog-legacy-Ed.json.minisig}` (generados)
- Test: `models/src/test/kotlin/.../models/MinisignVerifierTest.kt`

**Interfaces:**
- Produces:
```kotlin
data class MinisignPublicKey(val keyId: ByteArray, val key: ByteArray) {
    companion object { fun parse(text: String): MinisignPublicKey }   // contenido de un .pub (2 líneas)
}
class SignatureException(message: String) : Exception(message)
class MinisignVerifier(private val trustedKeys: List<MinisignPublicKey>) {
    /** Devuelve el texto del comentario confiable si todo es válido; si no, lanza SignatureException. */
    fun verify(message: ByteArray, signatureFile: String): String
}
```

- [ ] **Step 1: Formato** (documentar en KDoc):
  - `.pub`: línea 1 `untrusted comment: …`; línea 2 base64 de `"Ed"(2) || keyId(8) || pk(32)` = 42 bytes.
  - `.minisig`: línea 1 `untrusted comment: …`; línea 2 base64 de `alg(2) || keyId(8) || sig(64)` = 74 bytes; línea 3 `trusted comment: <texto>`; línea 4 base64 de `globalSig(64)`.
  - Verificación: `alg == "ED"` (si `"Ed"` → rechazar: legado sin prehash); keyId ∈ llaves; `Ed25519.verify(sig, BLAKE2b-512(message), pk)`; `Ed25519.verify(globalSig, sig || utf8(texto), pk)`.
- [ ] **Step 2: Vectores de prueba.** `make_test_vectors.py` (en un venv del scratchpad con `cryptography`; NO al repo de dependencias) genera dos pares de llaves de prueba deterministas desde semillas fijas escritas en el script, el `catalog-ok.json` sintético y sus firmas (`ED` con llave 1, `ED` con llave 2, `Ed` legado con llave 1). **Validación cruzada:** descargar minisign 0.12 al scratchpad y comprobar `minisign -Vm catalog-ok.json -p test.pub` → "Signature and comment signature verified". Reportar la salida.
- [ ] **Step 3: Pruebas que fallan**
```kotlin
class MinisignVerifierTest {
    private fun res(name: String) = javaClass.getResource("/minisign/$name")!!.readBytes()
    private val pub1 = MinisignPublicKey.parse(String(res("test.pub")))
    private val pub2 = MinisignPublicKey.parse(String(res("test2.pub")))
    private val msg = res("catalog-ok.json")
    private val sig = String(res("catalog-ok.json.minisig"))

    @Test fun `firma valida devuelve el comentario confiable`() =
        assertTrue(MinisignVerifier(listOf(pub1)).verify(msg, sig).isNotEmpty())
    @Test fun `acepta si la llave esta entre varias`() =
        assertTrue(MinisignVerifier(listOf(pub2, pub1)).verify(msg, sig).isNotEmpty())
    @Test fun `un byte alterado se rechaza`() {
        val bad = msg.copyOf().also { it[10] = (it[10] + 1).toByte() }
        assertFailsWith<SignatureException> { MinisignVerifier(listOf(pub1)).verify(bad, sig) }
    }
    @Test fun `llave desconocida se rechaza`() =
        assertFailsWith<SignatureException> { MinisignVerifier(listOf(pub2)).verify(msg, sig) }
    @Test fun `firma de otra llave con key id falsificado se rechaza`() {
        val otra = String(res("catalog-otra-llave.json.minisig"))
        val lines = otra.lines().toMutableList()
        val raw = Base64.getDecoder().decode(lines[1]).also { System.arraycopy(pub1.keyId, 0, it, 2, 8) }
        lines[1] = Base64.getEncoder().encodeToString(raw)
        assertFailsWith<SignatureException> { MinisignVerifier(listOf(pub1)).verify(msg, lines.joinToString("\n")) }
    }
    @Test fun `comentario confiable alterado se rechaza`() {
        val alt = sig.replace(Regex("trusted comment: (.*)")) { "trusted comment: ${it.groupValues[1]}X" }
        assertFailsWith<SignatureException> { MinisignVerifier(listOf(pub1)).verify(msg, alt) }
    }
    @Test fun `algoritmo legado Ed se rechaza`() =
        assertFailsWith<SignatureException> {
            MinisignVerifier(listOf(pub1)).verify(msg, String(res("catalog-legacy-Ed.json.minisig")))
        }
    @Test fun `formato roto se rechaza`() {
        listOf("", "una linea", sig.lines().take(3).joinToString("\n"), sig.replace("trusted comment:", "comment:"))
            .forEach { assertFailsWith<SignatureException> { MinisignVerifier(listOf(pub1)).verify(msg, it) } }
    }
    @Test fun `llave publica mal formada falla al leerla`() =
        assertFailsWith<IllegalArgumentException> { MinisignPublicKey.parse("untrusted comment: x\nAAAA") }
}
```
Run: `./gradlew :models:testDebugUnitTest` → FAIL (referencias sin resolver).
- [ ] **Step 4: Implementación** (`MinisignVerifier.kt`)
```kotlin
package io.github.diegobr4nd.lectorbilingue.models

import java.util.Base64
import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

data class MinisignPublicKey(val keyId: ByteArray, val key: ByteArray) {
    companion object {
        fun parse(text: String): MinisignPublicKey {
            val line = text.lines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("untrusted comment:") }
                .singleOrNull() ?: throw IllegalArgumentException("llave pública mal formada")
            val raw = runCatching { Base64.getDecoder().decode(line) }.getOrNull()
            require(raw != null && raw.size == 42 && raw[0] == 'E'.code.toByte() && raw[1] == 'd'.code.toByte()) {
                "llave pública mal formada"
            }
            return MinisignPublicKey(raw.copyOfRange(2, 10), raw.copyOfRange(10, 42))
        }
    }
    override fun equals(other: Any?) = other is MinisignPublicKey && keyId.contentEquals(other.keyId) && key.contentEquals(other.key)
    override fun hashCode() = keyId.contentHashCode()
}

class SignatureException(message: String) : Exception(message)

/** Verifica firmas minisign (algoritmo ED: Ed25519 sobre BLAKE2b-512) y su comentario confiable. */
class MinisignVerifier(private val trustedKeys: List<MinisignPublicKey>) {
    fun verify(message: ByteArray, signatureFile: String): String {
        if (signatureFile.length > 4096) throw SignatureException("firma demasiado grande")
        val lines = signatureFile.lines().map { it.trimEnd('\r') }
        if (lines.size < 4 || !lines[0].startsWith("untrusted comment:") || !lines[2].startsWith("trusted comment: ")) {
            throw SignatureException("formato de firma inválido")
        }
        val sigBlock = decode(lines[1], 74)
        val alg = String(sigBlock, 0, 2, Charsets.US_ASCII)
        if (alg != "ED") throw SignatureException("algoritmo de firma no admitido")
        val keyId = sigBlock.copyOfRange(2, 10)
        val signature = sigBlock.copyOfRange(10, 74)
        val key = trustedKeys.firstOrNull { it.keyId.contentEquals(keyId) }
            ?: throw SignatureException("firma de una llave desconocida")
        val trustedComment = lines[2].removePrefix("trusted comment: ")
        val globalSignature = decode(lines[3], 64)

        if (!ed25519(key.key, blake2b512(message), signature)) throw SignatureException("firma inválida")
        if (!ed25519(key.key, signature + trustedComment.toByteArray(Charsets.UTF_8), globalSignature)) {
            throw SignatureException("comentario confiable alterado")
        }
        return trustedComment
    }

    private fun decode(b64: String, size: Int): ByteArray {
        val raw = runCatching { Base64.getDecoder().decode(b64.trim()) }.getOrNull()
        if (raw == null || raw.size != size) throw SignatureException("formato de firma inválido")
        return raw
    }

    private fun blake2b512(data: ByteArray): ByteArray {
        val d = Blake2bDigest(512)
        d.update(data, 0, data.size)
        return ByteArray(64).also { d.doFinal(it, 0) }
    }

    private fun ed25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        signer.update(message, 0, message.size)
        return signer.verifySignature(signature)
    }
}
```
- [ ] **Step 5:** `./gradlew :models:testDebugUnitTest` → 9 tests OK.
- [ ] **Step 6: Commit** `feat(models): verificador de firmas minisign` (incluye `tools/catalog/make_test_vectors.py` y los recursos de prueba; NO incluir llaves privadas de prueba si el script puede regenerarlas: guardar solo `.pub`, mensajes y firmas).

---

### Task 3: `Catalog` y `CatalogParser` con TDD (agente `estructura`)

**Files:**
- Create: `models/src/main/kotlin/.../models/Catalog.kt`, `.../models/CatalogParser.kt`
- Test: `models/src/test/kotlin/.../models/CatalogParserTest.kt`

**Interfaces:**
- Produces:
```kotlin
data class ModelFile(val name: String, val size: Long, val sha256: String, val url: String)
data class CatalogModel(val id: String, val pair: String, val engine: String, val modelVersion: String,
                        val license: String, val attribution: String, val files: List<ModelFile>) {
    val totalSize: Long get() = files.sumOf { it.size }
}
data class Catalog(val version: Int, val generated: java.time.Instant, val models: List<CatalogModel>) {
    fun findByPair(pair: String): List<CatalogModel> = models.filter { it.pair == pair }
}
class CatalogException(message: String) : Exception(message)
object CatalogParser {
    const val MAX_BYTES = 1 shl 20
    const val URL_PREFIX = "https://github.com/DiegoBr4nd/lector-bilingue-modelos/releases/download/"
    fun parse(bytes: ByteArray): Catalog   // lanza CatalogException
}
```
- [ ] **Step 1: Pruebas que fallan** — una función `valido()` que construye un `JSONObject` correcto (1 modelo, 2 archivos) y casos que mutan un campo cada uno. Debe haber **una prueba por regla** de la spec §3.1 / Global Constraints: versión ≠ 1; `generated` inválido; `id` con mayúsculas; `id` repetido; `pair` `../x`; `engine` `x`; 0 archivos; 33 archivos; `name` con `/`, con `..`, vacío, repetido; `size` 0 y 2^31+1; `sha256` con mayúsculas y con 63 chars; `url` `http://`, otro host, otro repo; JSON > 1 MiB; 201 modelos; JSON no válido; campos faltantes; tipos equivocados (`size` string). Y casos válidos: parse correcto, `totalSize`, `findByPair`. Los mensajes de `CatalogException` no incluyen el valor ofensivo (solo el nombre del campo y la posición).
- [ ] **Step 2: Implementación** con `org.json` (`JSONObject(String(bytes, UTF_8))`), conversión estricta (`getString`/`getLong` con comprobación de tipo vía `opt` + `is`), regex de Global Constraints, `Instant.parse(generated)`; cualquier `JSONException` → `CatalogException("JSON inválido")`.
- [ ] **Step 3:** `./gradlew :models:testDebugUnitTest` → todo verde. **Commit** `feat(models): catálogo de modelos con validación estricta`.

---

### Task 4: `HttpFetcher` con lista blanca (agente `estructura`)

**Files:**
- Create: `models/src/main/kotlin/.../models/HttpFetcher.kt`
- Test: `models/src/test/kotlin/.../models/HttpFetcherTest.kt`

**Interfaces:**
- Produces:
```kotlin
class NetworkPolicyException(message: String) : Exception(message)
fun interface HostPolicy { fun allows(url: java.net.URL): Boolean }
object GitHubHostPolicy : HostPolicy  // https + host ∈ lista blanca de Global Constraints
class HttpFetcher(
    private val policy: HostPolicy = GitHubHostPolicy,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) {
    /** Descarga completa a memoria con tope (catálogo y firma). */
    fun fetchBytes(url: String, maxBytes: Int): ByteArray
    /**
     * Descarga a [target] reanudando desde target.length() si existe (Range).
     * Llama a [onBytes] con cada bloque escrito (para el SHA-256 incremental y el progreso).
     * Aborta con NetworkPolicyException/IOException si se supera [maxBytes] en total.
     */
    fun downloadTo(url: String, target: java.io.File, maxBytes: Long, onBytes: (ByteArray, Int) -> Unit)
}
```
- [ ] **Step 1: Pruebas que fallan** (MockWebServer; en pruebas se usa una `HostPolicy` que permite solo `localhost` por **http** del servidor de pruebas, inyectada; `GitHubHostPolicy` se prueba aparte como función pura):
  - `GitHubHostPolicy`: acepta `https://github.com/...`, `https://objects.githubusercontent.com/...`, `https://release-assets.githubusercontent.com/...`; rechaza `http://github.com`, `https://github.com.evil.com`, `https://evilgithub.com`, `https://user@github.com.evil`, puerto raro con host malo.
  - `fetchBytes`: 200 OK; 404 → IOException sin cuerpo en el mensaje; cuerpo > maxBytes → excepción; redirección 302 a host permitido → sigue; 302 a host no permitido → `NetworkPolicyException`; 6 redirecciones → excepción; no envía `Cookie`; envía `User-Agent: lector-bilingue`.
  - `downloadTo`: descarga completa; con archivo parcial pide `Range: bytes=N-` y con respuesta 206 continúa (resultado byte a byte igual); si el servidor responde 200 a un Range, reinicia el archivo desde cero; si el total supera `maxBytes`, aborta y el archivo no supera `maxBytes`; `onBytes` recibe exactamente los bytes escritos.
- [ ] **Step 2: Implementación** con `HttpURLConnection`, `instanceFollowRedirects = false`, bucle manual de redirecciones (resolver `Location` relativo con `URL(base, location)`), `policy.allows()` antes de **cada** conexión, `setRequestProperty("User-Agent","lector-bilingue")`, `useCaches=false`, sin `CookieHandler` (comprobar `CookieHandler.getDefault()==null` o fijar cabecera vacía), lectura en bloques de 64 KiB con contador. Mensajes de error sin URL completa (solo host).
- [ ] **Step 3:** pruebas verdes. **Commit** `feat(models): descargas HTTPS solo hacia GitHub, con reanudación y topes`.

---

### Task 5: `CatalogRepository` (agente `estructura`)

**Files:**
- Create: `models/src/main/kotlin/.../models/CatalogRepository.kt`, `.../models/TrustedKeys.kt`
- Test: `models/src/test/kotlin/.../models/CatalogRepositoryTest.kt`

**Interfaces:**
- Consumes: `MinisignVerifier`, `CatalogParser`, `HttpFetcher`.
- Produces:
```kotlin
object TrustedKeys {
    /** Llaves públicas de producción (actual + reserva). Se rellenan en la Tarea 12. */
    val production: List<MinisignPublicKey> = emptyList()
}
class CatalogRepository(
    private val dir: java.io.File,                         // filesDir/catalog
    private val verifier: MinisignVerifier,
    private val fetcher: HttpFetcher,
    private val bundled: () -> Pair<ByteArray, String>?,   // (catalog.json, .minisig) del APK, o null
    private val catalogUrl: String = CATALOG_URL,
) {
    companion object {
        const val CATALOG_URL = "https://github.com/DiegoBr4nd/lector-bilingue-modelos/releases/download/catalogo/catalog.json"
    }
    /** El mejor catálogo válido conocido sin red: guardado o incrustado (el más nuevo). */
    fun current(): Catalog?
    /** Descarga, verifica, aplica antirretroceso y guarda. Devuelve el catálogo vigente tras la operación. */
    fun refresh(): Catalog
}
```
- [ ] **Step 1: Pruebas que fallan** (verificador con llaves de prueba; catálogos de prueba firmados en la Tarea 2 — ampliar `make_test_vectors.py` para generar `catalog-viejo.json` y `catalog-nuevo.json` con `generated` distintos):
  - `refresh` acepta y guarda (`dir/catalog.json` + `.minisig`) un catálogo válido; `current()` lo devuelve después.
  - firma inválida → `SignatureException`, nada guardado, `current()` sigue igual.
  - catálogo con `generated` anterior al guardado → rechazado (`CatalogException("catálogo más antiguo")`), se conserva el guardado.
  - sin red (MockWebServer apagado) → `refresh` lanza `IOException`, `current()` devuelve el incrustado verificado.
  - incrustado más nuevo que el guardado → `current()` devuelve el incrustado.
  - archivo guardado manipulado en disco → `current()` lo ignora (se re-verifica al leer) y cae al incrustado.
  - escritura atómica (temporal + rename) del catálogo guardado.
- [ ] **Step 2: Implementación.** **Step 3:** verde. **Commit** `feat(models): repositorio del catálogo con firma y antirretroceso`.

---

### Task 6: `ModelDownloader` y `ModelInstaller` (agente `estructura`)

**Files:**
- Create: `models/src/main/kotlin/.../models/ModelDownloader.kt`, `.../models/ModelInstaller.kt`, `.../models/InstalledModel.kt`
- Test: `models/src/test/kotlin/.../models/ModelDownloaderTest.kt`, `.../models/ModelInstallerTest.kt`

**Interfaces:**
- Produces:
```kotlin
class IntegrityException(message: String) : Exception(message)
data class InstalledModel(val id: String, val pair: String, val engine: String, val modelVersion: String, val files: List<String>)
class ModelDownloader(private val modelsDir: java.io.File, private val fetcher: HttpFetcher) {
    /** Baja (reanudando) y verifica cada archivo en modelsDir/.tmp/<id>/. Devuelve esa carpeta verificada. */
    fun download(model: CatalogModel, onProgress: (downloaded: Long, total: Long) -> Unit): java.io.File
}
class ModelInstaller(private val modelsDir: java.io.File) {
    /** Re-verifica tamaños y SHA-256 en [staging], escribe .installed.json y lo instala en modelsDir/<pair>/ de forma atómica. */
    fun install(model: CatalogModel, staging: java.io.File): InstalledModel
}
```
- [ ] **Step 1: Pruebas que fallan:** descarga de 2 archivos OK; reanudar tras corte (servidor que corta a la mitad la primera vez; el `.part` se completa con Range); SHA-256 distinto → `IntegrityException`, el `.part` se borra, nada en `modelsDir/<pair>`; tamaño distinto → idem; progreso monótono que termina en `totalSize`; un archivo ya completo y verificado no se vuelve a bajar; `install` con modelo previo instalado → reemplazo: primero renombrar el viejo a `.old-<id>`, luego el nuevo a `<pair>`, luego borrar el viejo (si falla a mitad, el viejo sigue disponible); `install` re-verifica (un archivo cambiado en staging → `IntegrityException`); `.installed.json` correcto; nombres de archivo solo los del catálogo.
- [ ] **Step 2: Implementación** (SHA-256 incremental con `MessageDigest` vía `onBytes`; al reanudar, primero se hashea lo ya bajado). **Step 3:** verde. **Commit** `feat(models): descarga verificada e instalación atómica de modelos`.

---

### Task 7: `ModelImporter` (`.zip` con defensas) (agente `estructura`)

**Files:**
- Create: `models/src/main/kotlin/.../models/ModelImporter.kt`
- Test: `models/src/test/kotlin/.../models/ModelImporterTest.kt`

**Interfaces:**
- Consumes: `Catalog`, `ModelInstaller`.
- Produces:
```kotlin
class ModelImporter(private val modelsDir: java.io.File, private val installer: ModelInstaller) {
    /** Lee el zip (sin copiarlo entero), identifica el modelo en [catalog], verifica e instala. */
    fun import(zip: java.io.InputStream, catalog: Catalog): InstalledModel
}
```
- [ ] **Step 1: Pruebas que fallan** (zips construidos en la prueba con `ZipOutputStream`): válido → instalado; entrada `../x` → rechazo; entrada `sub/model.bin` → rechazo; entrada absoluta `/model.bin` → rechazo; entrada extra no listada → rechazo; entrada repetida → rechazo; falta un archivo → rechazo; entrada que descomprime más que `size` (bomba: 10 MB de ceros declarados como 1 KB en el catálogo) → rechazo **sin** escribir más de `size`+64 KiB; SHA-256 distinto → rechazo; zip que no corresponde a ningún modelo → `CatalogException`; en todo rechazo, la carpeta temporal desaparece y no hay nada nuevo en `modelsDir/<pair>`.
- [ ] **Step 2: Implementación** con `ZipInputStream` (identificación: el conjunto de nombres del zip debe ser igual al conjunto de `files.name` de exactamente un modelo; si hay varios, el que coincida en SHA-256 tras escribir). **Step 3:** verde. **Commit** `feat(models): importar modelos desde zip con defensas contra zip slip y bombas`.

---

### Task 8: `ModelStore`, `DownloadWorker` y manifest (agente `estructura`)

**Files:**
- Create: `models/src/main/kotlin/.../models/ModelStore.kt`, `.../models/DownloadWorker.kt`, `.../models/Models.kt` (fábrica que arma las piezas con `Context`)
- Modify: `app/src/main/AndroidManifest.xml` (permisos + `SystemForegroundService` con `foregroundServiceType="dataSync"`), `app/src/main/res/values/strings.xml`
- Test: `models/src/test/kotlin/.../models/ModelStoreTest.kt`

**Interfaces:**
- Produces:
```kotlin
class ModelStore(private val modelsDir: java.io.File) {
    fun installed(): List<InstalledModel>          // lee .installed.json de cada carpeta; ignora .tmp/.old-*
    fun isInstalled(pair: String): Boolean
    fun sizeOnDisk(pair: String): Long
    fun delete(pair: String)                        // valida pair con la regex; no sigue enlaces
}
object Models {
    fun catalogRepository(context: android.content.Context): CatalogRepository
    fun store(context: android.content.Context): ModelStore
    fun enqueueDownload(context: android.content.Context, modelId: String): java.util.UUID   // WorkManager, único por modelo
}
class DownloadWorker(ctx: android.content.Context, params: androidx.work.WorkerParameters) : androidx.work.CoroutineWorker(ctx, params)
```
- [ ] **Step 1:** `ModelStore` con TDD (instalados, ignorar temporales, borrar, `pair` inválido → IAE).
- [ ] **Step 2:** `DownloadWorker`: lee `modelId` del input; `CatalogRepository.current()` (o `refresh()` si no hay); `setForeground(ForegroundInfo(id, notificación, FOREGROUND_SERVICE_TYPE_DATA_SYNC))` con canal "Descargas de modelos"; progreso con `setProgress(workDataOf("bytes" to n, "total" to t))` y en la notificación; errores de red → `Result.retry()` (backoff exponencial, máx. 5 intentos); `IntegrityException`/`SignatureException`/`CatalogException` → `Result.failure` con un código (`"error" to "integridad"|"firma"|"catalogo"`), nunca texto. Restricciones: `NetworkType.CONNECTED`, almacenamiento no bajo.
- [ ] **Step 3: Manifest** (`app`):
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
...
<service
    android:name="androidx.work.impl.foreground.SystemForegroundService"
    android:foregroundServiceType="dataSync"
    tools:node="merge" />
```
- [ ] **Step 4:** `./gradlew lint test assembleFdroidDebug` verde; revisar el manifest fusionado: solo los permisos de Global Constraints (listar). **Commit** `feat(models): descarga en segundo plano con WorkManager y almacén de modelos`.

---

### Task 9: CI, herramientas del catálogo y guía (agente `infraestructura`)

**Files:**
- Modify: `.github/workflows/ci.yml` (lista permitida exacta de permisos; pruebas de `tools/catalog`)
- Create: `tools/catalog/build_catalog.py`, `tools/catalog/test_build_catalog.py`, `docs/catalogo.md`
- Modify: `docs/build.md` (enlace a `docs/catalogo.md`)

- [ ] **Step 1: CI.** El paso del manifest pasa a: extraer todos los `android:name` de `<uses-permission…>` de los manifests fusionados de release y fallar si hay alguno fuera de: `android.permission.INTERNET`, `android.permission.FOREGROUND_SERVICE`, `android.permission.FOREGROUND_SERVICE_DATA_SYNC`, `android.permission.POST_NOTIFICATIONS`, `android.permission.WAKE_LOCK`, `android.permission.ACCESS_NETWORK_STATE`, `io.github.diegobr4nd.lectorbilingue.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. Probar localmente que pasa con el manifest real y falla si se agrega `READ_CONTACTS` a una copia en el scratchpad. Renombrar el paso a "El manifest fusionado solo pide los permisos permitidos". Agregar `(cd tools/catalog && python -m unittest -v)`.
- [ ] **Step 2: `build_catalog.py`** (stdlib; TDD): `python tools/catalog/build_catalog.py --sums ruta/SHA256SUMS --sizes-from carpeta/ --id opus-en-es-tcbig-2026.10 --pair en-es --engine opus --model-version tc-big-2026.10 --license CC-BY-4.0 --attribution "Helsinki-NLP / OPUS-MT, Tiedemann et al." --release opus-en-es-v1 --catalog catalog.json` → crea o actualiza `catalog.json` (reemplaza el modelo con el mismo `id`; `generated` = ahora UTC; valida con las mismas reglas que la app — reimplementadas y probadas). Excluye `SHA256SUMS` y archivos que no están en la lista de sumas. Nunca toca llaves.
- [ ] **Step 3: `docs/catalogo.md`** (español, para principiante, comandos exactos para PowerShell y Git Bash): instalar minisign (`winget install jedisct1.minisign`); `minisign -G -p minisign.pub -s <ruta-segura>\minisign.key` (dónde guardarla, NO en el repo, dos respaldos offline); crear `lector-bilingue-modelos` (público, con README y licencia CC-BY del modelo); release `opus-en-es-v1` y subir los 9 archivos del artefacto `model`; `build_catalog.py`; `minisign -Sm catalog.json -s …\minisign.key -t "lector-bilingue catalogo <fecha>"`; release `catalogo` (marcar como "latest" NO es necesario: la URL usa la etiqueta); actualizar el catálogo (subir el nuevo y borrar el anterior del release); **rotación**: generar la llave de reserva ya mismo, guardarla offline, y qué hacer si se compromete la actual.
- [ ] **Step 4:** pruebas Python verdes; **Commit** `ci: lista exacta de permisos; tools: catálogo de modelos y guía de firma`.

---

### Task 10: Botones temporales en la pantalla de prueba (agente `estructura`)

**Files:**
- Modify: `app/src/main/kotlin/.../ui/enginetest/EngineTestViewModel.kt`, `EngineTestScreen.kt`, `strings.xml`
- Create: `app/src/main/assets/catalog/` (vacío salvo `.gitkeep`; el catálogo incrustado real llega en la Tarea 12)

- [ ] **Step 1:** ViewModel: `downloadModel()` → `Models.catalogRepository(app).current() ?: refresh()` en IO, elige el modelo `en-es` de motor `opus`, muestra su tamaño total en MB; `Models.enqueueDownload` y observa el `WorkInfo` (`getWorkInfoByIdFlow`) para estado/progreso; al terminar con éxito, recarga el motor (`engine.load`). `importModel(uri)` → `contentResolver.openInputStream(uri)` en IO → `ModelImporter.import(...)` → recarga. Errores mostrados con mensajes fijos de `strings.xml` según el tipo (red, firma, integridad, catálogo), nunca `e.message` de capas bajas.
- [ ] **Step 2:** Pantalla: botón "Descargar modelo en-es (N MB)" (texto de aviso: "Descarga grande: mejor con Wi-Fi"), barra de progreso determinada, botón "Importar modelo (.zip)" con `rememberLauncherForActivityResult(OpenDocument())` y tipos `arrayOf("application/zip")`; en Android 13+ pedir `POST_NOTIFICATIONS` antes de encolar (si se niega, igual descarga). Sin catálogo y sin red: mensaje "No hay catálogo disponible todavía".
- [ ] **Step 3:** `./gradlew lint test assembleFdroidDebug` verde; captura en el Pixel (sin catálogo real todavía: debe mostrar el mensaje de "no hay catálogo"). **Commit** `feat(app): botones temporales para descargar e importar modelos`.

---

### Task 11: Revisiones (agentes `seguridad` y `diseno`, solo lectura)

- [ ] **Step 1: `seguridad`** (§4 Red, §5.1, §5.4 de `03-seguridad.md`): `MinisignVerifier` (formato, comparación de key id, ambos verificadores, longitudes), `CatalogParser` (regex, topes, mensajes), `HttpFetcher` (lista blanca, redirecciones, `user@host`, puertos, IDN/mayúsculas en host, cookies, topes), `CatalogRepository` (antirretroceso, re-verificación al leer), descargador/instalador (atomicidad, rutas, enlaces simbólicos), importador (todas las defensas), worker (mensajes sin datos), manifest fusionado y permisos, dependencias nuevas (licencias, mantenimiento, verificación), `build_catalog.py`, `docs/catalogo.md` (manejo de la llave), CI.
- [ ] **Step 2: `diseno`**: botones temporales (estados, progreso, accesibilidad, textos).
- [ ] **Step 3:** críticos/altos → arreglo por el dueño + re-revisión; medios/bajos baratos ligados a reglas → lote; resto → lista para Juan.

---

### Task 12: Puerta 2b con Juan (sesión principal)

- [ ] **Step 1:** Juan sigue `docs/catalogo.md`: instala minisign, genera la llave **actual** y la **de reserva** (con contraseña), hace respaldos, crea `lector-bilingue-modelos`, sube el release `opus-en-es-v1` (archivos del último artefacto `model` verificado), arma y firma el catálogo, lo sube al release `catalogo`.
- [ ] **Step 2:** Juan pega aquí el contenido de los dos `.pub` (públicas). El controlador (o `estructura`) los incrusta en `TrustedKeys.production` y copia el catálogo firmado y su `.minisig` a `app/src/main/assets/catalog/` (catálogo incrustado). Prueba: `CatalogRepository` con las llaves reales verifica el catálogo incrustado (prueba unitaria con esos archivos). Commit `feat(models): llaves públicas y catálogo incrustado`.
- [ ] **Step 3:** En el Pixel: borrar solo el modelo copiado a mano (`adb shell run-as … rm -r files/models/en-es`), abrir la app, **Descargar modelo** → progreso → "Modelo en-es listo" → benchmark ≥ 15 palabras/s. Captura.
- [ ] **Step 4:** Catálogo alterado: servir un `catalog.json` manipulado no es posible en GitHub sin la llave → la prueba de rechazo es la unitaria con vectores (Tarea 2/5) **y** en el teléfono: copiar un `catalog.json` alterado a `files/catalog/` y comprobar que la app lo ignora (cae al incrustado). Captura o salida.
- [ ] **Step 5:** Importar: crear un `.zip` con los archivos del modelo (`tar.exe -a -c -f en-es.zip -C private\modelo\x\en-es *` o similar, documentado), copiarlo a Descargas del teléfono, borrar el modelo, **Importar modelo (.zip)** → listo → traducción OK.
- [ ] **Step 6:** push (con permiso), PR, CI verde, revisión final de la rama, Juan fusiona.
