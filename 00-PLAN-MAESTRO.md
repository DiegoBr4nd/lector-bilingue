# 🧭 Plan maestro · Lector bilingüe offline

> **Para Juan.** Esta es tu guía: qué hacer tú, en qué orden, y qué pedirle a los agentes en cada fase.
> Los agentes tienen sus propias guías en `docs/agentes/`. Tú no necesitas leerlas completas, pero sí aprobar lo que propongan.

## 📦 Qué hay en este kit

```
00-PLAN-MAESTRO.md              ← estás aquí (tu guía)
CLAUDE.md                       ← reglas que Claude Code lee en cada sesión
docs/
  contexto-y-decisiones.md      ← qué construimos y por qué (con las pruebas que hiciste)
  agentes/
    01-estructura.md            ← arquitectura y código Kotlin
    02-infraestructura.md       ← compilación, código nativo, modelos, publicación
    03-seguridad.md             ← privacidad y seguridad
    04-diseno.md                ← interfaz y experiencia de lectura
    05-metodologia.md           ← cómo se trabaja (Superpowers, fases, puertas)
.claude/agents/                 ← los 4 subagentes listos para Claude Code
  estructura.md · infraestructura.md · seguridad.md · diseno.md
```

**Cómo funciona el equipo** (analogía: una obra de construcción)
- **Tú** eres el dueño de la casa: decides, apruebas planos y recibes cada etapa.
- **La sesión principal de Claude Code** es el maestro de obra: coordina.
- **Los 4 subagentes** son especialistas: estructura (albañil), infraestructura (ingeniero), seguridad (inspector) y diseño (arquitecto de interiores).
- **Superpowers** es el manual de procedimientos: obliga a planear, probar y revisar.

---

## 🛠️ FASE 0 · Preparación (la haces tú; ~2-3 horas)

### Paso 0.1 · Cuentas
- [ ] **Plan de Claude con Claude Code:** necesitas plan **Pro o Max** (el plan gratis no incluye Claude Code).
- [ ] **GitHub:** cuenta activa (si no tienes, créala en github.com).

### Paso 0.2 · Instalar programas en Windows
Instálalos en este orden:

1. [ ] **Git for Windows** → https://git-scm.com/downloads/win
   - Deja las opciones por defecto.
   - Sirve para guardar versiones del código, y Claude Code lo usa para su terminal Bash.
2. [ ] **Node.js LTS** → https://nodejs.org
   - Lo necesitas solo para instalar algunas skills con `npx`.
3. [ ] **Android Studio** (versión estable) → https://developer.android.com/studio
   - Al abrirlo por primera vez, acepta la instalación estándar (SDK, emulador).
   - Trae `adb`, la herramienta que habla con tu Pixel por USB.
   - Tu laptop de 16 GB de RAM es suficiente.
4. [ ] *(Opcional)* **Android CLI** (oficial de Google, para agentes) → https://d.android.com/tools/agents
   - Si Windows la bloquea con "Una directiva de Control de aplicaciones bloqueó este archivo", **sáltala**: no es indispensable.
5. [ ] **Claude Code**: abre **PowerShell** (no hace falta como administrador) y pega:
   ```powershell
   irm https://claude.ai/install.ps1 | iex
   ```
   Cierra y abre PowerShell, y verifica:
   ```powershell
   claude --version
   ```
   Si dice "no se reconoce", revisa la guía oficial: https://code.claude.com/docs/en/troubleshoot-install

> 💡 **Opcional:** Android Studio Canary ya permite usar Claude como agente *dentro* del editor ("Bring Your Own Agent"). No es necesario: la terminal funciona perfecto.

