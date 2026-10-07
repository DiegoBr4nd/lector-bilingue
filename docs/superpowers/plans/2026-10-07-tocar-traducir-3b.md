# 3b · Tocar y traducir · Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** tocar un párrafo de un EPUB en el Lector y ver su traducción debajo en < 2 s, con caché en Room y pretraducción de los ~5 párrafos siguientes, todo en el teléfono.

**Architecture:** la app pregunta a la página de Readium con scripts propios (`EpubNavigatorFragment.evaluateJavascript`) qué párrafo hay bajo el toque y le pide insertar tarjetas como texto (`textContent`). Un `TranslationService` único, con un solo hilo y una fila con prioridad (toque antes que pretraducción), consulta primero el caché en Room (`translations`, base v2) y si falta traduce oración por oración con el motor elegido por `EngineSelector`. El `ReaderViewModel` lleva el estado de las tarjetas.

**Tech Stack:** Kotlin, Jetpack Compose + Material 3, Readium 3.4.0 (navegador EPUB en WebView), Room 2.8.5 (KSP), corrutinas, motores OPUS (CTranslate2) y Firefox (slimt) del módulo `:engine`.

**Spec:** `docs/superpowers/specs/2026-10-07-tocar-traducir-3b-design.md` (leerla antes de cada tarea).

## Global Constraints

- Rama `feat/tocar-traducir`, una tarea = commits `feat(3b): …` / `fix(3b): …` / `test(3b): …`; mensajes terminan con `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Nunca push sin permiso de Juan.
- **Nunca registrar (log) ni enviar por red el texto de los libros, sus traducciones ni sus huellas.** Nada de `Log`, `println`, `printStackTrace`, `Timber` en código nuevo.
- Prohibido: Firebase, Play Services, analítica, dependencias no libres. Sin dependencias nuevas salvo que `seguridad` las apruebe.
- Motor: beam 1, máximo 4 hilos (OPUS `EngineConfig()`; Firefox `EngineConfig(threads = 1)`, como en `EngineTestViewModel.configOf`).
- `minSdk` 26; `:books` sin *desugaring*: ninguna API > 26 ahí (lint `NewApi` limpio).
- Textos de la interfaz en `app/src/main/res/values/strings.xml`, en español. Comentarios en español, al estilo del código vecino.
- La página **no** puede llamar a la app: sin `addJavascriptInterface`. Hacia la página solo `textContent`, nunca `innerHTML`. Datos de los scripts codificados en JSON. La CSP de `HtmlSanitizer` no cambia sin aprobación de `seguridad`.
- Base `lector.db`: v1 → v2 con migración explícita; nunca `fallbackToDestructiveMigration`.
- Gradle: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew …`.
- Teléfono (Pixel 7, serial `2A261FDH200L5R`): solo `./gradlew installFdroidDebug installFdroidDebugAndroidTest`, `adb shell am force-stop io.github.diegobr4nd.lectorbilingue`, `adb shell am instrument -w -e class <clase> io.github.diegobr4nd.lectorbilingue.test/androidx.test.runner.AndroidJUnitRunner`, siempre con `export MSYS_NO_PATHCONV=1`. **Nunca** desinstalar, `pm clear`, `connectedAndroidTest` ni `input tap/swipe`. Antes de usarlo, mirar `dumpsys activity activities | grep topResumedActivity`: si Juan usa otra app, preguntar al controlador. Las pruebas borran lo que crean.
- Reglas de CLAUDE.md 8 y 9: cambios en red/archivos/nativo/dependencias → `seguridad`; en pantallas → `diseno`.

## Review Focus

1. **Párrafo con texto hostil** (comillas, `</script>`, `\u2028`, `<img onerror=…>` como texto, 50 000 caracteres): se inserta como texto, el script no se rompe y nada se ejecuta → pruebas en Tarea 5 (`ParagraphScriptsTest`) y Tarea 8 (`MaliciousEpubOnDeviceTest`).
2. **Cambiar de capítulo o salir mientras traduce:** la traducción termina y se guarda, no se inserta en otra página ni en un índice equivocado; la pretraducción vieja se cancela → Tarea 4 (`cancelaPretraduccionDeOtroRecurso`) y Tarea 6 (`noInsertaEnOtroRecurso`).
3. **Dos toques rápidos al mismo párrafo** (abrir y cerrar antes de que llegue la traducción): la tarjeta queda cerrada y la traducción llegada no la reabre → Tarea 6 (`cerrarAntesDeQueLlegueNoReabre`).
4. **Modelo borrado o instalado mientras el Lector está abierto:** al tocar se vuelve a mirar lo instalado; no se usa un motor descargado ni se cambia de motor en silencio → Tarea 4 (`vuelveAMirarLoInstaladoAlCargar`).
5. **Párrafo cuya traducción ya está en caché y además en la fila de pretraducción:** se responde del caché y el motor no la traduce dos veces → Tarea 4 (`noTraduceDosVecesLoMismo`).

---

### Task 1: Comprobación en el Pixel (desechable; agente `estructura`)

**Objetivo:** responder T1, T2 y T3 de la spec §3 con evidencia, en una rama local que se borra al final. No se fusiona nada.

**Files:** rama local `spike/3b-tocar` desde `feat/tocar-traducir`; código temporal en `app/src/androidTest/.../Spike3bOnDeviceTest.kt` y donde haga falta. Resultado en la spec §12 (nueva sección "Resultado de la comprobación"), único cambio que vuelve a `feat/tocar-traducir`.

- [ ] **Step 1: T2 · hoja de estilos.** Dentro de `ReadiumBooks.open` (rama spike), añadir al `TransformingContainer` un recurso nuevo `lector/tarjeta.css` servido desde un asset de la app, e inyectar `<link rel="stylesheet" href="…/lector/tarjeta.css">` en el `<head>` saneado (después de la CSP). Comprobar con `evaluateJavascript` que `getComputedStyle(document.querySelector('.lector-tarjeta')).borderLeftWidth` vale lo que dice la hoja, para un `<aside class="lector-tarjeta">` creado por script. Alternativa a probar si no carga: la ruta de los recursos propios de Readium (`readium_assets`). Anotar qué URL funcionó y que la CSP no cambió (`git diff` de `HtmlSanitizer.kt` vacío).
- [ ] **Step 2: T1 · TalkBack.** Con TalkBack encendido **por Juan** (pedírselo al controlador; no tocar ajustes por adb), registrar en un archivo de la caché de la app solo `true/false` y coordenadas: si el toque doble sobre un párrafo enfocado llega a `InputListener.onTap` y si `elementFromPoint` en ese punto da el mismo párrafo. Probar también si un `role`/`aria-*` en el párrafo hace que TalkBack ofrezca una acción con nombre. Juan apaga TalkBack al terminar.
- [ ] **Step 3: T3 · recarga.** Abrir capítulo 1, insertar una tarjeta, saltar al capítulo 3 con el Índice y volver: anotar si la tarjeta sigue (lo esperado: no) y qué señal indica que el recurso visible cambió (`currentLocator.href` distinto) y que la página está lista para scripts (por ejemplo, el primer `currentLocator` con el nuevo href, o un `evaluateJavascript("document.readyState")` igual a `"complete"`).
- [ ] **Step 4: Resultado.** Volver a `feat/tocar-traducir`, escribir la spec §12 (tabla T1/T2/T3 con ✅/❌ y evidencia, más "APIs que difieren"), commit `docs(3b): resultado de la comprobación en el Pixel`. Borrar la rama spike (`git branch -D spike/3b-tocar`). Si T1 o T2 fallan, **parar** y avisar al controlador (la spec §3 dice qué alternativa consultar con Juan).

