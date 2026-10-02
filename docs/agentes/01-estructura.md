# 01 · Agente de ESTRUCTURA (arquitectura y código de la app)

> **Misión:** diseñar y escribir el código Kotlin de la app: módulos, lógica, motores de traducción (lado Kotlin), lector y caché.
> **Lee primero:** `CLAUDE.md`, `docs/contexto-y-decisiones.md`, `docs/agentes/05-metodologia.md`.
> **Trabaja con:** *infraestructura* (te entrega las librerías nativas y los modelos), *diseño* (te entrega las pantallas), *seguridad* (revisa tus cambios sensibles).

## 1. Skills que debe usar este agente

| Skill | Qué aporta | Instalación (la hace Juan) |
|---|---|---|
| **Android Skills oficiales de Google** (`android/skills`): `navigation-3`, `adaptive`, `styles`, `testing-setup`, `edge-to-edge` | Navigation 3, diseño adaptable, temas, pruebas y borde a borde | Ya copiada en `.claude/skills/` (Paso 0.5 del plan maestro) |
| **compose-agent** (hamen/compose_skill) | Guía y revisión de código Compose mientras se escribe | `/plugin marketplace add hamen/compose_skill` y `/plugin install compose-agent@compose_skill` |
| **jetpack-compose-audit** (mismo repo) | Auditoría del repo con nota 0-100 (rendimiento, estado, efectos) | `/plugin install jetpack-compose-audit@compose_skill` |
| **Superpowers** | Proceso: plan, TDD, revisión | Ver `05-metodologia.md` |

## 2. Arquitectura propuesta

**Patrón:** arquitectura oficial recomendada por Android (capas UI → dominio → datos) con **MVVM** (Model-View-ViewModel: la pantalla solo dibuja; el ViewModel guarda el estado y decide; los datos viven aparte).

Analogía: un restaurante. La **pantalla** es el mesero (muestra y toma pedidos), el **ViewModel** es el jefe de cocina (decide qué preparar), y los **repositorios/motores** son la cocina y la despensa.

### Módulos Gradle
Un **módulo** es un pedazo del proyecto que compila por separado. Separar ayuda a que cada agente trabaje sin pisar a otro.

```
:app                      → arranque, navegación, inyección de dependencias
:core:model               → clases de datos puras (Libro, Párrafo, ParIdiomas...)
:core:ui                  → sistema de diseño (tema, tipografía, componentes) ← lo llena DISEÑO
:core:database            → Room: caché de traducciones, biblioteca, progreso
:core:prefs               → DataStore: ajustes del usuario
:core:text                → partir texto en oraciones (ICU BreakIterator), normalizar
:engine:api               → interfaz TranslationEngine (contrato común de motores)
:engine:opus              → motor OPUS vía CTranslate2 (JNI) ← la parte nativa la entrega INFRA
:engine:firefox           → motor Firefox (Bergamot) ← reutilizable de Offline Translator (GPL-3.0)
:models                   → catálogo, descarga, verificación SHA-256, importación manual
:feature:library          → pantalla Biblioteca
:feature:reader           → lector EPUB (Readium) + tocar y traducir
:feature:book-translation → traducción del libro completo en segundo plano + exportar
:feature:pdf              → (fase 5) PDF en reflow
:feature:settings         → ajustes, idiomas, licencias
```

### Contrato del motor (núcleo de todo)
```kotlin
interface TranslationEngine {
    val id: String                       // "opus" | "firefox"
    suspend fun load(pair: LanguagePair, config: EngineConfig)
    suspend fun translate(sentences: List<String>): List<String>
    fun unload()
}

data class EngineConfig(
    val beamSize: Int = 1,               // 1 = rápido (tocar); 4 = calidad (libro completo)
    val threads: Int = 4,                // NUNCA más que los núcleos rápidos
)
```

**Reglas del motor:**
- `EngineSelector` elige motor por par de idiomas y RAM disponible: OPUS si existe el modelo y hay **≥ 3 GB de RAM total**; si no, Firefox.
- Hilos: detectar núcleos rápidos (frecuencia máxima por núcleo en `/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq`); por defecto 4. **Prohibido** usar todos los núcleos: medimos que 8 hilos es 4 veces más lento.
- Un solo motor cargado a la vez (RAM). Liberar con `unload()` al salir del lector.
- Todo en `Dispatchers.Default` con una cola única: nunca dos traducciones en paralelo sobre el mismo motor.

### Librerías base (todas FOSS, compatibles con F-Droid)
| Para | Librería | Nota |
|---|---|---|
| UI | Jetpack Compose + Material 3 | |
| Navegación | Navigation 3 | Usar la skill oficial |
| Inyección de dependencias | Hilt | Apache-2.0 |
| Asincronía | Coroutines + Flow | |
| Base de datos | Room | Caché de traducciones |
| Ajustes | DataStore | |
| EPUB | **Readium Kotlin Toolkit** (BSD-3) | Soporta EPUB 2/3 y PDF; minSdk 24 |
| PDF texto | PdfBox-Android (Apache-2.0) | Solo extracción de texto (fase 5) |
| Partir oraciones | `android.icu.text.BreakIterator` | Viene en Android, sin dependencia extra |

> **Prohibido:** Firebase, Play Services, AdMob, Crashlytics, analítica de cualquier tipo, MuPDF (AGPL) salvo decisión explícita de Juan.

## 3. Diseño de datos clave

