# Subtítulos en vivo

App Android que escucha lo que suena en el celular (reels, videos), lo transcribe en inglés y lo traduce al español en una ventanita flotante. Funciona sin internet después de la primera vez.

## Cómo funciona

1. **Audio**: captura el audio interno con `AudioPlaybackCapture` (Android 10+), o el micrófono si lo elegís.
2. **Voz → texto**: [Vosk](https://alphacephei.com/vosk/) con el modelo chico en inglés, corriendo en el celular.
3. **Traducción**: ML Kit Translate de Google (gratis, offline).
4. **Ventanita**: se dibuja encima de cualquier app. Arrastrala para moverla; doble toque muestra u oculta el inglés.

## Instalar

Cada vez que se sube un cambio a `main`, GitHub compila el APK y lo publica en **Releases → Última versión**. Bajá `SubtitulosEnVivo.apk` desde el celular y abrilo (hay que permitir instalar apps de origen desconocido).

## Archivos

- `MainActivity.kt`: pantalla principal y permisos.
- `CaptionService.kt`: captura de audio, reconocimiento y traducción.
- `CaptionOverlay.kt`: la ventanita flotante.
- `ModelAssets.kt`: copia el modelo de voz del APK a la memoria de la app.