### Paso 0.3 · Preparar el Pixel 7
1. [ ] *Ajustes → Acerca del teléfono* → toca **7 veces** "Número de compilación" (activa el modo desarrollador).
2. [ ] *Ajustes → Sistema → Opciones de desarrollador* → activa **Depuración por USB**.
3. [ ] Conecta el Pixel por cable. En el teléfono, acepta "¿Permitir depuración USB?".
4. [ ] En PowerShell:
   ```powershell
   adb devices
   ```
   Debe aparecer tu teléfono con la palabra `device`. Si `adb` no se reconoce, agrégalo desde Android Studio (*SDK Manager → SDK Tools → Android SDK Platform-Tools*).

### Paso 0.4 · Crear el repositorio
1. [ ] En github.com → **New repository**:
   - Nombre: `lector-bilingue`
   - **Público**
   - Licencia: **GNU General Public License v3.0**
   - Marcar "Add a README file"
2. [ ] En PowerShell, en la carpeta donde guardas tus proyectos:
   ```powershell
   git clone https://github.com/<tu-usuario>/lector-bilingue.git
   cd lector-bilingue
   ```
3. [ ] Copia **todo el contenido de este kit** dentro de esa carpeta, incluida la carpeta oculta `.claude`.
4. [ ] Guarda y sube:
   ```powershell
   git add .
   git commit -m "docs: plan maestro, guías de agentes y subagentes"
   git push
   ```

### Paso 0.5 · Instalar las skills
Dentro de la carpeta del proyecto, abre Claude Code:
```powershell
claude
```
La primera vez te pedirá iniciar sesión en el navegador. Luego escribe estos comandos **dentro de Claude Code**, uno por uno:

**Metodología: Superpowers**
```
/plugin install superpowers@claude-plugins-official
```

**Compose: guía y auditoría de código**
```
/plugin marketplace add hamen/compose_skill
/plugin install compose-agent@compose_skill
/plugin install jetpack-compose-audit@compose_skill
```

**Diseño: Material 3**
```
/plugin marketplace add hamen/material-3-skill
/plugin install material-3@material-3-skill
```

**Seguridad: Trail of Bits**
```
/plugin marketplace add trailofbits/skills
/plugin menu
```
En el menú, instala: `static-analysis`, `differential-review`, `insecure-defaults` y `c-review`.

**Skills oficiales de Android (Google).** Son solo archivos de texto, así que se copian directo del repositorio oficial (no hace falta la Android CLI; en Windows con *Smart App Control* activo, `android skills add` falla al cargar un `.dll`). Sal de Claude Code (`/exit`) y en PowerShell, dentro de la carpeta del proyecto:
```powershell
git clone --depth 1 https://github.com/android/skills.git "$env:TEMP\android-skills"
$skills = @(
  "jetpack-compose\adaptive",
  "jetpack-compose\theming\styles",
  "navigation\navigation-3",
  "security\android-intent-security",
  "security\android-permissions-security",
  "system\edge-to-edge",
  "testing\testing-setup",
  "performance\r8-analyzer",
  "build-system\agp\agp-9-upgrade",
  "devtools\android-cli"
)
New-Item -ItemType Directory -Force ".claude\skills" | Out-Null
foreach ($s in $skills) { Copy-Item -Recurse -Force "$env:TEMP\android-skills\$s" ".claude\skills\" }
Copy-Item -Force "$env:TEMP\android-skills\LICENSE.txt" ".claude\skills\ANDROID-SKILLS-LICENSE.txt"
Get-ChildItem ".claude\skills"
```
Debes ver 10 carpetas. Súbelas al repo (`git add .claude/skills` → `git commit -m "chore: android skills oficiales"` → `git push`).

### Paso 0.6 · Verificar
Vuelve a abrir `claude` y escribe `@agent-` (sin enviar): el autocompletado debe mostrar los 4 subagentes **estructura, infraestructura, seguridad, diseno**.
También puedes preguntar: *"¿Qué subagentes de proyecto tienes disponibles?"*

> ℹ️ En Claude Code 2.1.198 o más nuevo, el comando `/agents` aparece como **"(removed)"**: ya no abre un menú. Es normal y no significa que tus agentes se borraron.

Para revisar que los archivos no tengan errores de formato (en PowerShell):
```powershell
claude plugin validate .claude/agents
```