---

### Task 2: Room v2 · tabla `translations`, columna `direction` y migración (agente `estructura`)

**Files:**
- Create: `books/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/books/db/TranslationEntity.kt`
- Create: `books/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/books/db/TranslationDao.kt`
- Create: `books/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/books/db/Migrations.kt`
- Modify: `books/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/books/db/BookEntity.kt` (campo `direction`)
- Modify: `books/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/books/db/BookDao.kt` (`setDirection`)
- Modify: `books/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/books/db/LectorDatabase.kt` (v2, entidades, migración, `translations()`)
- Modify: `books/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/books/Book.kt` (campo `direction`)
- Modify: `books/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/books/BookRepository.kt` (`setDirection`, mapeo)
- Modify: `books/src/test/.../FakeBookDao.kt` y `app/src/test/.../ui/library/FakeBookDao.kt` (nuevo método)
- Create (generado): `books/schemas/io.github.diegobr4nd.lectorbilingue.books.db.LectorDatabase/2.json`
- Modify: `app/build.gradle.kts` (assets de `androidTest` con los esquemas)
- Test: `books/src/test/.../BookRepositoryTest.kt` (dirección), `app/src/androidTest/.../MigrationOnDeviceTest.kt`

**Interfaces:**
- Produces:
  - `@Entity(tableName = "translations") data class TranslationEntity(@PrimaryKey val key: String, val translation: String, val createdAt: Long)`
  - `interface TranslationDao { suspend fun get(key: String): TranslationEntity?; suspend fun getAll(keys: List<String>): List<TranslationEntity>; @Insert(onConflict = REPLACE) suspend fun put(row: TranslationEntity) }`
  - `BookEntity.direction: String?` (al final, con valor por defecto `null`), `Book.direction: String?`
  - `BookDao.setDirection(id: String, direction: String?)`, `BookRepository.setDirection(id: String, direction: String?)`
  - `LectorDatabase.translations(): TranslationDao`, `MIGRATION_1_2`

- [ ] **Step 1: Prueba de la migración (falla).** `MigrationOnDeviceTest` con `MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LectorDatabase::class.java)`:

```kotlin
@RunWith(AndroidJUnit4::class)
class MigrationOnDeviceTest {
    private val name = "migracion-prueba.db"
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LectorDatabase::class.java)

    @After fun cleanUp() { InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name) }

    @Test fun de1a2ConservaLosLibrosYAnadeTraducciones() {
        helper.createDatabase(name, 1).use { db ->
            db.execSQL("INSERT INTO books (id, title, author, coverPath, addedAt, lastOpenedAt, progress, locator) VALUES ('a', 'T', NULL, NULL, 1, NULL, 0.5, NULL)")
        }
        helper.runMigrationsAndValidate(name, 2, true, MIGRATION_1_2).use { db ->
            db.query("SELECT title, progress, direction FROM books WHERE id = 'a'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("T", c.getString(0)); assertEquals(0.5f, c.getFloat(1)); assertTrue(c.isNull(2))
            }
            db.execSQL("INSERT INTO translations (`key`, translation, createdAt) VALUES ('k', 'hola', 2)")
        }
    }
}
```

Añadir en `app/build.gradle.kts`, dentro de `android { }`: `sourceSets { getByName("androidTest").assets.srcDir("$rootDir/books/schemas") }`.

- [ ] **Step 2: Correr y ver que falla.** `installFdroidDebug installFdroidDebugAndroidTest` + `am instrument -e class io.github.diegobr4nd.lectorbilingue.MigrationOnDeviceTest`. Esperado: no compila (`MIGRATION_1_2` no existe). Anotar la salida.
- [ ] **Step 3: Implementar.**

```kotlin
// TranslationEntity.kt
/** Traducción guardada de un párrafo entero. [key] = huella SHA-256 (ver TranslationRules.cacheKey en :app). */
@Entity(tableName = "translations")
data class TranslationEntity(@PrimaryKey val key: String, val translation: String, val createdAt: Long)

// TranslationDao.kt
@Dao
interface TranslationDao {
    @Query("SELECT * FROM translations WHERE `key` = :key")
    suspend fun get(key: String): TranslationEntity?

    /** Varias a la vez (pretraducción). SQLite admite 999 parámetros: quien llama manda tandas pequeñas. */
    @Query("SELECT * FROM translations WHERE `key` IN (:keys)")
    suspend fun getAll(keys: List<String>): List<TranslationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: TranslationEntity)
}

// Migrations.kt
/** v1 → v2 (3b): dirección por libro (null = automática) y caché de traducciones. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN direction TEXT DEFAULT NULL")
        db.execSQL("CREATE TABLE IF NOT EXISTS translations (`key` TEXT NOT NULL, translation TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(`key`))")
    }
}
```

`BookEntity`: añadir `@ColumnInfo(defaultValue = "NULL") val direction: String? = null` al final. `BookDao`: `@Query("UPDATE books SET direction = :direction WHERE id = :id") suspend fun setDirection(id: String, direction: String?)`. `LectorDatabase`: `@Database(entities = [BookEntity::class, TranslationEntity::class], version = 2, exportSchema = true)`, `abstract fun translations(): TranslationDao`, `.addMigrations(MIGRATION_1_2)` en `open`, y el KDoc actualizado. `Book`: añadir `val direction: String? = null`; `BookRepository.toBook()` lo copia; `suspend fun setDirection(id: String, direction: String?) = dao.setDirection(id, direction)`. Los dos `FakeBookDao`: implementar `setDirection` sobre su mapa.

Compilar una vez (`:books:kspDebugKotlin`) para generar `schemas/.../2.json` y añadirlo al commit.

