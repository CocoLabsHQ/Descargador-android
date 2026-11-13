package com.descargador.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import kotlin.concurrent.thread

/**
 * Hace la descarga en segundo plano: sigue aunque cierres la app o apagues la pantalla,
 * y muestra el progreso (con botón Cancelar) en la barra de notificaciones.
 */
class DescargaService : Service() {

    companion object {
        const val ACCION_CANCELAR = "com.descargador.app.CANCELAR"
        const val CANAL_PROGRESO = "descargas"
        const val CANAL_LISTO = "listo"
        const val ID_PROGRESO = 11
        const val ID_FIN = 12
        private const val X_LINK = "link"
        private const val X_AUDIO = "audio"
        private const val X_FORMATO = "formato"
        private const val X_ALTURA = "altura"
        private const val X_LISTA = "lista"
        private val ITEM = Regex("Downloading item (\\d+) of (\\d+)")
        private val DESTINO = Regex("\\[(?:download|ExtractAudio)] Destination: (.+)$")

        fun iniciar(ctx: Context, link: String, audio: Boolean, formato: String, altura: Int, lista: Boolean) {
            val i = Intent(ctx, DescargaService::class.java)
                .putExtra(X_LINK, link).putExtra(X_AUDIO, audio).putExtra(X_FORMATO, formato)
                .putExtra(X_ALTURA, altura).putExtra(X_LISTA, lista)
            ctx.startForegroundService(i)
        }
    }

