# Contexto y decisiones del proyecto

> **Para los agentes:** este documento es la fuente de verdad sobre *qué* se construye y *por qué*.
> Antes de proponer un cambio que contradiga algo de aquí, pregúntale a Juan.
> Cada decisión dice en qué evidencia se apoya.

## 1. Qué es la app

**Lector bilingüe offline para Android.** Nombre provisional: `lector-bilingue`.

- Abre libros y documentos (EPUB, PDF con texto digital, DOCX, TXT).
- Tocas un párrafo y ves su traducción al instante.
- Puede traducir el libro entero en segundo plano y exportar un EPUB bilingüe.
- **Todo pasa en el teléfono.** Ningún texto del usuario sale del dispositivo.
- **Gratis y de código abierto.** Se publica en F-Droid y en Google Play.

### Para quién
- Personas que aprenden inglés y quieren leer libros reales con ayuda.
- Estudiantes y profesionales con PDFs técnicos en inglés.
- Lectores que quieren libros que no existen en su idioma.
- Mercado inicial: Latinoamérica, empezando por Colombia. Par principal: **inglés → español latino**.

### Qué NO es (por ahora)
- No es un traductor de texto suelto tipo Google Translate (ya existe Offline Translator).
- No rehace PDFs conservando el diseño exacto en la v1: el PDF se muestra en modo "reflow" (texto que se reacomoda a la pantalla).
- No usa servidores propios ni cuentas de usuario.

## 2. Motores de traducción (decidido con pruebas)

| Motor | Papel | Tamaño | Evidencia |
|---|---|---|---|
| 📦 **OPUS-MT tc-big** (Helsinki-NLP), cuantizado int8, corrido con **CTranslate2** | **Principal** donde exista el par de idiomas | ~230 MB por par | Nota 4,04/5 a ciegas (DeepL 4,44). En Pixel 7: **21,9 palabras/s, 0,9 s por párrafo, ~330 MB RAM** (beam 1, 4 hilos) |
| ⚡ **Firefox Translations** (Mozilla, Bergamot/Marian) | **Respaldo**: celulares con poca RAM e idiomas sin OPUS grande | 17-44 MB por par | Nota 3,28/5. Muy rápido. Ya probado en Android por Offline Translator |
| 💎 TranslateGemma 4B | **Descartado** | 2,5 GB | En Pixel 7: 3,1 tokens/s y ~1 min por párrafo |

### Resultados de la prueba de calidad (25 fragmentos, evaluación a ciegas por Juan)

| Categoría | DeepL | OPUS grande | Firefox |
|---|---|---|---|
| Literatura | 4,00 | 3,83 | 3,17 |
| Coloquial / modismos | 4,40 | **3,20** | **2,20** |
| Académico | 4,33 | 4,67 | 4,00 |
| Legal / formal | 5,00 | 4,00 | 3,00 |
| Técnico | 4,75 | 4,25 | 3,25 |
| Trampas (falsos amigos) | 4,50 | 4,75 | 4,50 |
| **Promedio** | **4,44** | **4,04** | **3,28** |

**Debilidades conocidas:**
- Lo coloquial es el punto débil de ambos motores locales. Mejora futura: glosario de modismos.
- La nota de OPUS se midió con **beam 4**. Comparación a ciegas beam 1 vs beam 4 (fase 2a, ver abajo): beam 1 pierde poco.
- Traducir **párrafos enteros** de una vez puede hacer que el modelo invente contenido o se salte oraciones; **oración por oración** no (comprobado en la fase 2a). La app siempre parte en oraciones.
- Español: algunos modelos producen español de España ("furgoneta", "estropear"). El objetivo es español latino neutro.

### Ajustes del motor medidos en Pixel 7 (CTranslate2, int8)

