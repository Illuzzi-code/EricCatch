# 📲 EricCatch Pro

> Descargador de videos y música para Android — rápido, moderno y sin anuncios.

<p align="center">
  <img src="https://img.shields.io/badge/Android-5.0%2B-brightgreen?logo=android" />
  <img src="https://img.shields.io/badge/yt--dlp-powered-blueviolet" />
  <img src="https://img.shields.io/github/v/release/Illuzzi-code/EricCatch?label=última%20versión" />
  <img src="https://img.shields.io/github/actions/workflow/status/Illuzzi-code/EricCatch/build-release.yml?label=build" />
</p>

---

## ✨ Características

- 🎬 **Descarga videos** en hasta 4K / 1080p Full HD
- 🎵 **Extrae audio** en MP3 de máxima calidad con ffmpeg
- 🔍 **Búsqueda integrada** — busca directamente desde la app sin salir
- 📋 **Historial de descargas** — reproduce, comparte o elimina tus archivos
- 🔔 **Descarga en background** — sigue usando el teléfono mientras descarga
- 📊 **Progreso en tiempo real** — velocidad, tamaño y porcentaje en vivo
- 🌐 Compatible con YouTube, TikTok, Instagram, Twitter/X, Facebook, SoundCloud y más
- 🎨 Diseño dark glass moderno con animaciones fluidas
- 🔐 Login opcional con cuenta YouTube para descargas en HD sin bloqueos

---

## 📥 Descargar

### Última versión
👉 **[Ir a Releases](../../releases/latest)** y descarga el archivo `.apk`

### Instalación
1. Descarga el `.apk` desde Releases
2. En tu Android: **Ajustes → Seguridad → Instalar apps desconocidas** (activar)
3. Abre el `.apk` e instala
4. ¡Listo!

---

## 🛠️ Tecnologías

| Componente | Tecnología |
|---|---|
| Lenguaje | Kotlin |
| UI | Material Design 3 + animaciones custom |
| Descarga | [yt-dlp](https://github.com/yt-dlp/yt-dlp) vía [youtubedl-android](https://github.com/JunkFood02/youtubedl-android) |
| Conversión de audio | FFmpeg |
| Imágenes | Glide |
| Arquitectura | MVVM + LiveData + ViewModel |
| Build | Gradle KTS |
| CI/CD | GitHub Actions |

---

## 🔨 Compilar desde código fuente

```bash
git clone https://github.com/Illuzzi-code/EricCatch.git
cd EricCatch
./gradlew assembleDebug
# El APK queda en app/build/outputs/apk/debug/
```

Requiere Android Studio Hedgehog o superior / JDK 17.

---

## ⚠️ Aviso legal

Esta app usa yt-dlp para obtener contenido de plataformas de video.
Úsala únicamente para descargar contenido del que tengas derechos o que esté bajo licencias que lo permitan (Creative Commons, dominio público, etc.).
El desarrollador no se responsabiliza del uso indebido.

---

## 📄 Licencia

MIT — libre para uso personal.
