# Publicar en Google Play · guía para Juan

Esta guía te lleva de cero a tener la versión 0.9.0 en la **prueba interna** de Google Play (una pista privada, solo para tus cuentas de prueba).

Reglas que no se rompen:
- Las llaves y las contraseñas son **solo tuyas**. Claude no las crea, no las lee, no las guarda.
- Nada de esto se copia dentro de la carpeta del proyecto (el repositorio).
- Comandos para **Git Bash** de Windows. Pégalos tal cual, uno a uno.

## Palabras nuevas
- **AAB** (Android App Bundle): el archivo que se sube a Play. Play arma de él el APK que se instala en cada teléfono.
- **Llave de subida**: tu firma personal. Prueba a Play que el AAB lo subes tú.
- **Llave de la app**: la que firma lo que reciben los usuarios. La guarda y la cuida Google (**Play App Signing**). Tú no la tienes.
- **keystore** (`.jks`): un archivo que guarda tu llave de subida, protegido con contraseña.
- **keytool** y **jarsigner**: programas que vienen con Android Studio. Crean y revisan llaves y firmas.
- **Tag** de git: una etiqueta fija en un punto de la historia del código (aquí, `v0.9.0`).

## 1. Crear la llave de subida (una sola vez)

```bash
mkdir -p /c/Users/JUAN/llaves
"/c/Program Files/Android/Android Studio/jbr/bin/keytool" -genkeypair -v \
  -keystore /c/Users/JUAN/llaves/lector-subida.jks -alias subida \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Tu nombre, O=Lector bilingue, C=XX"
```

Antes de pegarlo, cambia:
- `CN=` por tu nombre.
- `C=XX` por el código de tu país de dos letras (`CO`, `MX`, `ES`...).
- `O=` es la organización; puedes dejarla.

Qué pasa al correrlo:
- Te pide una contraseña para el keystore y otra para la llave (puede ser la misma). Al teclear no se ve nada.
- `-validity 10000` son unos 27 años; Play lo exige largo.
- Termina con un mensaje que dice que generó el par de claves.

Contraseñas:
- Largas (frase de 4 o 5 palabras) y que no uses en otro sitio.
- Guárdalas en un gestor de contraseñas o en papel, **separadas** del archivo `.jks`.

Respaldos (el `.jks` no se puede regenerar):
- Copia `lector-subida.jks` a un USB.
- Copia a un segundo lugar físico distinto (otro USB en otra casa, por ejemplo).
- No lo subas a la nube sin cifrar, ni al correo, ni a GitHub.