| Ajuste | Palabras/s | Párrafo típico | Uso recomendado |
|---|---|---|---|
| beam 1, 4 hilos | 21,9 | 0,9 s | Tocar y traducir |
| beam 4, 4 hilos | 8,1 | 2,9 s | Libro completo en segundo plano |
| beam 1, 8 hilos | 5,5 | 3,9 s | **No usar**: los núcleos lentos frenan todo |

> **Regla:** usar como máximo tantos hilos como núcleos rápidos tenga el teléfono (normalmente 4).

### Decisión: beam por uso (fase 2a, 2026-10-02, decidido por Juan)

| Uso | Beam | Por qué |
|---|---|---|
| Tocar y traducir | **1** | Respuesta inmediata (~1 s por párrafo típico en la app) |
| Libro completo en segundo plano | **4** | Nadie espera mirando la pantalla; ganamos calidad |

**Evidencia** (25 textos privados de Juan, evaluación a ciegas 1-5, traducidos en el Pixel 7 con la tubería de la app):

| | Nota promedio | Textos ganados | Palabras/s* | Mediana* | Peor caso* |
|---|---|---|---|---|---|
| Beam 1 | 4,08 | 0 | 12,7 | 1.547 ms | 3.736 ms |
| Beam 4 | 4,32 | 5 | 5,6 | 4.094 ms | 7.566 ms |
| Empates | | 20 | | | |

\* Medido en una prueba instrumentada, sin acceso a los 2 núcleos más rápidos (grupo "foreground" del sistema). En la app visible, beam 1 da 21,4 palabras/s y 975 ms (puerta 1b); beam 4 se estima en ~9,5 palabras/s y ~2,5 s por párrafo.

## 3. Distribución y negocio

- **Licencia de la app:** GPL-3.0-or-later. Permite reutilizar código de Offline Translator (GPL-3.0).
- **F-Droid (canal principal):** prohíbe librerías propietarias (AdMob, Firebase, Play Services, Play Billing). Sin anuncios.
- **Google Play:** misma app. Opcional: compra única de "Apoyar" en un *flavor* separado (variante de compilación).
- **Ingresos:** donaciones (Liberapay/Ko-fi) en F-Droid; compra de apoyo en Play. Expectativa realista: poco dinero al principio; el valor es portafolio y reputación.
- **Firma:** builds reproducibles para que F-Droid publique el APK con la firma de Juan (tiene cuenta de desarrollador verificada en Google).
- **Verificación de desarrolladores de Google:** obligatoria desde el 30 sept 2026 en Brasil, Indonesia, Singapur y Tailandia; global en 2027. Juan registra el paquete con su cuenta.

## 4. Licencias de terceros a respetar

| Componente | Licencia | Obligación |
|---|---|---|
| Modelos Firefox Translations | MPL-2.0 | Avisar y enlazar la fuente |
| Modelo OPUS-MT tc-big en-es | CC-BY-4.0 | **Atribución** visible en "Acerca de" |
| CTranslate2 | MIT | Aviso de licencia |
| Readium Kotlin Toolkit | BSD-3-Clause | Aviso de licencia |
| Offline Translator (si se reutiliza código) | GPL-3.0 | La app también GPL-3.0 (ya decidido) |
| MuPDF | AGPL | **Evitar** salvo decisión explícita; preferir PDFium (vía Readium) + PdfBox-Android (Apache-2.0) |
| NLLB-200 (Meta) | CC-BY-NC | **Prohibido** (no comercial) |

## 5. Dispositivos

- **Referencia de desarrollo:** Google Pixel 7 (8 GB RAM).
- **Objetivo:** "la mayoría de móviles". OPUS en celulares de 4 GB o más; Firefox en los demás.
- **minSdk propuesto:** 26 (Android 8). El agente de estructura puede ajustarlo con justificación.

## 6. Equipo de desarrollo de Juan

- Laptop ASUS TUF F15, 16 GB RAM, Windows (dual boot con Parrot OS).
- Stack conocido: Python, React, TypeScript. **Kotlin es nuevo para él:** explicar en lenguaje simple, con analogías.
- Nivel autodescrito: intermedio.