- [ ] **Step 4: Prueba JVM de la dirección.** En `books/src/test/.../BookRepositoryTest.kt`:

```kotlin
@Test fun `la direccion se guarda y se puede volver a automatica`() = runTest {
    val repo = repository() // el ayudante que ya usa el archivo
    insertBook("a")
    repo.setDirection("a", "es-en"); assertEquals("es-en", repo.get("a")!!.direction)
    repo.setDirection("a", null); assertNull(repo.get("a")!!.direction)
}
```

- [ ] **Step 5: Correr todo.** `:books:testDebugUnitTest :app:testFdroidDebugUnitTest :books:lint` y en el Pixel `MigrationOnDeviceTest` y `BookDaoOnDeviceTest`. Esperado: OK. Pegar las salidas en el informe.
- [ ] **Step 6: Commit** `feat(3b): base v2 con caché de traducciones y dirección por libro`.

---

### Task 3: `TranslationRules` · dirección, huella, oraciones (agente `estructura`)

**Files:**
- Create: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/data/TranslationRules.kt`
- Test: `app/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/data/TranslationRulesTest.kt`

**Interfaces:**
- Consumes: `LanguagePair` (`engine/api`), `SentenceSplitter.split(paragraph: String): List<String>` (`core/text`).
- Produces (`object TranslationRules`):
  - `fun direction(bookLanguages: List<String>, override: String?): LanguagePair`
  - `fun wire(pair: LanguagePair): String` → `"en-es"`; `fun parseWire(s: String?): LanguagePair?`
  - `fun normalize(text: String): String`
  - `fun cacheKey(modelTag: String, pair: LanguagePair, normalizedText: String): String` (64 hex)
  - `fun batches(normalizedText: String, maxChars: Int = 4000): List<List<String>>` (oraciones agrupadas)
  - `fun join(translatedSentences: List<String>): String`

- [ ] **Step 1: Pruebas (fallan).**

```kotlin
class TranslationRulesTest {
    private val enEs = LanguagePair("en", "es"); private val esEn = LanguagePair("es", "en")

    @Test fun `ingles traduce a espanol y espanol a ingles`() {
        assertEquals(enEs, TranslationRules.direction(listOf("en-US"), null))
        assertEquals(esEn, TranslationRules.direction(listOf("es"), null))
        assertEquals(esEn, TranslationRules.direction(listOf("ES-419"), null))
    }
    @Test fun `sin idioma u otro idioma es ingles a espanol`() {
        assertEquals(enEs, TranslationRules.direction(emptyList(), null))
        assertEquals(enEs, TranslationRules.direction(listOf("fr"), null))
    }
    @Test fun `la eleccion del libro manda y una invalida se ignora`() {
        assertEquals(esEn, TranslationRules.direction(listOf("en"), "es-en"))
        assertEquals(enEs, TranslationRules.direction(listOf("en"), "../x"))
    }
    @Test fun `normalizar colapsa espacios y recorta`() {
        assertEquals("Hola mundo.", TranslationRules.normalize("  Hola \n\t mundo.  "))
        assertEquals("a b", TranslationRules.normalize("a\u00A0b"))
    }
    @Test fun `la huella cambia con el modelo el par o el texto y es hex de 64`() {
        val k = TranslationRules.cacheKey("opus:opus-en-es:1", enEs, "Hi.")
        assertTrue(Regex("^[0-9a-f]{64}$").matches(k))
        assertNotEquals(k, TranslationRules.cacheKey("opus:opus-en-es:2", enEs, "Hi."))
        assertNotEquals(k, TranslationRules.cacheKey("opus:opus-en-es:1", esEn, "Hi."))
        assertNotEquals(k, TranslationRules.cacheKey("opus:opus-en-es:1", enEs, "Hi!"))
        assertEquals(k, TranslationRules.cacheKey("opus:opus-en-es:1", enEs, "Hi."))
    }
    @Test fun `tandas de oraciones de hasta 4000 caracteres sin partir oraciones`() {
        val s = "Una oración de prueba. ".repeat(400).trim()
        val b = TranslationRules.batches(s)
        assertTrue(b.size > 1); assertTrue(b.all { batch -> batch.sumOf { it.length } <= 4000 || batch.size == 1 })
        assertEquals(SentenceSplitter.split(s), b.flatten())
    }
    @Test fun `unir con un espacio`() = assertEquals("Hola. Adiós.", TranslationRules.join(listOf("Hola.", "Adiós.")))
}
```

- [ ] **Step 2: Correr** `:app:testFdroidDebugUnitTest --tests '*TranslationRulesTest'`. Esperado: no compila.
- [ ] **Step 3: Implementar.**

```kotlin
/** Reglas puras de la traducción (sin Android): dirección, huella del caché y tandas de oraciones. */
object TranslationRules {
    private val EN_ES = LanguagePair("en", "es")
    private val ES_EN = LanguagePair("es", "en")
    private val SPACES = Regex("[\\s\\u00A0]+")

    /** [override] (columna `books.direction`) manda si es un par válido; si no, el primer idioma del libro. */
    fun direction(bookLanguages: List<String>, override: String?): LanguagePair {
        parseWire(override)?.let { return it }
        return when (bookLanguages.firstOrNull()?.lowercase()?.substringBefore('-')) {
            "es" -> ES_EN
            else -> EN_ES
        }
    }

    fun wire(pair: LanguagePair): String = "${pair.source}-${pair.target}"

    fun parseWire(s: String?): LanguagePair? {
        val parts = s?.split('-') ?: return null
        if (parts.size != 2) return null
        return runCatching { LanguagePair(parts[0], parts[1]) }.getOrNull()
    }

    fun normalize(text: String): String = text.replace(SPACES, " ").trim()