    private var wake: PowerManager.WakeLock? = null
    private var trabajando = false
    private var ultimoAviso = 0L
    private var nombre = ""
    private var itemActual = 1
    private var itemTotal = 1

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i == null) { stopSelf(); return START_NOT_STICKY }
        if (i.action == ACCION_CANCELAR) {
            if (DescargaEstado.activa) {
                DescargaEstado.estado = "Cancelando..."
                DescargaEstado.avisar()
                Motor.cancelarDescarga()
            } else stopSelf()
            return START_NOT_STICKY
        }

        crearCanales()
        try {
            startForeground(ID_PROGRESO, notificacion("Preparando...", 0, true),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) { /* sin aviso fijo: la descarga sigue igual mientras la app esté viva */ }

        if (trabajando) return START_NOT_STICKY
        trabajando = true
        DescargaEstado.activa = true

        val link = i.getStringExtra(X_LINK) ?: ""
        val audio = i.getBooleanExtra(X_AUDIO, false)
        val formato = i.getStringExtra(X_FORMATO) ?: "mp4"
        val altura = i.getIntExtra(X_ALTURA, 0)
        val lista = i.getBooleanExtra(X_LISTA, false)

        // Para que el celular no se "duerma" a mitad de la descarga con la pantalla apagada
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "descargador:descarga")
            wake?.acquire(6 * 60 * 60 * 1000L)
        } catch (e: Exception) { }

        thread { trabajo(link, audio, formato, altura, lista) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { wake?.takeIf { it.isHeld }?.release() } catch (e: Exception) { }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ notificaciones

    private fun crearCanales() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL_PROGRESO,
            "Descargas en curso", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CANAL_LISTO,
            "Descarga terminada", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun abrirApp(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notificacion(texto: String, pct: Int, indeterminado: Boolean): Notification {
        val cancelar = PendingIntent.getService(this, 1,
            Intent(this, DescargaService::class.java).setAction(ACCION_CANCELAR),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val accion = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_cerrar), "Cancelar", cancelar).build()
        val b = Notification.Builder(this, CANAL_PROGRESO)
            .setSmallIcon(R.drawable.ic_stat_descarga)
            .setContentTitle(if (nombre.isNotBlank()) nombre else "Descargador")
            .setContentText(texto)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, pct.coerceIn(0, 100), indeterminado)
            .setContentIntent(abrirApp())
            .setCategory(Notification.CATEGORY_PROGRESS)
            .addAction(accion)
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    /** Actualiza la pantalla (si está abierta) y la notificación (como mucho cada 0,8 s). */
    private fun refrescar(forzar: Boolean = false) {
        DescargaEstado.avisar()
        val ahora = SystemClock.elapsedRealtime()
        if (!forzar && ahora - ultimoAviso < 800) return
        ultimoAviso = ahora
        try {
            getSystemService(NotificationManager::class.java).notify(ID_PROGRESO,
                notificacion(DescargaEstado.estado, DescargaEstado.progreso, DescargaEstado.indeterminado))
        } catch (e: Exception) { }
    }

    private fun poner(texto: String, indeterminado: Boolean) {
        DescargaEstado.estado = texto
        DescargaEstado.indeterminado = indeterminado
        refrescar(forzar = true)
    }

    private fun alProgreso(prog: Float, eta: Long, linea: String, lista: Boolean) {
        ITEM.find(linea)?.let {
            itemActual = it.groupValues[1].toIntOrNull() ?: itemActual
            itemTotal = (it.groupValues[2].toIntOrNull() ?: itemTotal).coerceAtLeast(1)
            DescargaEstado.estado = "Video $itemActual de $itemTotal"
            DescargaEstado.indeterminado = false
            refrescar(forzar = true)
        }
        DESTINO.find(linea)?.let { nombre = limpiarNombre(it.groupValues[1]) }
        if (prog < 0f) return
        val pct = if (lista) (((itemActual - 1) + prog / 100f) / itemTotal * 100f).toInt() else prog.toInt()
        DescargaEstado.progreso = pct.coerceIn(0, 100)
        DescargaEstado.indeterminado = false
        DescargaEstado.estado = when {
            lista -> "Video $itemActual de $itemTotal  -  ${prog.toInt()}%"
            eta >= 0 -> "${prog.toInt()}%  -  quedan ${eta}s"
            else -> "${prog.toInt()}%"
        }
        refrescar()
    }

    private fun limpiarNombre(ruta: String): String {
        var n = File(ruta.trim()).name
        n = n.substringBeforeLast('.', n)
        n = n.replace(Regex("\\.f[\\w-]+$"), "")
        n = n.replace(Regex("\\s*\\[[\\w-]{6,}]$"), "")
        return n.take(80)
    }

    // ------------------------------------------------------------------ trabajo

    private fun trabajo(link: String, audio: Boolean, f: String, h: Int, lista: Boolean) {
        val tmp = File(getExternalFilesDir(null), "tmp").apply { deleteRecursively(); mkdirs() }
        var titulo = ""
        var texto = ""
        var exito = false
        try {
            poner("Preparando (la primera vez tarda unos segundos)...", true)
            Motor.esperarInicio()?.let { throw RuntimeException("No se pudo iniciar yt-dlp: $it") }
            if (Motor.actualizando()) {
                poner("Actualizando el motor de descarga (solo tarda un momento)...", true)
                Motor.esperarActualizacion(60_000)
            }
            poner("Descargando...", true)

            var ok = false
            var intento = 0
            var errorFinal: String? = null
            while (!ok && !DescargaEstado.cancelado && intento < 30) {   // yt-dlp reanuda lo ya bajado
                intento++
                try {
                    YoutubeDL.getInstance().execute(
                        request = Motor.pedido(link, audio, f, h, lista, tmp),
                        processId = Motor.PROCESO,
                        callback = { prog, eta, linea -> alProgreso(prog, eta, linea, lista) })
                    ok = true
                } catch (e: Exception) {
                    if (DescargaEstado.cancelado) break
                    val m = e.message ?: ""
                    DescargaEstado.agregar(Motor.resumen(m))
                    if (!Motor.esErrorDeRed(m)) { errorFinal = Motor.resumen(m); break }
                    for (s in 8 downTo 1) {
                        if (DescargaEstado.cancelado) break
                        poner("Sin conexión. Reintentando en ${s}s (intento $intento)...", true)
                        Thread.sleep(1000)
                    }
                }
            }

            // En una lista, aunque falle algún video se guardan los que sí terminaron
            val guardados = if (ok || (lista && !DescargaEstado.cancelado)) guardar(tmp) else 0
            when {
                ok -> {
                    exito = true
                    DescargaEstado.progreso = 100
                    titulo = "Descarga terminada"
                    texto = "$guardados archivo(s) en Descargas/Descargador"
                    DescargaEstado.estado = "¡Listo! $texto"
                }
                DescargaEstado.cancelado -> DescargaEstado.estado = "Cancelado"
                guardados > 0 -> {
                    titulo = "Descarga terminada con errores"
                    texto = "Se guardaron $guardados archivo(s); algunos fallaron"
                    DescargaEstado.estado = texto
                }
                errorFinal != null -> {
                    titulo = "No se pudo descargar"
                    texto = "Abre la app para ver el detalle"
                    DescargaEstado.estado = "Falló. Revisa el detalle abajo."
                }
                else -> {
                    titulo = "No se pudo descargar"
                    texto = "No se pudo completar tras varios intentos"
                    DescargaEstado.estado = texto
                }
            }
        } catch (e: Exception) {
            DescargaEstado.agregar(Motor.resumen(e.message ?: e.toString()))
            DescargaEstado.estado = "Falló. Revisa el detalle abajo."
            titulo = "No se pudo descargar"
            texto = "Abre la app para ver el detalle"
        } finally {
            terminar(titulo, texto, exito)
        }
    }

    private fun terminar(titulo: String, texto: String, exito: Boolean) {
        DescargaEstado.indeterminado = false
        DescargaEstado.activa = false
        trabajando = false
        DescargaEstado.avisar()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) { }
        if (titulo.isNotBlank()) {
            try {
                val n = Notification.Builder(this, CANAL_LISTO)
                    .setSmallIcon(R.drawable.ic_stat_descarga)
                    .setContentTitle(titulo)
                    .setContentText(texto)
                    .setContentIntent(abrirApp())
                    .setAutoCancel(true)
                    .build()
                getSystemService(NotificationManager::class.java).notify(ID_FIN, n)
            } catch (e: Exception) { }
        }
        stopSelf()
    }

    /** Mueve los archivos terminados a Descargas/Descargador (visible en cualquier gestor de archivos). */
    private fun guardar(tmp: File): Int {
        var n = 0
        val fragmento = Regex("\\.f[\\w-]+\\.[A-Za-z0-9]+$")   // restos de video/audio sin unir
        val archivos = tmp.walkTopDown().filter {
            it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") &&
                !it.name.endsWith(".temp") && !fragmento.containsMatchIn(it.name)
        }.toList()
        for (f in archivos) {
            val mime = when (f.extension.lowercase()) {
                "mp4" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"
                "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "opus" -> "audio/ogg"
                "flac" -> "audio/flac"; "wav" -> "audio/wav"; else -> "application/octet-stream"
            }
            val sub = f.parentFile?.relativeTo(tmp)?.path ?: ""
            val carpeta = if (sub.isNotEmpty() && sub != ".") "Download/Descargador/$sub" else "Download/Descargador"
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, carpeta)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: continue
            contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            f.delete(); n++
        }
        return n
    }
}
