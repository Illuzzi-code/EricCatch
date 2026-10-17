# SnapTube Pro — Comparativa con apps similares
*Actualizado: Octubre 2026*

## ¿En qué celulares corre?

**Requisito mínimo: Android 7.0 (API 24) con arquitectura ARM64.**

| Especificación | Mínimo recomendado | Notas |
|---|---|---|
| Android | 7.0 Nougat (2016) | `minSdk = 24` |
| Arquitectura CPU | ARM64-v8a | La librería youtubedl-android incluye binarios ARM64 |
| RAM | 1 GB | yt-dlp + Python corren ligero en background |
| Almacenamiento libre | ~200 MB | Librería + archivos descargados |
| Conexión | Internet para búsqueda y descarga | — |

### ¿Un celular "básico" lo corre?

**Sí.** Un Moto E, Redmi A-series, Samsung Galaxy A0x o similar de 2018 en adelante lo corre sin problema. El APK pesa ~15 MB (incluye la librería youtubedl-android). La primera vez descarga Python + yt-dlp (~80 MB adicionales) de forma automática.

---

## Estado actual de la app (Octubre 2026)

### ✅ Lo que ya funciona
- Búsqueda integrada de YouTube via Innertube API (20 resultados)
- Miniaturas, título, duración y canal en resultados
- Descarga MP4 máxima calidad (bestvideo+bestaudio merged)
- Descarga MP3 máxima calidad (VBR ~320kbps)
- Archivos guardados en `/sdcard/Download/SnapTubePro/`
- yt-dlp se actualiza automáticamente al abrir la app (máx 1 vez/día)
- Evita bot-detection con `player_client=android`
- Funciona con URLs directas de YouTube también
- Sin anuncios, sin trackers

### ⚠️ Limitaciones actuales
- Solo YouTube (búsqueda). Otros sitios funcionan pegando URL directa.
- Sin historial de descargas
- Sin cola — una descarga a la vez
- Sin selector de calidad/resolución
- Sin notificación al terminar descarga
- Sin modo offline / reproductor integrado

---

## Comparativa con apps similares

| Característica | **SnapTube Pro** *(esta app)* | **SnapTube** (oficial) | **NewPipe** | **VidMate** | **YTDLnis** |
|---|---|---|---|---|---|
| **Código fuente** | ✅ Tuyo | ❌ Cerrado | ✅ Open source | ❌ Cerrado | ✅ Open source |
| **Anuncios** | ✅ Sin anuncios | ❌ Muchos | ✅ Sin anuncios | ❌ Muchos | ✅ Sin anuncios |
| **Trackers** | ✅ Ninguno | ❌ Facebook SDK, Firebase | ✅ Ninguno | ❌ Extensivos | ✅ Ninguno |
| **Motor de descarga** | yt-dlp 2026.08 | Scrapers propios | Extractores propios | Scrapers propios | yt-dlp (Python) |
| **Sitios soportados** | 1000+ (URL directa) | ~50 | ~30 | ~200 | 1000+ |
| **Búsqueda integrada** | ✅ YouTube (Innertube) | ✅ YouTube | ✅ YouTube | ✅ YouTube + otros | ✅ YouTube |
| **Calidad MP4** | ✅ Hasta 4K/8K | ✅ Hasta 1080p | ✅ Hasta 1080p | ✅ Hasta 1080p | ✅ Hasta 4K |
| **MP3 máxima calidad** | ✅ VBR ~320kbps | ✅ | ✅ | ✅ | ✅ |
| **Historial descargas** | ❌ No | ✅ Sí | ✅ Sí | ✅ Sí | ✅ Sí |
| **Cola de descargas** | ❌ No | ✅ Sí | ❌ No | ✅ Sí | ✅ Sí |
| **Selector de calidad** | ❌ No (auto máx.) | ✅ Sí | ✅ Sí | ✅ Sí | ✅ Sí |
| **Notificaciones** | ❌ No | ✅ Sí | ✅ Sí | ✅ Sí | ✅ Sí |
| **Reproductor integrado** | ❌ No | ✅ Sí | ✅ Sí | ✅ Sí | ❌ No |
| **Miniaturas en búsqueda** | ✅ Sí | ✅ Sí | ✅ Sí | ✅ Sí | ✅ Sí |
| **Anti bot-detection** | ✅ player_client=android | Propio | Parcial | Propio | ✅ Completo |
| **Android mínimo** | 7.0 (API 24) | 5.0 (API 21) | 5.0 (API 21) | 5.0 (API 21) | 5.0 (API 21) |
| **Peso APK** | ~15 MB | ~25 MB | ~15 MB | ~35 MB | ~20 MB |
| **Play Store** | ❌ APK | ❌ APK | ❌ F-Droid | ❌ APK | ❌ APK/F-Droid |

---

## Mejoras priorizadas por impacto

### 🔴 Alta prioridad (cambian mucho la experiencia)

**1. Notificación de descarga completa**
Cuando termina una descarga la app no avisa si está en background. Fácil de implementar con `NotificationManager` — el permiso ya está pedido.

**2. Historial de descargas**
Una pantalla con los archivos ya descargados (título, miniatura, fecha, tamaño). Se implementa con `Room` database o simplemente leyendo los archivos de la carpeta `SnapTubePro/`.

**3. Selector de calidad**
Antes de descargar mostrar las opciones disponibles: 4K, 1080p, 720p, 480p para MP4; y 320kbps, 192kbps, 128kbps para MP3. Se obtiene con `yt-dlp -F <url>`.

### 🟡 Media prioridad (mejoran la usabilidad)

**4. Cola de descargas múltiples**
Poder agregar varios videos a una cola y que se descarguen uno tras otro. Requiere `WorkManager` + lista de tareas pendientes.

**5. Paginación de búsqueda**
Cargar más resultados al llegar al final de la lista (scroll infinito). Innertube devuelve un `continuationToken` para la siguiente página.

**6. Soporte a más sitios en búsqueda**
Actualmente la búsqueda solo es YouTube. Agregar pestañas o un selector: YouTube / SoundCloud / TikTok (estos tienen APIs/scrapers similares).

**7. Compartir desde otras apps**
Manejar el intent `ACTION_SEND` para que cuando compartas un link desde YouTube, Chrome, Instagram, etc., se abra SnapTube Pro directamente con la URL lista.

### 🟢 Baja prioridad (pulido)

**8. Tema claro**
La app solo tiene tema oscuro. Agregar soporte a tema claro/automático según el sistema.

**9. Miniaturas en alta resolución**
Actualmente usa `hqdefault.jpg` (480x360). Se puede usar `maxresdefault.jpg` (1280x720) para la pantalla de detalle.

**10. Cancelar descarga en curso**
Poder cancelar una descarga que está tardando mucho. La librería tiene `destroyProcessById()` para esto.

**11. Renombrar archivo antes de descargar**
Que el usuario pueda editar el nombre del archivo antes de guardar.

---

## Veredicto real vs. la competencia

SnapTube Pro ya supera a SnapTube oficial y VidMate en lo más importante: **sin anuncios, sin trackers, código tuyo**. La diferencia principal con NewPipe y YTDLnis es el **historial, la cola y el selector de calidad** — que son las 3 mejoras de mayor impacto para llevarla a nivel publicable.

---

*Nota legal: descargar contenido protegido por copyright sin autorización puede violar los términos de servicio de las plataformas y leyes de tu país. El uso personal de material libre de derechos es generalmente aceptado.*
