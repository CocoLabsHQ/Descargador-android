package com.descargador.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/** Una fila de resultados de búsqueda. */
class Hallazgo(
    val titulo: String,
    val autor: String,
    val duracion: String,
    val url: String,
    val miniatura: String?,
    val esLista: Boolean,
    val cantidad: Int)

enum class Fuente(val etiqueta: String) {
    YOUTUBE("YouTube"),
    LISTAS("Listas de YouTube"),
    MUSICA("YouTube Music"),
    SOUNDCLOUD("SoundCloud")
}

object Busqueda {

    private fun cod(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun consulta(f: Fuente, q: String, n: Int): String = when (f) {
        Fuente.YOUTUBE -> "ytsearch$n:$q"
        Fuente.SOUNDCLOUD -> "scsearch$n:$q"
        Fuente.LISTAS -> "https://www.youtube.com/results?search_query=${cod(q)}&sp=EgIQAw%3D%3D"
        Fuente.MUSICA -> "https://music.youtube.com/search?q=${cod(q)}"
    }

    /** Busca con yt-dlp (sin descargar nada) y devuelve los resultados. Lanza excepción si falla. */
    fun buscar(f: Fuente, texto: String, cantidad: Int = 15): List<Hallazgo> {
        Motor.esperarInicio()?.let { throw RuntimeException("No se pudo iniciar yt-dlp: $it") }
        if (Motor.actualizando()) Motor.esperarActualizacion(45_000)

        val req = YoutubeDLRequest(consulta(f, texto, cantidad)).apply {
            addOption("--flat-playlist")
            addOption("--dump-single-json")
            addOption("--no-warnings")
            addOption("--playlist-end", cantidad.toString())
            addOption("--socket-timeout", "20")
        }
        val resp = YoutubeDL.getInstance().execute(request = req, processId = Motor.PROCESO_BUSQUEDA)
        return parsear(resp.out, f)
    }

    private fun JSONObject.txt(k: String): String = if (isNull(k)) "" else optString(k, "")

    private fun formatoDuracion(seg: Long): String {
        if (seg <= 0) return ""
        val h = seg / 3600; val m = (seg % 3600) / 60; val s = seg % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun parsear(json: String, f: Fuente): List<Hallazgo> {
        val ini = json.indexOf('{')
        if (ini < 0) return emptyList()
        val arr = JSONObject(json.substring(ini)).optJSONArray("entries") ?: return emptyList()
        val salida = ArrayList<Hallazgo>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val id = e.txt("id")
            var url = e.txt("url").ifBlank { e.txt("webpage_url") }
            if (!url.startsWith("http")) {
                if (id.isNotBlank() && (f == Fuente.YOUTUBE || f == Fuente.MUSICA)) url = "https://www.youtube.com/watch?v=$id"
                else if (id.isNotBlank() && f == Fuente.LISTAS) url = "https://www.youtube.com/playlist?list=$id"
                else continue
            }
            val titulo = e.txt("title").ifBlank { url }
            val autor = listOf("channel", "uploader", "creator", "artist")
                .map { e.txt(it) }.firstOrNull { it.isNotBlank() } ?: ""
            val seg = if (e.isNull("duration")) 0L else e.optDouble("duration", 0.0).toLong()
            val esLista = f == Fuente.LISTAS || url.contains("/playlist?list=") || url.contains("/sets/")

            // Miniatura: para videos de YouTube se arma la dirección conocida (liviana); el resto usa la que informa yt-dlp
            val idYt = Regex("[?&]v=([\\w-]{11})").find(url)?.groupValues?.get(1)
            var mini: String? = if (idYt != null) "https://i.ytimg.com/vi/$idYt/mqdefault.jpg" else null
            if (mini == null) mini = e.txt("thumbnail").ifBlank { null }
            if (mini == null) {
                val ts = e.optJSONArray("thumbnails")
                if (ts != null && ts.length() > 0) mini = ts.optJSONObject(ts.length() - 1)?.txt("url")?.ifBlank { null }
            }
            salida.add(Hallazgo(titulo, autor, formatoDuracion(seg), url,
                mini?.replace("http://", "https://"), esLista,
                if (e.isNull("playlist_count")) 0 else e.optInt("playlist_count", 0)))
        }
        return salida
    }
}

/** Baja las miniaturas de los resultados en segundo plano y las guarda en memoria. */
object Miniaturas {
    private val cache = LruCache<String, Bitmap>(40)

    fun cargar(url: String?, img: ImageView) {
        img.tag = url
        img.setImageDrawable(null)
        if (url.isNullOrBlank()) return
        cache.get(url)?.let { img.setImageBitmap(it); return }
        thread {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                val bytes = c.inputStream.use { it.readBytes() }
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var s = 1
                while (o.outWidth / s > 640) s *= 2
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = s })
                if (bmp != null) {
                    cache.put(url, bmp)
                    img.post { if (img.tag == url) img.setImageBitmap(bmp) }
                }
            } catch (e: Exception) { /* sin miniatura: no pasa nada */ }
        }
    }
}
