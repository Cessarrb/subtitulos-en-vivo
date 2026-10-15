# Subtítulos en vivo

App Android que lee los **Subtítulos instantáneos** de Android (Live Caption) y muestra la traducción al español en una ventanita flotante.

## Cómo funciona

1. Android transcribe el audio con sus Subtítulos instantáneos (en inglés).
2. Un **servicio de accesibilidad** lee el texto de ese cuadro.
3. **ML Kit Translate** de Google lo traduce al español (gratis, offline después de la primera vez).
4. La traducción aparece en una ventanita azul que se puede arrastrar.

La versión anterior, que transcribía el audio por su cuenta con Vosk, está en la rama `version-vosk`.

## Instalar

- **GitHub Actions**: cada cambio en `main` compila el APK y lo publica en **Releases → Última versión**.
- **Android Studio**: abrí la carpeta del proyecto y usá **Build → Build APK(s)**.

Al ser una app instalada a mano, en Android 13 o superior puede que haya que ir a *Info de la app → ⋮ → Permitir configuración restringida* antes de poder activar el servicio de accesibilidad.

## Archivos

- `MainActivity.kt`: instrucciones y diagnóstico.
- `CaptionReaderService.kt`: lee los subtítulos y los traduce.
- `CaptionOverlay.kt`: la ventanita flotante.
