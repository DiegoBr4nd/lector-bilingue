# Catálogo firmado de modelos

Esta guía es para Juan. Explica cómo publicar un modelo y firmar el catálogo.
Las llaves privadas las maneja solo Juan. Ningún agente las crea ni las guarda.

## Cómo funciona (en corto)
- **Catálogo** (`catalog.json`): una lista de los modelos que la app puede descargar. Dice nombre, tamaño, huella SHA-256 y dirección de cada archivo.
- **Firma** (`catalog.json.minisig`): un sello digital del catálogo. La app solo acepta catálogos con una firma válida. Es como el sello de lacre de una carta: si alguien cambia el catálogo, el sello ya no coincide.
- **minisign**: programa pequeño que crea y revisa esas firmas. Usa dos archivos de llave:
  - **llave privada** (`minisign.key`): firma. Es secreta. Quien la tenga puede publicar catálogos falsos.
  - **llave pública** (`minisign.pub`): revisa. Es pública. Va dentro de la app.
- **Release** de GitHub: una página del repo donde se suben archivos grandes. Cada release tiene una **etiqueta** (nombre corto, como `opus-en-es-v1`).
- **SHA-256**: una huella de 64 letras y números de un archivo. Si el archivo cambia un solo byte, la huella cambia.

Repo de los modelos: `DiegoBr4nd/lector-bilingue-modelos`.

## 1. Instalar minisign (una sola vez)
PowerShell o Git Bash:
```
winget install jedisct1.minisign
```
Cierra y abre la terminal. Comprueba:
```
minisign -v
```

## 2. Crear tu llave (una sola vez, hazlo tú)
Primero decide dónde guardar la llave privada. **Nunca dentro del repo** ni en una carpeta sincronizada (OneDrive, Drive).
Ejemplo: una carpeta tuya fuera de `Documents\lector-bilingue`, como `C:\Users\JUAN\llaves`.

PowerShell:
```
mkdir C:\Users\JUAN\llaves
cd C:\Users\JUAN\llaves
minisign -G -p minisign.pub -s C:\Users\JUAN\llaves\minisign.key
```
Git Bash:
```
mkdir -p /c/Users/JUAN/llaves
cd /c/Users/JUAN/llaves
minisign -G -p minisign.pub -s /c/Users/JUAN/llaves/minisign.key
```
- Te pide una **contraseña**. Elígela larga y guárdala en tu gestor de contraseñas.
- Guarda **dos copias offline** de `minisign.key` (por ejemplo, dos memorias USB en lugares distintos). Si la pierdes, no puedes volver a firmar con esa llave.
- `minisign.pub` se puede compartir. Avísale al agente de infraestructura cuando la tengas: él la incrusta en la app (Tarea 12). No le pases nunca la privada.
- Para verla: `type minisign.pub` (PowerShell) o `cat minisign.pub` (Git Bash).

## 3. Crear el repo de modelos (una sola vez)
En github.com, con tu cuenta:
1. Nuevo repositorio: `lector-bilingue-modelos`, **público**.
2. Marca "Add a README" y crea el archivo.
3. Añade un archivo `LICENSE` con la licencia **CC-BY-4.0** (la del modelo OPUS-MT). En el README escribe de dónde viene el modelo: Helsinki-NLP / OPUS-MT, Tiedemann et al.

## 4. Obtener los archivos del modelo
El modelo se convierte en GitHub Actions (no en tu PC).
1. En el repo `lector-bilingue`, pestaña **Actions**, workflow **model**, botón **Run workflow** (o abre la última ejecución verde).
2. Abajo, en **Artifacts**, descarga **`modelo-en-es`** (un `.zip`).
3. Descomprímelo en una carpeta de trabajo, por ejemplo `C:\Users\JUAN\trabajo-modelo`. Dentro hay:
   - `en-es.tar.zst`: todos los archivos del modelo, empaquetados.
   - `en-es/SHA256SUMS`: la lista de huellas.
   - `reference-outputs.txt`: traducciones de referencia (no se publica).
4. Desempaqueta el `.tar.zst` (Windows 11 ya trae `tar`):

PowerShell:
```
cd C:\Users\JUAN\trabajo-modelo
tar --zstd -xf en-es.tar.zst
```
Git Bash:
```
cd /c/Users/JUAN/trabajo-modelo
tar --zstd -xf en-es.tar.zst
```
Queda la carpeta `en-es/` con el modelo, `LICENSE`, `ATTRIBUTION.txt`, `MODEL_CARD.md` y `SHA256SUMS`.

Comprueba las huellas antes de seguir (Git Bash):
```
cd en-es && sha256sum -c SHA256SUMS
```
Todas deben decir `OK`.

## 5. Subir los archivos del modelo a un release
1. En el repo `lector-bilingue-modelos`: **Releases > Create a new release**.
2. Etiqueta (tag): `opus-en-es-v1`. Título igual.
3. Arrastra **todos los archivos que hay dentro de `en-es/`** (el modelo, los `.spm`, los `.json`, `LICENSE`, etc., y `SHA256SUMS`). Arrastra los archivos, no la carpeta.
4. **Publish release**.

El nombre de cada archivo debe quedar igual al de la carpeta. El catálogo apunta a
`https://github.com/DiegoBr4nd/lector-bilingue-modelos/releases/download/opus-en-es-v1/<archivo>`.

## 6. Crear `catalog.json`
Python 3.12 ya está instalado. Desde la raíz del repo `lector-bilingue`.

