# 04 · Agente de DISEÑO (interfaz, experiencia de lectura y accesibilidad)

> **Misión:** que leer con la app se sienta mejor que leer un PDF con el traductor del teléfono. Una interfaz tranquila, legible y en español, que no estorbe la lectura.
> **Lee primero:** `CLAUDE.md`, `docs/contexto-y-decisiones.md`, `docs/agentes/05-metodologia.md`.
> **Entrega a:** *estructura*, en forma de componentes Compose dentro de `:core:ui` y pantallas en cada `:feature:*`.

## 1. Skills que debe usar este agente (investigadas en octubre 2026)

| Skill | Por qué es la mejor opción | Instalación (la hace Juan) |
|---|---|---|
| **material-3** (`hamen/material-3-skill`, Ivan Morgillo) | Hecha para **Jetpack Compose** como plataforma principal. Cubre 30+ componentes, *design tokens* (los valores del diseño: colores, tamaños, tipografía), temas, diseño adaptable y un **modo auditoría** que puntúa la app en 10 categorías de cumplimiento Material 3. ~1,4 mil estrellas, MIT, versión 1.1.1 (jun 2026) | `npx --yes skills add hamen/material-3-skill --skill material-3 -y` o en Claude Code: `/plugin marketplace add hamen/material-3-skill` y `/plugin install material-3@material-3-skill` |
| **jetpack-compose** y **edge-to-edge** (oficiales de Google, `android/skills`) | Las prácticas oficiales actuales de Compose y de pantallas de borde a borde | `android skills add` |
| **compose-agent** (`hamen/compose_skill`) | Revisa el código de cada pantalla mientras se escribe (rendimiento, estado) | Ver `01-estructura.md` |

**¿Por qué no otras?** Las skills de diseño más famosas (por ejemplo, `frontend-design` de Anthropic) están pensadas para **web** (HTML/CSS/React). Esta app es Android nativo con Compose; las de arriba hablan ese idioma.

## 2. Principios de diseño

1. **El texto es el protagonista.** Nada de adornos alrededor del libro. Los controles aparecen al tocar y se esconden solos.
2. **La traducción ayuda, no reemplaza.** El original siempre se puede ver; la traducción se distingue sin gritar (color secundario, tipografía ligeramente distinta).
3. **Privacidad visible.** Un distintivo pequeño y honesto: "🔒 Traducido en tu teléfono". Nunca exagerar.
4. **Velocidad percibida.** Si algo tarda más de 300 ms, mostrar un esqueleto de carga; nunca una pantalla congelada.
5. **Español latino primero** (`es-419`), inglés como segundo idioma de la interfaz. Lenguaje cercano, sin tecnicismos ("modelo de idioma" en vez de "checkpoint int8").
6. **Accesible por defecto** (sección 5).

## 3. Pantallas

| Pantalla | Qué contiene | Fase |
|---|---|---|
| **Prueba de motor** (temporal) | Caja de texto, botón Traducir, resultado, tiempo en ms | 1 |
| **Bienvenida** (3 pasos) | Qué hace la app → privacidad → elegir idiomas y descargar el modelo (con tamaño y aviso de WiFi) | 2 |
| **Idiomas** | Pares instalados y disponibles, tamaño, motor (⚡ rápido / 📦 calidad), borrar, importar desde archivo | 2 |
| **Biblioteca** | Portadas en cuadrícula, progreso de lectura y de traducción, botón "Abrir libro" | 3 |
| **Lector** ⭐ | La pantalla más importante (ver sección 4) | 3 |
| **Traducir libro completo** | Estimado de tiempo, progreso, pausar, exportar EPUB bilingüe | 4 |
| **Ajustes** | Tema, tipografía, modo de traducción, velocidad vs calidad, idioma de la interfaz | 3 |
| **Acerca de y licencias** | Versión, donar, código fuente, **atribuciones obligatorias** (OPUS CC-BY, Mozilla MPL, Readium BSD...) | 6 |