    fun cacheKey(modelTag: String, pair: LanguagePair, normalizedText: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest("$modelTag\n${wire(pair)}\n$normalizedText".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun batches(normalizedText: String, maxChars: Int = 4000): List<List<String>> {
        val out = mutableListOf<MutableList<String>>(); var size = 0
        for (s in SentenceSplitter.split(normalizedText)) {
            if (out.isEmpty() || size + s.length > maxChars) { out += mutableListOf<String>(); size = 0 }
            out.last() += s; size += s.length
        }
        return out
    }

    fun join(translatedSentences: List<String>): String = translatedSentences.joinToString(" ") { it.trim() }.trim()
}
```

Si `SentenceSplitter.split` deja oraciones de más de `MAX_SENTENCE_CHARS` del motor (ver `OpusEngine`/`FirefoxEngine`), partirlas por la última coma o espacio antes del tope, con prueba `una oracion enorme se parte antes del tope del motor`.

- [ ] **Step 4: Correr** las pruebas: OK.
- [ ] **Step 5: Commit** `feat(3b): reglas de dirección, huella y tandas de oraciones`.

---

### Task 4: `TranslationService` · fila con prioridad, un hilo, caché, motor perezoso (agente `estructura`)

**Files:**
- Create: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/data/TranslationService.kt`
- Create: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/data/EngineProvider.kt`
- Modify: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/LectorApp.kt` (`val translations: TranslationService`)
- Test: `app/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/data/TranslationServiceTest.kt`

**Interfaces:**
- Consumes: `TranslationRules` (Tarea 3), `TranslationDao`/`TranslationEntity` (Tarea 2), `TranslationEngine`, `EngineSelector.choose`, `EngineChoice`, `EngineId`, `EngineConfig`, `Models.installedDir`, `ModelStore.installed()`, `AppSettings` (motor forzado).
- Produces:

```kotlin
enum class Priority { TAP, PREFETCH }

data class TranslateRequest(val pair: LanguagePair, val text: String, val priority: Priority, val resource: String)

sealed interface TranslateResult {
    data class Done(val translation: String) : TranslateResult
    data class MissingModel(val engine: EngineId) : TranslateResult
    data object EngineFailed : TranslateResult      // no se pudo preparar el motor
    data object ParagraphFailed : TranslateResult   // falló una oración: nada en caché
}

/** Lo que el servicio necesita del disco y de los motores (falso en las pruebas). */
interface EngineProvider {
    /** Motores instalados para el par y su etiqueta de modelo ("opus:<id>:<versión>"). Lee disco: llamar fuera del hilo principal. */
    fun installed(pair: LanguagePair): Map<EngineId, String>
    fun engine(id: EngineId): TranslationEngine
    fun config(id: EngineId): EngineConfig
    fun totalRamBytes(): Long
    fun forced(): EngineId?
    fun hasMemoryFor(id: EngineId): Boolean
}

class TranslationService(
    private val provider: EngineProvider,
    private val cache: TranslationDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val worker: CoroutineDispatcher,          // un solo hilo
    private val scope: CoroutineScope,                // vive lo que la app
    private val idleUnloadMillis: Long = 120_000,
) {
    suspend fun cached(pair: LanguagePair, texts: List<String>): Map<String, String>  // texto normalizado → traducción
    suspend fun translate(request: TranslateRequest): TranslateResult
    fun prefetch(requests: List<TranslateRequest>)    // encola PREFETCH, no espera
    fun cancelPrefetchExcept(resource: String)        // al cambiar de capítulo
    fun release()                                      // al salir del Lector: cancela la fila y descarga el motor
    /** true si hay un motor cargado para [pair] (la tarjeta muestra "Traduciendo…" en vez de "Preparando el traductor…"). */
    fun engineReady(pair: LanguagePair): Boolean
}
```

- Comportamiento:
  - `translate`: normaliza; si el texto queda vacío → `Done("")`. Busca en caché con la etiqueta del motor que se usaría; si está → `Done` sin tocar el motor. Si no, encola con su prioridad y espera.
  - **Fila:** `TAP` siempre sale antes que cualquier `PREFETCH` pendiente; dentro de la misma prioridad, en orden de llegada. Un `PREFETCH` ya en curso termina (no se corta a mitad de una oración) y se guarda.
  - Un pedido igual (misma huella) ya en la fila o en curso no se duplica: el segundo espera el resultado del primero (un `Deferred` por huella).
  - **Motor:** al primer pedido que lo necesita (dentro de `worker`): `provider.installed(pair)` → `EngineSelector.choose(installed.keys, provider.totalRamBytes(), provider.forced())`. `Missing` → `MissingModel`. `Use(id)` → si `!provider.hasMemoryFor(id)` → `EngineFailed`; si no, `engine(id).load(pair, config(id))`; si lanza (que no sea cancelación) → `EngineFailed`. Si ya hay otro motor o par cargado, `unload()` antes. **En cada carga se vuelve a mirar lo instalado** (nunca se cachea entre cargas).
  - Traducir: `TranslationRules.batches(text)` → `engine.translate(batch)` por tanda → `join`. Excepción (no cancelación) → `ParagraphFailed` y no se guarda nada. Éxito → `cache.put(TranslationEntity(key, traducción, clock()))`.
  - **Inactividad:** tras `idleUnloadMillis` sin pedidos, `unload()` en `worker`. `release()`: cancela los pendientes (sus `translate` reciben `CancellationException`) y descarga.
  - Nunca registra nada.

- [ ] **Step 1: Pruebas (fallan).** Con `StandardTestDispatcher` como `worker`, `TestScope` como `scope`, un `FakeEngine` (cuenta llamadas, puede bloquearse con un `CompletableDeferred` por oración, puede lanzar) y un `FakeTranslationDao` en memoria:

```kotlin
@Test fun tocarSaleAntesQueLaPretraduccion() = runTest(dispatcher) {
    val engine = FakeEngine(gate = true) // cada translate espera a engine.release()
    val s = service(engine)
    s.prefetch((1..3).map { req("p$it.", Priority.PREFETCH) })
    runCurrent()                        // p1 en curso
    val tap = async { s.translate(req("toque.", Priority.TAP)) }
    runCurrent(); engine.releaseAll(); advanceUntilIdle()
    assertEquals(listOf("p1.", "toque.", "p2.", "p3."), engine.translatedTexts) // p1 ya estaba en curso
    assertEquals(TranslateResult.Done("T(toque.)"), tap.await())
}
@Test fun nuncaDosTraduccionesALaVez() = runTest(dispatcher) { /* FakeEngine registra máximo de llamadas simultáneas; 10 pedidos mezclados; assertEquals(1, engine.maxConcurrent) */ }
@Test fun delCacheNoTocaElMotor() = runTest(dispatcher) { /* dao con la huella de "Hola." → Done sin engine.loadCount ni translate */ }
@Test fun noTraduceDosVecesLoMismo() = runTest(dispatcher) { /* prefetch("A.") y translate(TAP "A.") a la vez → engine.translatedTexts == ["A."] y ambos Done */ }
@Test fun fallaUnaOracionNoGuardaNada() = runTest(dispatcher) { /* FakeEngine lanza en la 2.ª oración → ParagraphFailed y dao vacío */ }
@Test fun sinModeloDiceCualDescargar() = runTest(dispatcher) { /* installed vacío, RAM 8 GiB → MissingModel(OPUS) y engine.loadCount == 0 */ }
@Test fun motorQueNoCargaEsEngineFailedYReintentarVuelveACargar() = runTest(dispatcher) { /* 1.ª load lanza → EngineFailed; 2.ª translate → load otra vez y Done */ }
@Test fun sinMemoriaEsEngineFailedSinCargar() = runTest(dispatcher) { /* hasMemoryFor=false → EngineFailed, loadCount 0 */ }
@Test fun vuelveAMirarLoInstaladoAlCargar() = runTest(dispatcher) { /* installed cambia entre cargas (OPUS→solo FIREFOX tras release()) → usa FIREFOX en la 2.ª; nunca usa un motor no instalado */ }
@Test fun cancelaPretraduccionDeOtroRecurso() = runTest(dispatcher) { /* prefetch de "c1" ×3, cancelPrefetchExcept("c3") → solo la que estaba en curso termina y se guarda; las otras 2 nunca llegan al motor */ }
@Test fun engineReadySoloConMotorCargado() = runTest(dispatcher) { /* false al empezar; true tras un Done; false tras release() */ }
@Test fun seDescargaTrasDosMinutosSinUso() = runTest(dispatcher) { /* Done; advanceTimeBy(119_999) → unloadCount 0; advanceTimeBy(2) → 1 */ }
@Test fun releaseCancelaLaFilaYDescarga() = runTest(dispatcher) { /* translate pendiente → CancellationException; unloadCount 1 */ }
@Test fun textoVacioNoVaAlMotor() = runTest(dispatcher) { /* "  \n " → Done("") y translate no llamado */ }
```

Escribir cada cuerpo completo (las líneas `/* … */` resumen lo que el implementador debe codificar; cada una lleva sus `assert`).

- [ ] **Step 2: Correr** `--tests '*TranslationServiceTest'`: no compila.
- [ ] **Step 3: Implementar** `TranslationService` (fila con dos `ArrayDeque`, un `Mutex` para la cola y un bucle único lanzado en `scope` + `worker`; `Deferred` por huella; `Job` de inactividad reiniciado en cada pedido). Implementar `AndroidEngineProvider(app: LectorApp, worker: CoroutineDispatcher)` en `EngineProvider.kt`:
  - `engine(OPUS) = OpusEngine({ p -> Models.installedDir(app, "opus", wire(p)) }, dispatcher = worker)`, igual con `FirefoxEngine`. Pasar el mismo hilo único al motor resuelve el pendiente de la 2c (el `synchronized` ya no bloquea un hilo de `Dispatchers.Default`; queda como protección barata).
  - `installed(pair)`: `Models.store(app).installed()` filtrado por par → `EngineId → "${engine}:${id}:${modelVersion}"`.
  - `config`: OPUS `EngineConfig()`, Firefox `EngineConfig(threads = 1)`.
  - `forced()`: el motor forzado de `AppSettings` (ver su KDoc).
  - `hasMemoryFor`: `ActivityManager.MemoryInfo().availMem` ≥ 450 MB para OPUS y ≥ 200 MB para Firefox (constantes con comentario: ~330 MB medidos en la 2a más margen).
  - En `LectorApp`: `val translations by lazy { val w = Executors.newSingleThreadExecutor { r -> Thread(r, "motor-traduccion") }.asCoroutineDispatcher(); TranslationService(AndroidEngineProvider(this, w), LectorDatabase…translations(), worker = w, scope = CoroutineScope(SupervisorJob() + w)) }`. **Una sola** `LectorDatabase.open(this)` en la app: convertirla en `private val db by lazy { LectorDatabase.open(this) }` y usarla en `books` y `translations`.
- [ ] **Step 4: Correr** las pruebas: OK. Más `:app:testFdroidDebugUnitTest :app:lintFdroidDebug`.
- [ ] **Step 5: Commit** `feat(3b): servicio de traducción con fila, un hilo y caché`.

---

### Task 5: `ParagraphBridge` · scripts propios y hoja de la tarjeta (agente `estructura`)

**Files:**
- Create: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/reader/ParagraphScripts.kt` (puro: arma los scripts y lee sus respuestas)
- Create: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/reader/ParagraphBridge.kt` (usa `EpubNavigatorFragment.evaluateJavascript`)
- Create: `app/src/main/assets/lector/tarjeta.css` (o donde diga la spec §12 / T2)
- Modify: `books/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/books/readium/ReadiumBooks.kt` y/o `HtmlSanitizer.kt` **solo** según el resultado de T2 (servir la hoja e insertar el `<link>`), con sus pruebas JVM en `ResourceSanitizingTest`/`HtmlSanitizerTest`
- Test: `app/src/test/kotlin/io/github/diegobr4nd/lectorbilingue/ui/reader/ParagraphScriptsTest.kt`, `app/src/androidTest/kotlin/io/github/diegobr4nd/lectorbilingue/ParagraphBridgeOnDeviceTest.kt`

**Interfaces:**
- Produces:

```kotlin
/** Un párrafo de la página visible: [index] es su posición entre los párrafos del recurso (0, 1, 2…). */
data class PageParagraph(val index: Int, val text: String)

sealed interface Card {
    data object Skeleton : Card                     // "Traduciendo…"
    data object Preparing : Card                    // "Preparando el traductor…"
    data class Text(val translation: String) : Card
    data class MissingModel(val label: String) : Card  // texto ya armado con el tamaño
    data class Failed(val label: String, val retry: String) : Card
}

object ParagraphScripts {
    const val SELECTOR = "p, li, blockquote, h1, h2, h3, h4, h5, h6, dd"
    fun find(xCss: Double, yCss: Double, following: Int = 5): String
    fun parseFind(json: String?): List<PageParagraph>   // [tocado, siguientes…]; vacío si no hay párrafo con texto
    fun insert(index: Int, card: Card, labels: CardLabels): String
    fun remove(index: Int): String
    fun indexAt(xCss: Double, yCss: Double): String     // índice del párrafo o de la tarjeta bajo el punto (para cerrar tocando la tarjeta)
}

data class CardLabels(val translationPrefix: String, val skeleton: String, val preparing: String)

class ParagraphBridge(private val navigator: () -> EpubNavigatorFragment?) {
    suspend fun paragraphsAt(xPx: Float, yPx: Float, density: Float): List<PageParagraph>
    suspend fun show(index: Int, card: Card, labels: CardLabels)
    suspend fun hide(index: Int)
}
```

- Reglas de los scripts (todas con pruebas en `ParagraphScriptsTest`):
  - Cada script es una función autoejecutada que recibe **un solo** argumento JSON, armado con `org.json.JSONObject`/`JSONArray` (`JSONObject.quote`) y además con `\u2028`/`\u2029` escapados.
  - `find`: `document.elementFromPoint(x, y)?.closest(SELECTOR)`; lista `document.querySelectorAll(SELECTOR)` **excluyendo** los que estén dentro de `.lector-tarjeta`; devuelve `JSON.stringify([{i, t}, …])` con `t = el.textContent` y los `following` siguientes con texto no vacío.
  - `insert`: busca la tarjeta `aside.lector-tarjeta[data-lector-i="N"]`; si no existe, la crea con `document.createElement('aside')`, `classList.add('lector-tarjeta')`, `dataset.lectorI`, `setAttribute('role','note')`, `setAttribute('aria-label', translationPrefix)` y la pone con `paragraph.after(aside)`. El contenido **solo** con `textContent` (y un `span` de clase `lector-esqueleto` para el esqueleto). Nunca `innerHTML`, `outerHTML`, `insertAdjacentHTML`, `document.write`, `eval`, `Function`, `setAttribute('on…')`.
  - `remove`: quita la tarjeta con ese índice si existe.
- La hoja `tarjeta.css`: fondo tenue, `border-left: 4px solid`, esquinas redondeadas, `font-size: 0.92em`, misma fuente que el libro (`font-family: inherit`), margen; `.lector-esqueleto` con animación que se apaga con `@media (prefers-reduced-motion: reduce)`. Colores con contraste AA sobre blanco (la página es blanca hasta la 3c); `diseno` los fija en la Tarea 6.

- [ ] **Step 1: Pruebas JVM (fallan).**

```kotlin
class ParagraphScriptsTest {
    private val labels = CardLabels("Traducción", "Traduciendo…", "Preparando el traductor…")

    @Test fun `el texto hostil va como dato JSON y no rompe el script`() {
        val evil = "\"); alert(1); (\" </script><img src=x onerror=alert(2)> \u2028 fin"
        val js = ParagraphScripts.insert(3, Card.Text(evil), labels)
        assertFalse(js.contains("</script>"), "</script> sin escapar")
        assertFalse(js.contains("\u2028"))
        assertTrue(js.contains("textContent"))
        listOf("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "Function(").forEach {
            assertFalse(js.contains(it), "usa $it")
        }
    }
    @Test fun `leer la respuesta de find`() {
        assertEquals(listOf(PageParagraph(4, "Hola."), PageParagraph(5, "Adiós.")), ParagraphScripts.parseFind("""[{"i":4,"t":"Hola."},{"i":5,"t":"Adiós."}]"""))
        assertEquals(emptyList(), ParagraphScripts.parseFind(null))
        assertEquals(emptyList(), ParagraphScripts.parseFind("no es json"))
        assertEquals(emptyList(), ParagraphScripts.parseFind("""[{"i":-1,"t":"x"}]"""))
    }
    @Test fun `find respeta el selector y excluye las tarjetas`() {
        val js = ParagraphScripts.find(10.0, 20.0)
        assertTrue(js.contains(ParagraphScripts.SELECTOR)); assertTrue(js.contains("lector-tarjeta"))
    }
}
```

`evaluateJavascript` de Readium devuelve el resultado ya como cadena JSON (en el spike llegaba el valor; comprobar en el paso 4 si llega doblemente citado y, si es así, `parseFind` lo desenvuelve una vez, con prueba).

- [ ] **Step 2: Correr**: no compila.
- [ ] **Step 3: Implementar** `ParagraphScripts` y `ParagraphBridge` (`paragraphsAt` divide el punto por `density`, como en el spike P1). Servir `tarjeta.css` según T2 con sus pruebas JVM (el `<link>` aparece una sola vez aunque `onCreatePublication` se aplique dos veces, spike 3a; la CSP del `<meta>` no cambia).
- [ ] **Step 4: Prueba en el Pixel (`ParagraphBridgeOnDeviceTest`).** Con el libro inventado de `ReaderOnDeviceTest` (reutilizar su `importAndOpen`), en `ReaderActivity`:
  - `paragraphsAt` en el centro del primer párrafo devuelve su texto y los siguientes.
  - `show(i, Card.Text("Hola"))`: la tarjeta existe debajo, el `top` del párrafo no cambia (antes/después, ±1 px) y `getComputedStyle(...).borderLeftWidth` es el de la hoja.
  - `show(i, Card.Text(evil))` con el texto hostil de la prueba JVM: `document.querySelectorAll('img').length` no cambia, `window.__lectorXss` sigue indefinido, y el `textContent` de la tarjeta es exactamente el texto.
  - `hide(i)`: la tarjeta desaparece.
- [ ] **Step 5: Correr** JVM + Pixel: OK. Lint.
- [ ] **Step 6: Commit** `feat(3b): puente con la página para leer párrafos e insertar tarjetas como texto`.

---

### Task 6: Lector · tarjetas, toques, pretraducción, dirección e Idiomas (agente `diseno` para la UI, `estructura` para el ViewModel)

**Files:**
- Create: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/reader/CardRules.kt` (puro)
- Modify: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/reader/ReaderViewModel.kt`
- Modify: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/reader/ReaderScreen.kt` (onTap, reinserción, botón de dirección, Idiomas superpuesto)
- Modify: `app/src/main/kotlin/io/github/diegobr4nd/lectorbilingue/ui/reader/ReaderPreviews.kt` (barra con el botón, hoja de dirección, estados de tarjeta simulados en Compose)
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/assets/lector/tarjeta.css` (colores finales)
- Test: `app/src/test/.../ui/reader/CardRulesTest.kt`, `ReaderViewModelTest.kt`; `app/src/androidTest/.../TapTranslateOnDeviceTest.kt`

**Interfaces:**
- Consumes: `TranslationService`, `TranslateRequest`, `TranslateResult`, `Priority` (Tarea 4); `ParagraphBridge`, `PageParagraph`, `Card`, `CardLabels` (Tarea 5); `TranslationRules.direction/wire/parseWire` (Tarea 3); `BookRepository.setDirection`, `Book.direction` (Tarea 2); `ModelHubApi.pairs` (`RowStatus.sizeBytes`) para el tamaño del modelo que falta; `LanguagesScreen(hub, settings, onBack)`.
- Produces:
  - `ReaderViewModel(bookId, repo, translations: TranslationService, languages: List<String>)` con:
    - `val direction: StateFlow<LanguagePair>`; `fun setDirection(pair: LanguagePair)`
    - `fun onTap(resource: String, hit: List<PageParagraph>)` (hit[0] = tocado; vacío → nada)
    - `fun onResourceShown(resource: String)` (al cambiar o recargarse el recurso visible)
    - `fun retry(resource: String, index: Int)`
    - `val cardOps: SharedFlow<CardOp>` con `sealed interface CardOp { data class Show(val resource: String, val index: Int, val card: CardState); data class Hide(val resource: String, val index: Int) }`
    - `enum`/`sealed CardState { Skeleton, Preparing, Text(String), MissingModel(EngineId), Failed(prepare: Boolean) }` (la pantalla la convierte en `Card` con los textos de `strings.xml`)
  - `object CardRules { fun toggle(open: Map<Int, CardState>, index: Int): Boolean /* true = abrir */; fun prefetchTargets(hit: List<PageParagraph>, cachedOrOpen: Set<String>): List<PageParagraph> }`

- Comportamiento:
  - `onTap`: si el tocado ya tiene tarjeta (o se tocó su tarjeta: la pantalla resuelve el índice con `ParagraphScripts.indexAt`) → `Hide` y se olvida. Si no → `Show(Skeleton)` (o `Preparing` si el motor no está cargado: `translations.engineReady(pair)`), pide `translate(TAP)` y al llegar → `Show(Text)` / `MissingModel` / `Failed`. Luego `prefetch` de los siguientes sin tarjeta.
  - Si el recurso visible cambió antes de que llegue el resultado, **no** se emite `Show` (Review Focus 2). Si la tarjeta se cerró antes de que llegue, **no** se reabre (Review Focus 3).
  - `onResourceShown(r)`: `translations.cancelPrefetchExcept(r)` y reemite `Show` de las tarjetas abiertas de `r` (reinserción, T3).
  - `onCleared()`: `translations.release()`.
  - Dirección: inicial = `TranslationRules.direction(languages, book.direction)`; `setDirection` guarda `wire(pair)` con `repo.setDirection` y cierra todas las tarjetas abiertas.
- Pantalla:
  - `InputListener.onTap(event)`: con `event.point` → `bridge.paragraphsAt(...)` → `vm.onTap(currentHref, hit)`; devuelve `true` si hubo párrafo (Readium no hace nada más), `false` si no.
  - Colector de `vm.cardOps` → `bridge.show/hide` solo si `op.resource == currentHref`.
  - Al cambiar `currentHref` (colector de `currentLocator` ya existente) → `vm.onResourceShown(href)` cuando la página esté lista (señal de T3).
  - Botón de dirección en `ReaderTopBar` (texto "EN → ES", 48 dp, `contentDescription` "Idioma de traducción: inglés a español") → hoja inferior con las dos direcciones (marca la actual con ícono y texto, como el Índice).
  - `MissingModel` → al tocar la tarjeta (`indexAt` + estado) se muestra `LanguagesScreen(app.hub, app.settings, onBack = { cerrar })` **superpuesta a pantalla completa dentro de `ReaderActivity`** (con `BackHandler`); al cerrarla, las tarjetas `MissingModel` abiertas se reintentan.
  - `Failed` → tocar la tarjeta = `vm.retry`.
  - TalkBack: según T1 (toque doble). `aria-label`/`role="note"` en la tarjeta; anuncio "Traduciendo…" con `View.announceForAccessibility` solo para el toque (no para la pretraducción).
- Textos nuevos (`strings.xml`): `reader_card_translation` "Traducción", `reader_card_skeleton` "Traduciendo…", `reader_card_preparing` "Preparando el traductor…", `reader_card_missing` "Falta el idioma %1$s → %2$s (%3$s) · Descargar", `reader_card_failed` "No se pudo traducir este párrafo · Reintentar", `reader_card_prepare_failed` "No se pudo preparar el traductor · Reintentar", `reader_direction` "%1$s → %2$s", `reader_direction_desc` "Idioma de traducción: %1$s a %2$s", `reader_direction_title` "Traducir de", nombres `language_en` "inglés", `language_es` "español" (si no existen ya; buscar antes con grep).

- [ ] **Step 1: Pruebas JVM (fallan)** en `CardRulesTest` y `ReaderViewModelTest`, con un `TranslationService` real sobre `FakeEngine`/`FakeTranslationDao` de la Tarea 4 (moverlos a un archivo compartido de pruebas `app/src/test/.../data/TranslationFakes.kt`):

```kotlin
@Test fun tocarAbreConEsqueletoYLuegoTexto() = runTest(dispatcher) {
    val (vm, ops) = vmWithOps()
    vm.onResourceShown("c1.xhtml"); vm.onTap("c1.xhtml", listOf(PageParagraph(0, "Hi.")))
    runCurrent(); assertEquals(CardOp.Show("c1.xhtml", 0, CardState.Preparing), ops.first())
    advanceUntilIdle(); assertEquals(CardOp.Show("c1.xhtml", 0, CardState.Text("T(Hi.)")), ops.last())
}
@Test fun tocarOtraVezCierra() = runTest(dispatcher) { /* dos onTap al mismo índice tras llegar → último op Hide(c1, 0) */ }
@Test fun cerrarAntesDeQueLlegueNoReabre() = runTest(dispatcher) { /* motor con gate; onTap, onTap; releaseAll → no hay Show(Text) después del Hide */ }
@Test fun noInsertaEnOtroRecurso() = runTest(dispatcher) { /* onTap c1; onResourceShown(c3) antes de llegar → ningún Show(c1, Text); pero la traducción está en caché */ }
@Test fun pretraduceLosSiguientesSinTarjeta() = runTest(dispatcher) { /* hit con 6 párrafos → el motor recibe los 5 siguientes después del tocado */ }
@Test fun reinsertaAlVolverAlRecurso() = runTest(dispatcher) { /* abrir c1:0, onResourceShown(c3), onResourceShown(c1) → reemite Show(c1, 0, Text) */ }
@Test fun cambiarDireccionGuardaYCierraTarjetas() = runTest(dispatcher) { /* setDirection(es-en) → dao.direction == "es-en", Hide de las abiertas, direction.value == es-en */ }
@Test fun sinModeloMuestraLaTarjetaDeDescarga() = runTest(dispatcher) { /* installed vacío → Show(MissingModel(OPUS)) */ }
@Test fun reintentarTrasFallo() = runTest(dispatcher) { /* falla una vez → Failed(prepare=false); retry → Text */ }
@Test fun alLimpiarseSueltaElMotor() = runTest(dispatcher) { /* vm.clear (ViewModelStore) → engine.unloadCount == 1 */ }
```

(Escribir cada cuerpo completo con sus `assert`; `vmWithOps()` crea el VM con `BookRepository` sobre `FakeBookDao`, el servicio con `worker = dispatcher` y recoge `cardOps` en una lista.)

- [ ] **Step 2: Correr**: no compila.
- [ ] **Step 3: Implementar** `CardRules` y el `ReaderViewModel` ampliado (el `viewModelFactory` de `ReaderScreen` pasa `app.translations` y `publication.metadata.languages`).
- [ ] **Step 4: Correr** JVM: OK.
- [ ] **Step 5: Bocetos (agente `diseno`).** Antes de la pantalla: boceto en texto de la barra con el botón de dirección, la hoja de dirección y los 5 estados de la tarjeta → el controlador lo enseña a Juan y espera su visto bueno. Después, Previews en `ReaderPreviews.kt` (claro/oscuro, 360/840, letra 1 y 2) del botón, la hoja y una simulación Compose de la tarjeta (mismos colores que `tarjeta.css`), y colores finales AA en `tarjeta.css`.
- [ ] **Step 6: Implementar la pantalla** (`ReaderScreen`) según "Pantalla" arriba.
- [ ] **Step 7: Prueba en el Pixel (`TapTranslateOnDeviceTest`).** Con un motor falso inyectado (añadir a `LectorApp` un `internal var translationsOverride: TranslationService? = null` solo para pruebas, o construir el VM con un servicio falso vía la fábrica: elegir lo más simple que no exponga nada en release) y el libro inventado:
  - Toque (vía `vm.onTap` con `bridge.paragraphsAt` en el centro del 2.º párrafo, sin `input tap`) → la tarjeta aparece con "T(…)" debajo y el párrafo no se mueve.
  - Segundo toque → desaparece.
  - Índice al capítulo 3 y vuelta → la tarjeta abierta reaparece.
  - Botón de dirección → elegir "español → inglés" → `books.get(id).direction == "es-en"`.
- [ ] **Step 8: Correr** JVM + Pixel (también `ReaderOnDeviceTest`, `LibraryOnDeviceTest`): OK. Lint.
- [ ] **Step 9: Commit(s)** `feat(3b): tocar un párrafo muestra su traducción debajo` y `feat(3b): dirección de traducción por libro`.

---

### Task 7: Motor real en el Pixel y tiempos (agente `infraestructura`)

**Files:**
- Create: `app/src/androidTest/kotlin/io/github/diegobr4nd/lectorbilingue/TapTranslateTimingOnDeviceTest.kt`
- Modify: la spec §12 (tabla de tiempos)

- [ ] **Step 1:** Prueba con el **motor real** (el modelo `en-es` ya instalado en el teléfono de Juan; `assumeTrue` si no está) y un libro inventado en inglés (texto propio, nunca los textos privados de Juan): medir con `SystemClock.elapsedRealtime()` (solo números en el informe):
  - toque → `Done` con motor frío (incluye carga);
  - toque → `Done` con motor cargado, párrafo típico (~40-60 palabras);
  - toque sobre un párrafo ya pretraducido;
  - palabras/s en 20 párrafos.
- [ ] **Step 2:** Comprobar las metas: < 2 s por párrafo con motor cargado y ≥ 15 palabras/s; pretraducido < 200 ms. Si no se cumplen, **no** cambiar beam ni hilos: informar al controlador.
- [ ] **Step 3:** `am force-stop` antes (regla de memoria de `entorno-windows-juan`). Anotar en la spec §12 y commit `test(3b): tiempos de tocar y traducir en el Pixel`.

---

### Task 8: Revisiones y correcciones (agentes `seguridad` y `diseno`, solo lectura; después correcciones)

- [ ] **Step 1: `seguridad`** sobre `main...feat/tocar-traducir`: scripts propios (§7 de la spec), JSON y `textContent`, sin `addJavascriptInterface`, CSP intacta o cambio aprobado, hoja CSS servida sin abrir otra ruta a archivos, caché (huella, nada de texto en logs), memoria, `MIGRATION_1_2`. Pide añadir a `MaliciousEpubOnDeviceTest` casos `t1…t5`: párrafo con comillas y `</script>`, `<img onerror>` escrito como texto, `\u2028`, párrafo de 50 000 caracteres (no se cuelga: < 30 s y la tarjeta muestra texto o error), libro que intenta crear un `aside.lector-tarjeta` falso (no se confunde con una tarjeta: el índice excluye tarjetas creadas por la app y el libro no puede ejecutar scripts).
- [ ] **Step 2: `diseno`:** capturas (Previews + Pixel con libro inventado) de la tarjeta en sus 5 estados, botón y hoja de dirección, claro/oscuro, letra 200 %, tableta; auditoría `material-3` de `ui/reader` (meta ≥ 80/100); contraste AA de la tarjeta sobre blanco; TalkBack por semántica y, en la puerta, con Juan.
- [ ] **Step 3: Correcciones** con TDD, un commit `fix(3b): …` por hallazgo alto o medio aceptado; los bajos no corregidos van a la descripción del PR.
- [ ] **Step 4: Revisión de código de toda la rama** (`superpowers:requesting-code-review`).
- [ ] **Step 5: Verificación completa:**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew lint test assembleFdroidDebug assembleFdroidRelease assemblePlayRelease
```

Más todas las instrumentadas en el Pixel con `am instrument`: `MigrationOnDeviceTest`, `ParagraphBridgeOnDeviceTest`, `TapTranslateOnDeviceTest`, `TapTranslateTimingOnDeviceTest`, `MaliciousEpubOnDeviceTest`, `ReaderOnDeviceTest`, `TocOnDeviceTest`, `LibraryOnDeviceTest`, `ReadiumBooksOnDeviceTest`, `BookDaoOnDeviceTest`, `WelcomeOnDeviceTest`, `LanguagesOnDeviceTest`, `ComponentsOnDeviceTest`. Pegar la salida en el informe. Tamaño del APK release antes/después (copia de `main` en una ruta corta, p. ej. `C:/w3b`, con los submódulos anidados copiados de la copia local; ver memoria `arranque-fase-3`).

---

### Task 9: Puerta 3b con Juan (sesión principal)

- [ ] **Step 1:** Pedir permiso a Juan para hacer push de `feat/tocar-traducir`; él abre el PR **en borrador** desde la web. Comprobar el CI con `curl -s "https://api.github.com/repos/DiegoBr4nd/lector-bilingue/actions/runs?head_sha=$(git rev-parse HEAD)" | grep -E '"(status|conclusion)"'`. **Antes de cada push posterior, comprobar que el PR no está ya fusionado** (`curl -s …/pulls/<n> | grep '"merged"'`).
- [ ] **Step 2:** `installFdroidDebug` en el Pixel. Juan hace la puerta de la spec §1 (puntos 1 a 7) con un libro real suyo. Anotar el resultado de cada punto. No se registra ni se copia nada del libro.
- [ ] **Step 3:** Juan aprueba las capturas (Tarea 8, Paso 2).
- [ ] **Step 4:** Actualizar la memoria `arranque-fase-3` (3b hecha; sigue la 3c) y proponer a Juan la línea "Fase actual" de `CLAUDE.md`.
- [ ] **Step 5:** Con el CI en verde y la puerta pasada, Juan marca el PR como listo y lo fusiona.
