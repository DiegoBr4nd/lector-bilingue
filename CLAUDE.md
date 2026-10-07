# CLAUDE.md · Memoria del proyecto lector-bilingue

Claude Code lee este archivo al empezar cada sesión. Aquí van las reglas que **siempre** aplican.

## Qué es
App Android de código abierto (GPL-3.0): **lector bilingüe offline**. Abre EPUB/PDF/DOCX, traduce párrafos al tocarlos y libros completos en segundo plano. **Todo en el teléfono; ningún texto del usuario sale del dispositivo.**
Detalles y evidencia: `docs/contexto-y-decisiones.md`.

## Documentos de referencia
- `00-PLAN-MAESTRO.md`: guía de Juan (pasos, prompts por fase, puertas)
- `docs/contexto-y-decisiones.md`: qué y por qué (motores, pruebas, licencias)
- `docs/agentes/05-metodologia.md`: cómo se trabaja (Superpowers, fases, puertas, Definition of Done)
- `docs/agentes/01-estructura.md`: arquitectura y código
- `docs/agentes/02-infraestructura.md`: compilación, nativo, modelos, publicación
- `docs/agentes/03-seguridad.md`: seguridad y privacidad
- `docs/agentes/04-diseno.md`: interfaz y accesibilidad

## Fase actual
**Fase 3 · Lector EPUB + tocar y traducir** (2a calidad, 2b gestor de modelos, 2c motor Firefox + EngineSelector, 2d diseño + Bienvenida e Idiomas y **3a abrir y leer**: hechas; sigue **3b tocar y traducir**) ← Juan actualiza esta línea al pasar cada puerta.

## Reglas siempre vigentes
1. Responder a Juan **en español**, en frases cortas y con viñetas. Juan es nuevo en Kotlin/Android: definir cada término nuevo en una línea, con una analogía si ayuda.
2. Seguir el ciclo de Superpowers: brainstorming → plan aprobado por Juan → TDD → revisión → cierre.
3. Una tarea = una rama = un PR. Nunca trabajar directo en `main`.
4. **Prohibido:** Firebase, Play Services (salvo Billing en el flavor `play`), AdMob, Crashlytics, analítica, MuPDF, NLLB, cualquier dependencia no libre.
5. Nunca registrar (log) ni enviar por red el texto de los libros o sus traducciones.
6. Motor: máximo 4 hilos por defecto (medimos que 8 hilos es 4 veces más lento en el Pixel 7).
7. Nunca afirmar que algo funciona sin compilar, correr pruebas y mostrar la evidencia.
8. Cambios en red, archivos, código nativo, permisos o dependencias → revisión del subagente `seguridad`.
9. Cambios en pantallas → revisión del subagente `diseno`.
10. Llaves privadas (keystore, llave del catálogo): solo las maneja Juan. Darle comandos exactos, nunca generarlas ni guardarlas por él.

## Comandos útiles
- Compilar: `./gradlew assembleFdroidDebug`
- Pruebas: `./gradlew test`
- Lint: `./gradlew lint`
- Instalar en el teléfono: `./gradlew installFdroidDebug` (Pixel conectado por USB con depuración activada)

(El agente de infraestructura actualiza esta sección cuando cambien.)

## Dispositivo de referencia
Google Pixel 7 (8 GB RAM). Meta del motor: **≥ 15 palabras/s** y **< 2 s por párrafo** con beam 1.