### ✅ Puerta de la Fase 0
- [ ] `claude --version` responde.
- [ ] `adb devices` muestra el Pixel.
- [ ] El repo está en GitHub con el kit.
- [ ] `@agent-` muestra los 4 agentes.
- [ ] Las skills están instaladas (`/plugin` las lista).

Cuando todo esté en verde, cambia en `CLAUDE.md` la línea de **Fase actual** a `Fase 1a · Proyecto base`.

---

## 🚀 Prompts por fase (copiar y pegar en Claude Code)

> **Cómo usarlos:** abre `claude` en la carpeta del proyecto y pega el prompt de la fase. El agente hará preguntas (brainstorming) y luego te mostrará un **plan**. **Léelo antes de aprobar.** Si algo no se entiende, pregunta; es tu derecho y tu responsabilidad.

### Fase 1a · Proyecto base
```
Estamos en la Fase 1a. Lee CLAUDE.md, docs/contexto-y-decisiones.md y docs/agentes/05-metodologia.md.
Usa Superpowers. Con el subagente infraestructura, crea el proyecto Android base (Gradle reproducible,
versiones fijas, verificación de dependencias, CI en GitHub Actions con lint y test). Con el subagente
estructura, crea los módulos iniciales de la sección 4 de docs/agentes/01-estructura.md (parte 1a).
Al final, que el subagente seguridad revise el manifest y la configuración de red.
Explícame cada herramienta nueva en una línea. Quiero instalar la app vacía en mi Pixel 7 para cerrar la puerta.
```
**Puerta 1a:** la app vacía se instala en tu Pixel y GitHub Actions sale en verde ✅.

### Fase 1b · Motor de traducción ⭐ (el paso más difícil)
```
Estamos en la Fase 1b, la prueba de fuego. Objetivo: traducir un párrafo con OPUS-MT tc-big (int8)
DENTRO de la app, sin internet, en menos de 2 segundos en mi Pixel 7.
Subagente infraestructura: compila CTranslate2 + SentencePiece + el puente JNI para arm64 en GitHub
Actions, y crea el script de conversión del modelo desde Helsinki-NLP (sección 2.2 y 2.4 de su guía).
Subagente estructura: el partidor de oraciones con TDD, el módulo :engine:opus y la pantalla de prueba
con el benchmark de los 25 textos (sección 4, parte 1b).
Subagente seguridad: revisa el puente JNI antes de fusionar.
Si CTranslate2 se complica más de una semana, prepárame una comparación con el plan B (ONNX Runtime).
```
**Lo que harás tú:** conectar el Pixel, correr el comando `adb push` que te den para copiar el modelo, y mirar el benchmark.
**Puerta 1b:** párrafo < 2 s y benchmark ≥ 15 palabras/s, en modo avión ✈️.

### Fase 2 · Gestor de modelos + motor Firefox
```
Estamos en la Fase 2. Sigue las tareas de Fase 2 de las guías 01, 02, 03 y 04.
Prioridad: descarga de modelos con catálogo firmado y verificación SHA-256, importación manual
desde archivo, motor Firefox de respaldo (reutilizando código GPL de Offline Translator con sus avisos),
EngineSelector por RAM, y las pantallas de Bienvenida e Idiomas.
Incluye la comparación de calidad OPUS beam 1 vs beam 4 con los 25 textos para que yo la evalúe.
```
**Lo que harás tú:** crear el repo `lector-bilingue-modelos` y generar la llave del catálogo con el comando que te den (la llave **privada** la guardas tú, con respaldo).

### Fase 3 · Lector EPUB + tocar y traducir
```
Estamos en la Fase 3. Integra Readium para leer EPUB, tocar un párrafo y ver su traducción,
con caché en Room y pretraducción de los párrafos siguientes. Subagente diseno: el lector con los
3 modos de traducción (sección 4 de su guía), empezando por bocetos en texto para mi aprobación.
Subagente seguridad: revisión del WebView y de la lectura de EPUB (sección 5.1), con EPUBs maliciosos de prueba.
```
**Lo que harás tú:** leer 10 minutos un libro real y decir qué modo de traducción prefieres.

