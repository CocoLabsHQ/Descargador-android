package com.descargador.app

import android.app.Application
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import kotlin.concurrent.thread

/** Estado de la descarga en curso. Lo escribe el servicio y lo lee la pantalla cuando está abierta. */
object DescargaEstado {
    @Volatile var activa = false
    @Volatile var cancelado = false
    @Volatile var progreso = 0
    @Volatile var indeterminado = false
    @Volatile var estado = "Listo para descargar"
    @Volatile var detalle = ""
    @Volatile var oyente: (() -> Unit)? = null

    fun empezar() {
        activa = true; cancelado = false; progreso = 0; indeterminado = true
        estado = "Preparando..."; detalle = ""
        avisar()
    }

    @Synchronized fun agregar(linea: String) { detalle += linea + "\n" }
    fun avisar() { oyente?.invoke() }
}

/** yt-dlp y ffmpeg: se inician una sola vez por proceso y los usan la pantalla (búsqueda) y el servicio (descarga). */
object Motor {
    const val PROCESO = "descarga"
    const val PROCESO_BUSQUEDA = "busqueda"

    // Solo estos errores se consideran "se cortó el internet" y se reintentan solos.
    // Cualquier otro (YouTube bloquea, formato no disponible, etc.) se muestra tal cual.
    private val RED = listOf("timed out", "Name or service", "Temporary failure", "Connection reset",
        "Connection refused", "Connection aborted", "Network is unreachable", "No route to host",
        "Errno -2", "Errno 101", "Errno 104", "Errno 110", "Errno 111",
        "HTTP Error 5", "IncompleteRead", "SSL:", "EOF occurred")

    @Volatile var initError: String? = null
    private var initThread: Thread? = null
    private var updateThread: Thread? = null

    @Synchronized fun iniciar(app: Application) {
        if (initThread != null) return
        val t = thread {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
            } catch (e: Exception) { initError = e.message ?: e.toString() }
        }
        initThread = t
        // yt-dlp que trae la app se queda viejo (YouTube cambia seguido): al abrir se baja el último.
        // Si no hay internet simplemente se sigue con el que ya está.
        updateThread = thread {
            try {
                t.join()
                if (initError == null) YoutubeDL.getInstance().updateYoutubeDL(app)
            } catch (e: Exception) { /* sin internet o GitHub no responde: no pasa nada */ }
        }
    }

    /** Espera a que el motor termine de iniciar. Devuelve el error si falló, o null si todo bien. */
    fun esperarInicio(): String? { initThread?.join(); return initError }

    fun actualizando(): Boolean = updateThread?.isAlive == true
    fun esperarActualizacion(maxMs: Long) { updateThread?.takeIf { it.isAlive }?.join(maxMs) }

    fun cancelarDescarga() {
        DescargaEstado.cancelado = true
        try { YoutubeDL.getInstance().destroyProcessById(PROCESO) } catch (e: Exception) { }
    }

    fun cancelarBusqueda() {
        try { YoutubeDL.getInstance().destroyProcessById(PROCESO_BUSQUEDA) } catch (e: Exception) { }
    }

    fun esErrorDeRed(m: String) = RED.any { m.contains(it, ignoreCase = true) }

    /** yt-dlp mezcla avisos (WARNING) con el error real (ERROR:): mostramos el error real. */
    fun resumen(m: String): String {
        val errores = m.lines().filter { it.contains("ERROR:") }
        return if (errores.isNotEmpty()) errores.joinToString("\n").take(500) else m.takeLast(400)
    }

    fun pedido(link: String, audio: Boolean, f: String, h: Int, lista: Boolean, tmp: File) =
        YoutubeDLRequest(link).apply {
            addOption(if (lista) "--yes-playlist" else "--no-playlist")
            addOption("--retries", "infinite")          // reintenta si el internet se corta
            addOption("--fragment-retries", "infinite")
            addOption("--retry-sleep", "3")
            addOption("--socket-timeout", "30")
            addOption("--no-mtime")
            val nombre = if (lista)
                "%(playlist_title|Lista).60s/%(playlist_index|0)s - %(title).80s [%(id)s].%(ext)s"
            else "%(title).80s [%(id)s].%(ext)s"
            addOption("-o", tmp.absolutePath + "/" + nombre)
            if (audio) {
                addOption("-x"); addOption("--audio-format", f); addOption("--audio-quality", "0")
            } else {
                addOption("-f", if (h > 0) "bv*[height<=$h]+ba/b[height<=$h]" else "bv*+ba/b")
                addOption("--merge-output-format", f)
                when (f) {
                    "mp4" -> addOption("-S", "res,ext:mp4:m4a")
                    "webm" -> addOption("-S", "res,vcodec:vp9,acodec:opus")
                }
            }
        }
}
