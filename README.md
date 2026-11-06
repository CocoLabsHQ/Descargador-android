# Descargador para Android

App mínima (Kotlin) para descargar video o audio con **yt-dlp**: pegas o compartes un link, eliges video/audio y formato, y listo.
Si se corta el internet, reintenta solo y retoma lo ya descargado. Los archivos quedan en `Descargas/Descargador`.

<p align="center"><img src="app/src/main/res/drawable-nodpi/logo.png" width="160" alt="Descargador"></p>

> Estado: versión 0.2, en pruebas. Las descargas solo avanzan con la app abierta (mantiene la pantalla encendida).

## Cómo obtener el APK
Entra a **Releases** (columna derecha del repositorio) y descarga el `.apk` de la última versión.
Para publicar una versión nueva: `git tag v0.3 && git push origin v0.3` (GitHub Actions compila y la publica sola).

## Botones de la app
- **Actualizar**: busca la última Release de este repositorio y, si es más nueva, la descarga e instala. Al abrir la app también avisa sola si hay una versión nueva.
- **Feedback**: abre una *Issue* nueva en este repositorio con tu comentario, la versión de la app, el modelo de celular y el último error. También se puede compartir por WhatsApp o correo.

> El repositorio debe ser **público** para que los celulares puedan consultar las versiones sin iniciar sesión.

## Limitaciones
- Sin DRM (Netflix, Spotify, etc.), sin contenido privado o de pago.
- YouTube cambia seguido; si falla, hace falta actualizar la biblioteca de yt-dlp y recompilar.
- No descarga en segundo plano.

## Créditos
- [yt-dlp](https://github.com/yt-dlp/yt-dlp), [youtubedl-android](https://github.com/yausername/youtubedl-android) (GPL-3.0) y FFmpeg.
- Código escrito con ayuda de [Claude](https://claude.ai) (Anthropic).

## Aviso legal
Úsalo solo con contenido que tengas derecho a descargar. Sin garantías.

## Licencia
GPL-3.0 (obligatoria porque la biblioteca youtubedl-android usa esa licencia). Ver `LICENSE`.
