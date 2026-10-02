# 05 · Metodología de desarrollo

> **Para quién:** para Juan y para todos los agentes. Define *cómo* se trabaja.
> **Idea central:** nada de "programar a ciegas". Cada cambio sigue el ciclo **pensar → planear → probar → construir → revisar**.

## 1. La metodología elegida y por qué

Investigamos las opciones más usadas hoy para desarrollar con agentes de IA:

| Opción | Qué es | Para este proyecto |
|---|---|---|
| **Superpowers** (obra/superpowers) | Conjunto de *skills* (instrucciones empaquetadas) que obliga al agente a seguir un proceso de ingeniería: lluvia de ideas → plan → TDD → revisión | ✅ **Elegida como base** |
| **OpenSpec** | Especificaciones livianas en Markdown dentro del repo | 🟡 Opcional. Nuestros documentos en `docs/` ya cumplen ese papel |
| **GitHub Spec Kit** | Flujo fijo de especificación → plan → tareas | 🟡 Válido, pero más rígido para una sola persona |
| **BMAD Method** | Muchos agentes con roles (PM, arquitecto, QA...) y mucha ceremonia | ❌ Excesivo para un desarrollador solo; gasta muchos tokens |

**Por qué Superpowers:**
- Es el marco más adoptado (cerca de 293 mil estrellas en GitHub, licencia MIT).
- Está hecho para Claude Code y se instala con un comando.
- Impone justo lo que más falla al programar con IA: **pensar antes de escribir código, escribir pruebas primero y revisar antes de dar algo por terminado.**
- Las especificaciones ya las tenemos: son los documentos de `docs/`.

### Instalación (Juan, una sola vez)
Dentro de Claude Code:
```
/plugin install superpowers@claude-plugins-official
```
Si ese marketplace no está disponible:
```
/plugin marketplace add obra/superpowers-marketplace
/plugin install superpowers@superpowers-marketplace
```

## 2. El ciclo de trabajo de cada tarea

Analogía: es como construir una casa. Primero el arquitecto y el dueño se ponen de acuerdo (lluvia de ideas), luego los planos (plan), luego cada muro se construye y se inspecciona (TDD y revisión), y al final se entrega la obra (cierre).

| Paso | Skill de Superpowers | Qué pasa | ¿Participa Juan? |
|---|---|---|---|
| 1 | `brainstorming` | El agente hace preguntas hasta entender bien la tarea | ✅ Respondes preguntas |
| 2 | `using-git-worktrees` | Crea una copia aislada del proyecto en una rama nueva | No |
| 3 | `writing-plans` | Divide el trabajo en tareas de 2-5 minutos con archivos exactos | ✅ **Apruebas el plan** |
| 4 | `subagent-driven-development` | Un subagente nuevo hace cada tarea; otro la revisa | No |
| 5 | `test-driven-development` | Primero la prueba que falla (ROJO), luego el código que la pasa (VERDE), luego limpieza | No |
| 6 | `requesting-code-review` | Se compara lo hecho contra el plan | No |
| 7 | `finishing-a-development-branch` | Fusionar, abrir PR o descartar | ✅ Decides fusionar |

**Glosario rápido**
- **Rama (branch):** una línea paralela del código para trabajar sin romper lo principal. Como un borrador.
- **Worktree:** una carpeta separada con esa rama, para que dos trabajos no se pisen.
- **TDD (desarrollo guiado por pruebas):** escribir primero la prueba que dice "esto debe pasar", y luego el código.
- **PR (pull request):** una solicitud para unir una rama a la principal, con revisión.

## 3. Fases del proyecto y "puertas"

Una **puerta** (*gate*) es un punto de control: **no se pasa a la siguiente fase hasta que Juan verifica la lista y aprueba.**

| Fase | Objetivo | Puerta: se aprueba si... |
|---|---|---|
| **0. Preparación** (solo Juan) | Herramientas, repo, skills | `claude` corre, el repo existe, el Pixel se detecta por USB |
| **1a. Proyecto base** | Proyecto Android que compila, CI, reglas de seguridad base | La app vacía se instala en el Pixel; CI en verde |
| **1b. Motor** | App mínima que traduce un párrafo con OPUS **dentro de la app** | En el Pixel 7, sin internet, un párrafo se traduce en **< 2 s** |
| **2. Gestor de modelos + motor Firefox** | Descargar, verificar e importar modelos; respaldo Firefox | Descarga con verificación SHA-256; Firefox traduce en un celular de gama baja |
| **3. Lector EPUB + tocar y traducir** | Abrir EPUB, tocar párrafo, ver traducción, caché | Un libro real se lee cómodo; volver a un párrafo es instantáneo |
| **4. Libro completo** | Traducción en segundo plano y exportar EPUB bilingüe | Un libro de ~80 mil palabras se traduce sin cerrarse; el EPUB exportado abre en otro lector |
| **5. PDF y DOCX** | PDF con texto digital en reflow; DOCX | Un PDF técnico real se lee y traduce |
| **6. Publicación** | Pulido, accesibilidad, build reproducible, F-Droid y Play | Checklists de seguridad y diseño en verde; APK reproducible verificado |

