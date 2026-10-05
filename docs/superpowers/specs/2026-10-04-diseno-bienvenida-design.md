# Spec · Fase 2d · Sistema de diseño, Bienvenida, Inicio e Idiomas

- **Fecha:** 2026-10-04
- **Rama:** `feat/diseno-bienvenida`
- **Estado:** diseño aprobado por Juan en conversación (partes 1, 2 y 3, con bocetos en el navegador). Falta que revise esta spec.
- **Fase 2 partida en:** 2a calidad (hecha) → 2b modelos (hecha) → 2c motor Firefox + EngineSelector (hecha, PRs #11 y #12) → **2d diseño + Bienvenida/Idiomas**.
- **Agentes:**
  - `diseno`: sistema de diseño, pantallas y accesibilidad. Es el dueño de la interfaz.
  - `estructura`: navegación, ViewModels y ajustes guardados.
  - `infraestructura`: dependencias, fuentes e íconos, y CI.
  - `seguridad`: revisión de la dependencia nueva y de los ajustes guardados.

## 1. Objetivo

La app deja de ser una pantalla de prueba y tiene un recorrido real para un usuario nuevo:
- **Bienvenida (3 pasos):** qué hace la app, privacidad, y elegir el idioma y descargarlo.
- **Inicio provisional:** el estado de los idiomas y el acceso a Idiomas. La Biblioteca lo reemplaza en la Fase 3.
- **Idiomas:** pares, modelos por motor, descargar, borrar con confirmación, importar y elegir motor.

Todo se apoya en un **sistema de diseño** en `:core:ui` que usarán todas las fases siguientes.

**Puerta 2d** (con evidencia en el Pixel 7):
1. La Bienvenida se recorre completa. Se usa el interruptor de debug "Mostrar Bienvenida otra vez" en vez de reinstalar la app, para no borrar los modelos ni los textos privados.
2. Idiomas funciona de punta a punta:
   - borrar, con confirmación;
   - descargar, y volver a descargar con confirmación (ver nota en §4.3: en la 2d no hay botón para volver a descargar un modelo instalado);
   - importar `.zip`;
   - cambiar de motor, y la elección se recuerda tras cerrar la app.
3. Con **TalkBack** se completa la Bienvenida y se inicia una descarga sin mirar la pantalla.
4. Con **letra del sistema al 200 %** no se corta ningún texto ni botón.
5. **Auditoría Material 3** (skill `material-3`, modo auditoría) hecha y anotada. La meta de 80/100 es para publicar; aquí la cifra es la referencia.
6. **Capturas aprobadas por Juan:** cada pantalla en claro y oscuro, en teléfono y tableta, y con letra grande.
7. CI en verde. Revisiones de `diseno` y `seguridad` sin hallazgos altos abiertos. PR creado **como borrador** hasta cerrar la puerta.

### Fuera de alcance
- Biblioteca, Lector, Ajustes y el idioma inglés de la interfaz: Fase 3.
- Acerca de y licencias: fase 6. Los archivos de licencia de fuentes e íconos sí van ya en el repo.
- Color dinámico de Material You (colores del fondo de pantalla): apagado. Se podrá ofrecer en Ajustes más adelante.
- Elegir tema de página o fuente: los 4 temas de página se **definen** aquí y se **usan** en el Lector (Fase 3).

## 2. Decisiones tomadas (con Juan)

| Tema | Decisión |
|---|---|
| Pantalla de prueba del motor | Solo en la versión **debug**, dentro de un menú "Desarrollador". La versión de la tienda no la incluye |
| Idioma de la interfaz | Solo **español latino** en la 2d. Todos los textos en `strings.xml`, listos para el inglés en la Fase 3 |
| Pantalla tras la Bienvenida | **Inicio provisional** con el estado de los idiomas, el botón "Idiomas" y la tarjeta "Tu biblioteca llega pronto" |
| Paleta | **Tinta y papel:** azul tinta `#2E4A7D` sobre crema `#FBF8F1` en claro; `#A9C1F0` sobre `#121418` en oscuro |
| Fuentes | **Inter** (interfaz) y **Literata** (lectura), las dos con licencia OFL e incluidas en la app |
| Íconos | **Material Symbols Rounded** (Apache-2.0). Solo los que se usan, copiados como dibujos vectoriales. **Cero emojis en la interfaz** |
| Navegación | **Navigation 3** (`androidx.navigation3`, Apache-2.0): cada pantalla es un dato simple y el historial de "Atrás" es una lista |
| Nombres de los motores para el usuario | **Calidad** (ícono `workspace_premium`) = OPUS; **Rápido** (ícono `bolt`) = Firefox. "OPUS" y "Firefox" solo aparecen en el menú de desarrollador |
| Ajustes guardados | Un archivo de ajustes del propio teléfono (`SharedPreferences`), sin dependencias nuevas: Bienvenida vista (sí/no) y motor elegido (Automático/Calidad/Rápido) |

## 3. Módulo `:core:ui`

Es una biblioteca Android que no depende de `:models` ni de los motores.

### 3.1 Colores

**Interfaz:** esquemas de color Material 3, claro y oscuro, a partir de la paleta Tinta y papel. Siguen el modo del sistema.

**Temas de página del lector** (`ReaderTheme`): cada uno define fondo, texto, traducción y acento.

| Tema | Fondo | Texto | Traducción | Contraste texto / traducción |
|---|---|---|---|---|
| Claro | `#FBF8F1` | `#1C1B1F` | `#2E4A7D` | 16,2 / 8,3 |
| Sepia | `#F4ECD8` | `#3B2F1E` | `#6B4A1F` | 11,1 / 6,8 |
| Oscuro | `#121418` | `#E6E3DD` | `#A9C1F0` | 14,4 / 10,2 |
| Negro (AMOLED) | `#000000` | `#D9D9D9` | `#9DB4E0` | 14,9 / 10,0 |

- La traducción va en cursiva y con su color propio, así que no depende solo del color.
- Una **prueba automática** calcula el contraste WCAG de cada pareja texto/fondo, en la interfaz y en los 4 temas, incluida la traducción sobre su fondo. Falla si alguno baja de 4,5:1 (texto normal) o de 3:1 (elementos grandes o íconos).

### 3.2 Tipografía

- **Inter** se usa en toda la escala de Material 3. **Literata** se reserva para el texto de los libros (la usa el Lector en la Fase 3) y para los títulos grandes de la Bienvenida.
- Las fuentes van en `res/font/`, con sus archivos `OFL.txt`. Se usan las versiones variables o solo los pesos necesarios (400, 600, 700). El plan mide cuánto crece el APK.
- Todos los tamaños en `sp`, para respetar la letra del sistema.

### 3.3 Formas y espaciados

- Esquinas: 12 dp en tarjetas, 20 dp en botones y 28 dp en diálogos.
- Espaciados en múltiplos de 4 dp, como constantes.
- Área táctil mínima de 48 dp.

### 3.4 Componentes

- **`PrivacyBadge`:** candado y el texto "Traducido en tu teléfono".
- **`ModelRow`:** ícono del motor, nombre (Calidad o Rápido), tamaño y estado. El estado puede ser instalado, en uso, no instalado o descargando con barra y porcentaje. Lleva un botón de acción: Descargar, Cancelar o Borrar.
- **`ConfirmDialog`:** título, explicación con el tamaño y dos botones. El de confirmar nombra la acción, por ejemplo "Borrar".
- **`DownloadProgress`:** barra determinada o indeterminada, con texto y descripción para TalkBack.
- **Íconos:** `menu_book`, `lock`, `translate`, `check_circle`, `check_box`, `check_box_outline_blank`, `download`, `folder_open`, `wifi`, `workspace_premium`, `bolt`, `tune`, `delete`, `library_books`, `more_vert`, `close` y los que el plan necesite. Están en `core/ui/src/main/res/drawable/`, con el archivo de licencia Apache-2.0.
  - Un ícono decorativo no lleva descripción.
  - Un ícono con significado lleva descripción en español.

## 4. Pantallas (en `:app`)

### 4.1 Bienvenida (3 pasos, con indicador de paso)

1. **"Lee en inglés con ayuda"** (ícono `menu_book`): qué hace la app, sin cuentas ni anuncios. Botón: **Siguiente**.
2. **"Todo queda en tu teléfono"** (ícono `lock`): la traducción ocurre en el teléfono, y los libros y lo que se lee nunca salen de él. Internet solo se usa para descargar los idiomas. Botón: **Siguiente**.
3. **"Elige tu idioma":**
   - **Pares:** inglés → español marcado y español → inglés opcional.
   - **Motor recomendado:** cada par muestra el motor que elige `EngineSelector` (Automático) y su tamaño.
   - **Aviso de Wi-Fi.**
   - **Botones:**
     - **Descargar (N MB):** encola las descargas elegidas y va al Inicio. Pide el permiso de notificaciones antes, igual que hoy.
     - **Importar desde archivo (.zip)**.
     - **Más tarde.**
   - **Sin catálogo y sin red:** mensaje claro, sin botón Descargar. Importar sigue disponible.

- **Cuándo se marca "vista":** la Bienvenida queda vista al salir del paso 3, por cualquiera de los tres botones.
- **Si se cierra antes:** al abrir de nuevo, empieza en el paso 1.

### 4.2 Inicio (provisional)

- **Encabezado:** título "Hola".
- **Menú de tres puntos:** en debug incluye "Desarrollador".
- **Una tarjeta por par instalado o descargándose:**
  - instalado → "Listo para traducir · Calidad/Rápido";
  - descargando → barra de avance y botón Cancelar.
- **Sin ningún idioma:** tarjeta "Aún no tienes idiomas" con el botón **Descargar idiomas**, que lleva a Idiomas.
- **Botón Idiomas.**
- **Tarjeta "Tu biblioteca llega pronto".**
- **`PrivacyBadge` al pie.**

### 4.3 Idiomas

- **Una tarjeta por par del catálogo** (inglés → español, español → inglés), con una `ModelRow` por motor (Calidad y Rápido).
- **Motor:** selector **Automático / Calidad / Rápido**.
  - Debajo, una línea dice qué elige Automático en este teléfono y por qué, por ejemplo "Automático usa Calidad: tu teléfono tiene 8 GB".
  - La elección se guarda.
- **Importar desde archivo (.zip)**.
- **Borrar** y **volver a descargar** siempre piden confirmación (`ConfirmDialog`).
  - *Nota de cierre (2026-10-05):* en la 2d una fila instalada solo ofrece **Borrar**, así que el diálogo de volver a descargar existe (reglas y textos) pero no hay botón que lo abra. El botón "Descargar otra vez" (modelo dañado o actualizado) queda para la Fase 3. Hoy se reemplaza un modelo borrándolo (con confirmación) y descargándolo de nuevo.
- **Errores:** los mensajes fijos de la 2b y la 2c, redactados en lenguaje sencillo y sin detalles internos.
- **Si el motor elegido falla al cargar:** se **ofrece** usar el otro. Nunca cambia solo.

### 4.4 Desarrollador (solo debug)

- Vive en `app/src/debug/`. La versión release no compila ese código.
- Contiene:
  - la pantalla de prueba actual (`ui/enginetest`), que se mueve a debug sin perder funciones (benchmark, traducir, nombres técnicos);
  - un interruptor **"Mostrar Bienvenida otra vez"**.
- Una prueba comprueba que la versión release no contiene la clase de la pantalla de prueba.

## 5. Navegación y estado

- **Navigation 3:** las pantallas son `Welcome(step)`, `Home`, `Languages` y `Developer` (esta solo en debug). Un `NavDisplay` en `MainActivity` muestra la última del historial.
- **Pantalla de arranque:** Bienvenida si no está vista; si no, Inicio.
- **El botón Atrás:**
  - en la Bienvenida, retrocede un paso;
  - en el paso 1, cierra la app.
- **Un ViewModel por pantalla.** La lógica que decide qué mostrar va en funciones puras que se prueban sin teléfono, como `ScreenRules` en la 2c.
- **El motor elegido** sale de los ajustes guardados y se pasa a `EngineSelector` como el valor forzado.
- **Un solo motor cargado a la vez:** sigue la regla de la 2c. En la 2d ninguna pantalla nueva traduce; solo el menú de desarrollador carga motores.

## 6. Pendientes de la 2b y la 2c que se cierran aquí

- **Confirmar** antes de borrar y antes de volver a descargar un modelo.
- **Tocar Descargar sin conexión:** responde rápido con "Buscando…" y luego usa el catálogo guardado, sin esperar todo el tiempo de la red. Tope de espera del refresco del catálogo iniciado desde la interfaz: **5 s**. El trabajo de descarga en segundo plano conserva sus tiempos actuales.
- **Estado "cargando" atascado:** los pasos previos de cambiar de par van dentro del mismo manejo de errores.
- **TalkBack:** el selector de motor y el de par usan el rol de "opción" (radio). Lo comprueba una prueba de semántica de Compose.
- **Sin emojis en los textos de error ni de veredicto** (ya hecho en la 2c para el benchmark; se revisa en toda la app).

## 7. Accesibilidad (obligatorio, de `docs/agentes/04-diseno.md` §5)

- **Contraste WCAG AA:** lo verifica la prueba automática de §3.1.
- **Áreas táctiles:** mínimo de 48 dp.
- **Íconos:** todos los que tienen significado llevan descripción en español.
- **Regiones vivas:** para el avance de descarga, los errores y el estado del modelo.
- **Sin depender del color:** cada estado tiene también texto.
- **"Reducir movimiento":** las animaciones lo respetan (el avance entre pasos de la Bienvenida no anima si está activo).
- **Letra al 200 %:** se comprueba con Previews y en el Pixel.

## 8. Pruebas

- **Unitarias (JVM):**
  - reglas de cada pantalla: qué tarjeta mostrar, texto del motor y casos de error;
  - ajustes guardados: Bienvenida vista y motor elegido;
  - contraste de todos los colores (§3.1).
- **Compose UI, instrumentadas en el Pixel con `am instrument`:**
  - recorrido de la Bienvenida;
  - diálogo de borrar: confirmar y cancelar;
  - semántica de los selectores.
  - Nunca `connectedAndroidTest`.
- **Previews:** cada pantalla en claro y oscuro, teléfono y tableta, y con letra al 200 %. Las capturas se comparten con Juan.
- **Release:** una prueba o una comprobación del CI confirma que la pantalla de desarrollador no está en el APK release.

## 9. Riesgos

| Riesgo | Mitigación |
|---|---|
| Navigation 3 es reciente | Usar la versión estable más reciente a la fecha del plan, fijada en el catálogo de versiones con su huella, y la skill oficial `navigation-3`. La revisa `seguridad` como dependencia |
| El APK crece por fuentes e íconos | Solo los pesos o la versión variable necesarios y solo los íconos usados. Se mide el tamaño antes y después |
| Mover la pantalla de prueba a debug rompe las pruebas instrumentadas que la usan | Las pruebas de motores siguen en `androidTest` sin depender de la pantalla. Se corre el conjunto en el Pixel |
| Reinstalar para ver la Bienvenida borraría los modelos y los textos de Juan | Interruptor de debug "Mostrar Bienvenida otra vez". Nunca desinstalar ni `pm clear` |
| Contraste del tema sepia o de la traducción | Prueba automática del contraste y ajuste de los tonos antes de cerrar |

## 10. Lo que hace Juan

1. Revisar y aprobar esta spec y luego el plan.
2. Aprobar las capturas de las Previews (paso de revisión de `diseno`).
3. En la puerta:
   - tener el Pixel conectado y desbloqueado;
   - probar TalkBack (o dejar que lo maneje el controlador con `adb`);
   - decir si la experiencia le parece clara.
4. Crear el PR **como borrador** y fusionarlo solo cuando el controlador confirme que el CI está en verde.