PowerShell (una sola línea):
```
python tools/catalog/build_catalog.py --sums C:\Users\JUAN\trabajo-modelo\en-es\SHA256SUMS --sizes-from C:\Users\JUAN\trabajo-modelo\en-es --id opus-en-es-tcbig-2026.10 --pair en-es --engine opus --model-version tc-big-2026.10 --license CC-BY-4.0 --attribution "Helsinki-NLP / OPUS-MT, Tiedemann et al." --release opus-en-es-v1 --catalog C:\Users\JUAN\trabajo-modelo\catalog.json
```
Git Bash:
```
python tools/catalog/build_catalog.py \
  --sums /c/Users/JUAN/trabajo-modelo/en-es/SHA256SUMS \
  --sizes-from /c/Users/JUAN/trabajo-modelo/en-es \
  --id opus-en-es-tcbig-2026.10 --pair en-es --engine opus \
  --model-version tc-big-2026.10 --license CC-BY-4.0 \
  --attribution "Helsinki-NLP / OPUS-MT, Tiedemann et al." \
  --release opus-en-es-v1 \
  --catalog /c/Users/JUAN/trabajo-modelo/catalog.json
```
Qué hace la herramienta:
- Lee `SHA256SUMS`, mide el tamaño de cada archivo y comprueba que la huella coincida.
- Ignora `SHA256SUMS` y cualquier archivo que no esté en la lista.
- Si `catalog.json` ya existe, **reemplaza** el modelo con el mismo `--id` y deja los demás.
- Pone la fecha y hora actual (UTC) en `generated`. La app no acepta un catálogo más viejo que el que ya tiene, así que **siempre** actualiza el archivo existente en vez de empezar de cero.
- Revisa las mismas reglas que la app. Si algo está mal, avisa y no escribe nada.

Mira el resultado (`type catalog.json` o `cat catalog.json`): debe tener los nombres, tamaños y direcciones esperados.

## 7. Firmar el catálogo (con tu llave)
En la carpeta donde está `catalog.json`.

PowerShell:
```
$fecha = Get-Date -Format yyyy-MM-dd
minisign -S -H -m catalog.json -s C:\Users\JUAN\llaves\minisign.key -t "lector-bilingue catalogo $fecha"
```
Git Bash:
```
minisign -S -H -m catalog.json -s /c/Users/JUAN/llaves/minisign.key -t "lector-bilingue catalogo $(date +%F)"
```
- Te pide la contraseña de la llave.
- `-H` es obligatorio: la app solo acepta firmas con ese modo (hash BLAKE2b).
- Crea `catalog.json.minisig` al lado.
- Comprobación opcional:
```
minisign -V -p C:\Users\JUAN\llaves\minisign.pub -m catalog.json
```
(en Git Bash usa `/c/Users/JUAN/llaves/minisign.pub`). Debe decir `Signature and comment signature verified`.

## 8. Publicar el catálogo
1. En `lector-bilingue-modelos`: **Releases > Create a new release**.
2. Etiqueta: `catalogo`. Título: `catalogo`.
3. Sube **dos** archivos: `catalog.json` y `catalog.json.minisig`.
4. **Publish release**. No hace falta marcarlo como "latest": la app usa la etiqueta en la dirección.

La app descarga:
- `https://github.com/DiegoBr4nd/lector-bilingue-modelos/releases/download/catalogo/catalog.json`
- `https://github.com/DiegoBr4nd/lector-bilingue-modelos/releases/download/catalogo/catalog.json.minisig`

## 9. Actualizar el catálogo (otro modelo o nueva versión)
1. Sube los archivos del modelo nuevo a un release nuevo (por ejemplo `opus-en-es-v2`). No borres el viejo hasta que el catálogo nuevo esté publicado.
2. Descarga el `catalog.json` actual del release `catalogo` y ponlo en tu carpeta de trabajo.
3. Corre `build_catalog.py` (paso 6) con `--catalog` apuntando a ese archivo, con el `--id` y `--release` nuevos. Un `--id` nuevo agrega el modelo; el mismo `--id` lo reemplaza.
4. Firma de nuevo (paso 7). Cada vez hay que volver a firmar.
5. En el release `catalogo`: **Edit**, borra los dos archivos viejos y sube los nuevos (`catalog.json` y `catalog.json.minisig`). Que los dos sean del mismo momento: si no, la firma no coincide y la app rechaza el catálogo.

## 10. Rotación de llaves (hazlo ya, antes de necesitarla)
**Rotar** es cambiar a otra llave. Conviene tener lista una segunda llave para el día que la primera se pierda o se filtre.

**Ahora mismo**, crea la llave de reserva (otro nombre, otra contraseña):

PowerShell:
```
cd C:\Users\JUAN\llaves
minisign -G -p reserva.pub -s C:\Users\JUAN\llaves\reserva.key
```
Git Bash:
```
cd /c/Users/JUAN/llaves
minisign -G -p reserva.pub -s /c/Users/JUAN/llaves/reserva.key
```
- Guarda `reserva.key` **offline**, en lugares distintos a los de `minisign.key`. No la uses para firmar mientras no haga falta.
- Pásale al agente de infraestructura las dos llaves **públicas** (`minisign.pub` y `reserva.pub`). La app las incrusta las dos y acepta firmas de cualquiera de ellas. La reserva queda dormida.

**Si la llave actual se compromete** (te la robaron, se filtró, o no estás seguro):
1. Deja de firmar con ella. Avisa al agente de infraestructura.
2. Firma un catálogo nuevo con la llave de reserva (pasos 6 a 9). Las apps ya instaladas lo aceptan, porque ya traen la pública de reserva.
3. Publica una versión nueva de la app que **quite** la llave comprometida y agregue una tercera de reserva (créala antes, igual que arriba). Hasta que la gente actualice, la llave robada todavía sería aceptada, así que actúa rápido.
4. Pide al agente de seguridad que revise el caso y borra de los releases todo lo que no reconozcas.

**Si pierdes la llave actual pero no se filtró**: usa la de reserva igual que arriba y crea una nueva reserva.