## 4. El lector (detalle)

### Modos de traducción (el usuario elige; probar los tres con Juan)
| Modo | Cómo se ve | Ideal para |
|---|---|---|
| **Tocar para traducir** *(por defecto)* | Tocas un párrafo y su traducción aparece debajo, con un fondo suave. Tocar otra vez la oculta | Aprender inglés |
| **Intercalado** | Cada párrafo original seguido de su traducción, todo el capítulo | Lectura bilingüe continua |
| **Solo traducción** | Se ve solo el texto traducido; mantener presionado muestra el original | Leer un libro que no existe en tu idioma |

### Ajustes de lectura
- Tipografía: 2-3 fuentes libres (una serif para leer, una sans para la interfaz), todas con licencia OFL.
- Tamaño de letra, interlineado y márgenes ajustables.
- Temas: **claro, sepia, oscuro, negro puro (AMOLED)**. Color dinámico de Material You opcional en la interfaz, **no** en la página.
- Respetar el tamaño de letra del sistema.

### Estados que hay que diseñar (no solo el caso feliz)
- Traduciendo (esqueleto con la forma del párrafo).
- Modelo no descargado (llamado a descargar, con tamaño).
- Error del motor (mensaje humano + "Reintentar").
- PDF escaneado sin texto (explicar que aún no se soporta).
- Batería baja o teléfono caliente durante la traducción completa (pausa explicada).

## 5. Accesibilidad (obligatorio)
- [ ] Contraste mínimo **WCAG AA** (4,5:1 en texto normal) en los 4 temas, también en el fondo de la traducción.
- [ ] Áreas táctiles de **48 dp** como mínimo.
- [ ] Todo icono con `contentDescription` en español.
- [ ] Probado con **TalkBack** (el lector de pantalla de Android): se puede abrir un libro y traducir un párrafo sin ver.
- [ ] Sin información transmitida solo por color.
- [ ] Animaciones que respetan "reducir movimiento".
- [ ] Funciona con letra del sistema al 200 %.

## 6. Proceso de trabajo con Juan
1. **Boceto en texto primero:** describir la pantalla en una lista (qué hay arriba, en medio, abajo) y pedir aprobación. Es barato cambiar una lista.
2. **Compose Previews:** dibujar la pantalla con datos de ejemplo, en los 4 temas y en dos tamaños (teléfono y tableta). Generar capturas y enseñárselas a Juan.
3. **Prueba en el Pixel** con un libro real.
4. **Auditoría** con el modo audit de la skill `material-3` antes de cerrar cada fase. Meta: **≥ 80/100** al publicar.

## 7. Tareas por fase

| Fase | Tareas |
|---|---|
| 1 | Pantalla de prueba de motor (sencilla, sin pulir) |
| 2 | Sistema de diseño en `:core:ui` (colores, tipografía, formas, 4 temas); Bienvenida e Idiomas |
| 3 | Biblioteca, **Lector con los 3 modos**, Ajustes de lectura |
| 4 | Pantalla de traducción completa, notificación de progreso, exportar |
| 5 | Vista reflow de PDF y DOCX |
| 6 | Auditoría MD3 ≥ 80, accesibilidad completa, capturas para F-Droid y Play, ícono de la app |

## 8. Qué le pides a Juan
- Aprobar los bocetos en texto y las capturas de cada pantalla.
- Elegir entre 2-3 propuestas de paleta de color e ícono.
- Probar los 3 modos de lectura con un libro real y decir cuál prefiere por defecto.
- Si es posible, pedirle a 2-3 personas (ideal: alguien aprendiendo inglés) que prueben la app y anotar dónde se confunden.

## Fuentes
- Material 3 skill: https://github.com/hamen/material-3-skill
- Compose skill (audit + agent): https://github.com/hamen/compose_skill
- Android Skills oficiales: https://github.com/android/skills