### Caché de traducciones (Room)
- Clave: `sha256(textoOriginal + par + motor + versiónModelo + beam)`.
- Valor: traducción + fecha.
- Efecto: volver a un párrafo es instantáneo y retomar un libro no repite trabajo.
- Cambiar la versión del modelo invalida la caché sola, porque cambia la clave.

### Unidad de traducción
- El lector traduce **por párrafo**, pero el motor recibe **oraciones** (los modelos se entrenaron con oraciones).
- Contexto: no hace falta "ventana deslizante" en la v1; OPUS trabaja oración por oración. Se evaluará después.
- Partir con `BreakIterator` respetando abreviaturas ("Dr.", "e.g."). Pruebas unitarias con casos difíciles: diálogos con comillas, puntos suspensivos, números decimales.

## 4. Tareas por fase

### Fase 1 · Proyecto base (1a) + motor (1b) ⭐ (la prueba de fuego)
**1a**
1. Crear el proyecto con Android Studio (*New Project → Empty Activity*) o con Gradle; no dependas de la Android CLI (bloqueada en el Windows de Juan): Kotlin, Compose, minSdk 26, paquete `io.github.<usuario>.lectorbilingue`.
2. Crear los módulos `:app`, `:core:text`, `:engine:api`, `:engine:opus`.

**1b**
3. `:core:text`: partidor de oraciones con TDD (mínimo 15 casos de prueba, incluidos los 25 textos de la prueba de calidad).
4. `:engine:opus`: puente JNI hacia la librería `libct2bridge.so` que entrega INFRA. Funciones: `nativeLoad(modelDir, threads, beam)`, `nativeTranslate(sentences)`, `nativeUnload()`.
5. Pantalla temporal: caja de texto + botón "Traducir" + resultado + tiempo en milisegundos.
6. Importar el modelo manualmente (copiado al teléfono por USB) para no depender todavía del gestor de descargas.
7. **Benchmark** con los 25 textos: imprimir palabras/s y la mediana por párrafo.

**Puerta:** en el Pixel 7, **sin internet**, un párrafo se traduce en **< 2 s** y el benchmark da **≥ 15 palabras/s** con beam 1.

**Plan B (si CTranslate2 en Android se complica más de 1 semana):** pasar a **ONNX Runtime para Android** (tiene paquete oficial), exportando el modelo Marian con Hugging Face Optimum. Lo decide Juan con datos de ambos intentos.

### Fase 2 · Gestor de modelos + motor Firefox
1. `:models`: catálogo JSON firmado (formato definido por INFRA) → lista de pares disponibles con tamaño y motor.
2. Descarga con WorkManager, reanudable, **verificación SHA-256 obligatoria** antes de usar un modelo.
3. Importación manual desde archivo (Storage Access Framework): permite usar la app **sin dar permiso de internet**.
4. `:engine:firefox`: integrar el motor Bergamot reutilizando código de Offline Translator (GPL-3.0). Mantener el aviso de copyright original en los archivos copiados.
5. `EngineSelector` con la regla de RAM.
6. **Tarea de calidad:** correr los 25 textos con OPUS beam 1 y beam 4, y guardar ambas salidas para que Juan las compare. Si beam 1 pierde mucho, el lector usará beam 4 en párrafos cortos.

### Fase 3 · Lector EPUB + tocar y traducir
1. Integrar Readium: abrir EPUB, paginar o desplazar, recordar la posición.
2. Detectar el párrafo tocado (Readium expone la selección y el localizador).
3. Mostrar la traducción según el modo elegido por DISEÑO (debajo del párrafo / hoja inferior / intercalado).
4. Pretraducir en segundo plano los 2-3 párrafos siguientes al visible (sensación de "instantáneo").
5. Caché Room conectada.

### Fase 4 · Libro completo
1. Servicio en primer plano (*Foreground Service*, tipo `dataSync`) con notificación de progreso: Android no lo mata al bloquear la pantalla.
2. Recorrer los capítulos del EPUB, traducir y guardar en caché; reanudable si se interrumpe.
3. Pausa automática si la batería está bajo 20 % y no está cargando, o si el teléfono se calienta (`PowerManager.getCurrentThermalStatus`).
4. Exportar EPUB bilingüe: copiar el XHTML original e insertar debajo de cada `<p>` un `<p class="trad">`. Validar con EPUBCheck en CI.

### Fase 5 · PDF y DOCX
1. PDF: renderizado con el adaptador PDFium de Readium; texto con PdfBox-Android → vista **reflow** del texto con su traducción.
2. Detectar PDFs escaneados (sin texto) y mostrar un aviso. El OCR queda fuera de la v1.
3. DOCX: leer `word/document.xml` (es un ZIP con XML) y mostrar en reflow.

### Fase 6 · Publicación
- Flavors (variantes de compilación): `fdroid` (100 % libre, botón de donar) y `play` (igual, con compra opcional de apoyo vía Play Billing **solo en ese flavor**).
- Pantalla de licencias y atribuciones (CC-BY de OPUS obligatoria).
- Pasar la auditoría `jetpack-compose-audit` con **≥ 80/100**.

## 5. Qué le pides a Juan en cada fase
- **Fase 1:** conectar el Pixel por USB, copiar el modelo al teléfono (te daremos el comando) y mirar el benchmark.
- **Fase 2:** aprobar el formato del catálogo y probar en un celular básico si consigue uno prestado.
- **Fase 3:** leer 10 minutos un libro real y dar opinión de la experiencia.
- **Fase 4:** dejar traduciendo un libro completo y reportar duración, temperatura y batería.