Si lo pierdes:
- No pierdes la app: la llave de la app la tiene Google.
- Creas una llave nueva y pides a Play que cambie la llave de subida: [Ayuda de Play Console, restablecer la llave de subida](https://support.google.com/googleplay/android-developer/answer/9842756).
- Solo puede pedirlo el dueño de la cuenta y no es inmediato.

## 2. Decirle a Gradle dónde está la llave

**Gradle** es el programa que compila la app. Lee la llave desde un archivo tuyo, fuera del proyecto.

1. Crea con el Bloc de notas el archivo `C:\Users\JUAN\llaves\lector-subida.properties` con este contenido (cambia los `…` por tus contraseñas):

```properties
storeFile=C:/Users/JUAN/llaves/lector-subida.jks
storePassword=…
keyAlias=subida
keyPassword=…
```

2. Abre (o crea) `C:\Users\JUAN\.gradle\gradle.properties` y añade esta línea:

```properties
lector.firma=C:/Users/JUAN/llaves/lector-subida.properties
```

Ojo con las rutas:
- Usa **rutas completas** y con `/` (no `\`). En estos archivos la barra invertida es un carácter especial y rompe la ruta.
- Una ruta corta (relativa) se cuenta desde la carpeta `app/`, no desde donde estés; por eso se usa la completa.

Si algo falta, Gradle lo dice sin mostrar contraseñas:
- «No existe el archivo de firma…»: la ruta de `lector.firma` está mal.
- «Falta «storePassword» en el archivo de firma»: falta esa línea o está vacía.

Sin `lector.firma`, la compilación sale **sin firmar** y no falla. Eso es normal en el CI.

## 3. Compilar el AAB firmado y verificarlo

```bash
cd /c/Users/JUAN/Documents/lector-bilingue
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew bundlePlayRelease
```

El archivo queda en `app/build/outputs/bundle/playRelease/app-play-release.aab`.

Verifica la firma:

```bash
"/c/Program Files/Android/Android Studio/jbr/bin/jarsigner" -verify -verbose -certs \
  app/build/outputs/bundle/playRelease/app-play-release.aab | tail -5
```

- Debe terminar con **jar verified**.
- Si dice «jar is unsigned», Gradle no encontró `lector.firma`: revisa el paso 2.

## 4. Etiquetar la versión (antes de subir)

Hazlo cuando el PR de la fase 6b ya esté fusionado en `main`, y compila el AAB **desde ese `main`**. El enlace «Código fuente» de la app apunta a este tag (la licencia GPL pide que el código corresponda al binario publicado).

```bash
git checkout main
git pull
git tag v0.9.0
git push origin v0.9.0
```

Luego repite el paso 3 desde `main` si compilaste en otra rama.

## 5. Play Console: crear la app y subir a prueba interna

Entra a [Play Console](https://play.google.com/console) con tu cuenta de desarrollador. Los nombres de botones cambian con el tiempo; aquí van descritos de forma general.

1. **Crear app**:
   - Idioma predeterminado: español (Latinoamérica).
   - Tipo: aplicación. Gratuita.
   - Acepta las declaraciones.
2. El paquete (`io.github.diegobr4nd.lectorbilingue`) lo fija el primer AAB que subas. No se puede cambiar después.
3. **Firma de apps (Play App Signing)**: acepta que Google genere y guarde la llave de la app. Tu llave de subida es la del paso 1.
4. Menú **Pruebas > Prueba interna**:
   - Crea una lista de testers (tu correo y los de quien quieras).
   - **Crear versión** y sube `app-play-release.aab`.
   - Escribe las notas de la versión (por ejemplo: «Primera versión de prueba»).
   - Guarda y publica la versión en prueba interna.
5. Copia el enlace de invitación de testers y ábrelo desde el teléfono con una cuenta de la lista.

Si Play te frena:
- Puede pedir antes formularios (política de privacidad, Seguridad de los datos, clasificación de contenido, ficha de la tienda, declaración de anuncios).
- Esos formularios son de la **fase 6d**. Si aparecen, **para aquí** y avisa a Claude; la subida se hace en la 6d.
- No inventes respuestas: el contenido de esos formularios lo prepara Claude contigo.

## 6. Informe previo al lanzamiento

Al subir a una pista, Play prueba el AAB en teléfonos reales y genera un **informe previo al lanzamiento** (pre-launch report). Suele aparecer unas horas después.

- Búscalo en Play Console, dentro de la sección de pruebas/lanzamiento de esa versión.
- Mira cierres inesperados (crashes), problemas de accesibilidad y avisos de seguridad.
- Trae a Claude el texto de los avisos.
- No mandes capturas con texto de libros con derechos de autor.

## 7. Verificación de desarrolladores de Google

Google exige que los desarrolladores de Android verifiquen su identidad y registren sus apps. Fuentes oficiales:
- [Android developer verification](https://developer.android.com/developer-verification/guides)
- [Anuncio en el blog de Android Developers](https://android-developers.googleblog.com/2026/06/android-developer-verification.html)

Resumen (confirma las fechas en esas páginas, pueden cambiar):
- **Apps en Play**: Play Console registra el paquete por ti, ligado a tu cuenta y a tu firma. Mira la página de inicio de Play Console por si queda alguna app sin registrar; el plazo anunciado era el 30 de septiembre de 2026.
- **Fuera de Play** (F-Droid, APK suelto; viene después del lanzamiento): hace falta registrar el paquete aparte, demostrando que eres dueño de la app con un APK firmado con tu llave, y verificar tu identidad. Eso se decide en esa fase.

## 8. Copia de prueba en el Pixel (~15 min)

La **copia de prueba** es la versión release (la que recorta código con R8) para probar en tu teléfono sin tocar tu app. Se llama «Lector (prueba)», tiene otro paquete (`io.github.diegobr4nd.lectorbilingue.prueba`) y datos propios. Va firmada con la llave de debug, no con la tuya.

```bash
cd /c/Users/JUAN/Documents/lector-bilingue
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew assemblePlayPrueba
MSYS_NO_PATHCONV=1 adb devices
MSYS_NO_PATHCONV=1 adb install app/build/outputs/apk/play/prueba/app-play-prueba.apk
```

- El Pixel debe aparecer en `adb devices` (depuración USB activada).
- Usa `adb install` sin `-r`. Es otro paquete, así que no pisa tu app.
- Si dice que ya está instalada, bórrala primero (paso 6 del recorrido).

Recorrido:
1. Bienvenida: descarga un idioma con el motor rápido (Firefox) y otro con el de calidad (OPUS).
2. Añade un libro tuyo.
3. Toca un párrafo y mira la traducción. Prueba los modos Intercalado y Solo traducción.
4. Ajustes de lectura, Índice y cambio de capítulo.
5. «Acerca de», «Licencias» (el enlace al código debe abrir el tag `v0.9.0`) y «Enviar comentarios».
6. Borrar la copia: Ajustes de Android > Apps > «Lector (prueba)» > Desinstalar. Tu app «Lector» queda igual.

Si algo falla, anota qué hiciste y qué pasó, y avisa a Claude.