### Fase 4 · Libro completo
```
Estamos en la Fase 4. Traducción del libro completo en segundo plano (Foreground Service, reanudable,
pausa por batería o calor) y exportación a EPUB bilingüe validado con EPUBCheck. Pantalla de progreso
del subagente diseno. Revisión del subagente seguridad.
```
**Lo que harás tú:** traducir un libro completo y anotar duración, temperatura y batería.

### Fase 5 · PDF y DOCX
```
Estamos en la Fase 5. PDF con texto digital en vista reflow (PDFium de Readium + PdfBox-Android) y DOCX.
Detecta PDFs escaneados y avisa. Revisión de seguridad de ZIP/XML/PDF.
```

### Fase 6 · Publicación
```
Estamos en la Fase 6. Prepara el lanzamiento: flavors fdroid y play, pantalla de licencias y atribuciones,
auditoría material-3 (meta ≥ 80/100) y jetpack-compose-audit (≥ 80/100), auditoría final de seguridad
con MASVS, build reproducible verificado, metadatos fastlane en es-419 y en-US, política de privacidad
y guía paso a paso para que YO cree el keystore, firme y envíe a F-Droid y Google Play.
```
**Lo que harás tú:** crear el keystore (con **2 respaldos offline**), firmar, registrar el paquete en la verificación de desarrolladores de Google y enviar a F-Droid y Play.

---

## 🔁 Rutina de cada sesión
1. `cd lector-bilingue` → `git pull` → `claude`
2. Pega el prompt de la fase (o di "continuemos con la tarea X del plan").
3. Responde preguntas y **aprueba el plan solo si lo entiendes**.
4. Revisa el resumen final y prueba en el Pixel lo que sea visible.
5. Aprueba la fusión del PR.

### Comandos útiles dentro de Claude Code
| Comando | Para qué |
|---|---|
| `@agent-seguridad revisa esta rama` | Pedir revisión de seguridad a mano |
| `@agent-diseno muéstrame bocetos de X` | Pedir propuestas de diseño |
| `@agent-` (sin enviar) | Ver los subagentes disponibles |
| `/plugin` | Ver las skills y plugins instalados |
| `/clear` | Empezar una conversación limpia (útil al cambiar de tarea) |

## ⚠️ Reglas de oro para ti
- **Nunca pegues contraseñas ni llaves privadas** en el chat de Claude Code.
- **No apruebes un plan que no entiendes.** Pide que te lo expliquen más simple.
- **No saltes puertas.** Si la Fase 1b no cumple la meta de velocidad, no tiene sentido construir el lector encima.
- **Guarda el keystore como si fuera la llave de tu casa:** si se pierde, nadie podrá actualizar la app nunca más.
- Si algo falla y no entiendes el error, pega el mensaje completo y pide: *"explícamelo en simple y propón cómo arreglarlo"*.

## 🔗 Fuentes de lo investigado
- Superpowers (metodología): https://github.com/obra/superpowers
- Comparativa de metodologías: https://aicodingpatterns.com/en/patterns/openspec-vs-spec-kit-vs-bmad/
- Android Skills oficiales de Google: https://github.com/android/skills
- Android CLI: https://android-developers.googleblog.com/2026/04/build-android-apps-3x-faster-using-any-agent.html
- Claude en Android Studio (BYOA): https://android-developers.googleblog.com/2026/09/build-your-way-use-any-ai-agent-in-android-studio.html
- Material 3 skill: https://github.com/hamen/material-3-skill
- Compose skill (audit + agent): https://github.com/hamen/compose_skill
- Trail of Bits skills: https://github.com/trailofbits/skills
- Instalación de Claude Code: https://code.claude.com/docs/en/setup
- Subagentes de Claude Code: https://code.claude.com/docs/en/sub-agents