Cada documento de agente (`01` a `04`) dice qué le toca a ese agente en cada fase.

## 4. Quién hace qué

| Rol | Quién | Responsabilidad |
|---|---|---|
| **Dueño del producto** | Juan | Decide qué se construye, aprueba planes y puertas, prueba en el teléfono |
| **Orquestador** | La sesión principal de Claude Code | Lee los documentos, reparte tareas a los subagentes, integra |
| **Agente de estructura** | Subagente `estructura` | Arquitectura y código Kotlin de la app |
| **Agente de infraestructura** | Subagente `infraestructura` | Compilación, código nativo, modelos, CI, publicación |
| **Agente de seguridad** | Subagente `seguridad` | Revisa todo cambio sensible; solo lectura por defecto |
| **Agente de diseño** | Subagente `diseno` | Interfaz, experiencia de lectura, accesibilidad |

Los subagentes están definidos en `.claude/agents/`. Para invocarlos directamente en Claude Code:
```
@agent-seguridad revisa los cambios de esta rama
```

## 5. Reglas comunes para todos los agentes

1. **Leer antes de actuar:** `CLAUDE.md`, `docs/contexto-y-decisiones.md` y el documento propio del agente.
2. **Explicar en simple:** Juan es nuevo en Kotlin y Android. Cada concepto nuevo se define en una línea, con una analogía si ayuda.
3. **Una tarea, una rama, un PR.** Nada se sube directo a `main`.
4. **Pruebas primero** (TDD) para toda lógica. La interfaz se valida con *Compose Previews* y capturas.
5. **Verificar antes de decir "listo":** compilar, correr pruebas y, si aplica, probar en el Pixel. Nunca afirmar que algo funciona sin evidencia.
6. **Sin dependencias propietarias** (rompen F-Droid). Toda dependencia nueva requiere: licencia, fecha del último release y aprobación del agente de seguridad.
7. **Privacidad:** el texto del usuario nunca sale del teléfono. Ni analítica, ni registros remotos de fallos.
8. **Decisiones nuevas:** si un agente cambia una decisión de `contexto-y-decisiones.md`, la propone primero y, una vez aprobada, actualiza ese documento.

## 6. Convenciones

### Git
- Rama principal: `main` (siempre compila).
- Ramas: `feat/…`, `fix/…`, `chore/…`, `docs/…`.
- Mensajes con **Conventional Commits**: `feat(reader): tocar párrafo muestra traducción`.

### Definición de "terminado" (Definition of Done)
Una tarea está terminada solo si:
- [ ] Las pruebas nuevas y las existentes pasan.
- [ ] `./gradlew lint` sin errores nuevos.
- [ ] Revisión del agente de seguridad si toca: red, archivos, código nativo, permisos o dependencias.
- [ ] Revisión del agente de diseño si toca pantallas.
- [ ] La documentación afectada está actualizada.
- [ ] Juan lo vio funcionar (captura, video o prueba en el Pixel) cuando es visible para el usuario.

### Pruebas
| Tipo | Qué prueba | Herramienta |
|---|---|---|
| Unitarias | Lógica pura (partir oraciones, caché, verificación de hashes) | JUnit + kotlin.test |
| Instrumentadas | Lo que necesita Android (archivos, base de datos) | AndroidX Test en el Pixel |
| UI | Pantallas | Compose UI Test + capturas |
| **Calidad de traducción** | Que una actualización no empeore la traducción | Set fijo de 25 frases; comparar contra salidas aprobadas |
| **Rendimiento** | Velocidad del motor | Benchmark en el Pixel: **≥ 15 palabras/s** con beam 1 |

## 7. Cómo arranca cada sesión de trabajo (receta para Juan)

1. Abre la terminal en la carpeta del proyecto y escribe `claude`.
2. Di en qué fase estás y pega el *prompt* de esa fase (están en el `README.md`).
3. Responde las preguntas de la lluvia de ideas.
4. **Lee el plan** antes de aprobarlo. Si algo no se entiende, pregunta: es tu derecho y tu obligación.
5. Deja trabajar. Interviene si el agente pide decisiones.
6. Al final, revisa el resumen y prueba en el teléfono lo que sea visible.
7. Aprueba la fusión del PR.

## Fuentes
- Superpowers: https://github.com/obra/superpowers
- Comparativa OpenSpec / Spec Kit / BMAD: https://aicodingpatterns.com/en/patterns/openspec-vs-spec-kit-vs-bmad/
- Subagentes de Claude Code: https://code.claude.com/docs/en/sub-agents
