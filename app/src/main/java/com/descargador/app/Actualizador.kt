package com.descargador.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Datos de una versión publicada en GitHub (Releases). */
data class Release(val nombre: String, val notas: String, val urlApk: String, val bytes: Long)

sealed class Resultado {
    data class Nueva(val release: Release) : Resultado()
    object AlDia : Resultado()
    data class Error(val mensaje: String) : Resultado()
}

/**
 * Busca la última Release del repositorio, descarga el .apk adjunto y lo instala
 * con el instalador oficial de Android (el sistema pide confirmar).
 */
object Actualizador {

    fun versionActual(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (e: Exception) { "0" }

    /** Llamar fuera del hilo principal. */
    fun buscar(ctx: Context): Resultado {
        var con: HttpURLConnection? = null
        try {
            con = (URL(Config.API_ULTIMA_VERSION).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Descargador-Android")
            }
            val codigo = con.responseCode
            if (codigo == 404) return Resultado.Error(
                "Todavía no hay versiones publicadas en GitHub (o el repositorio es privado).")
            if (codigo != 200) return Resultado.Error("GitHub respondió con el código $codigo. Prueba más tarde.")

            val j = JSONObject(con.inputStream.bufferedReader().use { it.readText() })
            val tag = j.optString("tag_name")
            val assets = j.optJSONArray("assets")
            var url = ""
            var bytes = 0L
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                        url = a.optString("browser_download_url")
                        bytes = a.optLong("size")
                        break
                    }
                }
            }
            if (!esNueva(tag, versionActual(ctx))) return Resultado.AlDia
            if (url.isEmpty()) return Resultado.Error("La versión $tag no tiene un archivo .apk adjunto.")
            return Resultado.Nueva(Release(tag.removePrefix("v"), j.optString("body"), url, bytes))
        } catch (e: Exception) {
            return Resultado.Error("No se pudo consultar GitHub. ¿Tienes internet?")
        } finally {
            con?.disconnect()
        }
    }

    private fun partes(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V").split('.').map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }

    /** true si [remota] (por ejemplo "v0.3") es mayor que [local] (por ejemplo "0.2"). */
    fun esNueva(remota: String, local: String): Boolean {
        if (remota.isBlank()) return false
        val a = partes(remota)
        val b = partes(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun puedeInstalar(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** Descarga el APK a la carpeta privada de la app. Llamar fuera del hilo principal. */
    fun descargar(ctx: Context, r: Release, progreso: (Int) -> Unit, cancelado: () -> Boolean): File {
        val dir = File(ctx.getExternalFilesDir(null), "update").apply { deleteRecursively(); mkdirs() }
        val destino = File(dir, "Descargador-${r.nombre}.apk")
        val con = (URL(r.urlApk).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Descargador-Android")
        }
        try {
            if (con.responseCode != 200) throw RuntimeException("La descarga falló (código ${con.responseCode})")
            val total = if (con.contentLengthLong > 0) con.contentLengthLong else r.bytes
            con.inputStream.use { entrada ->
                destino.outputStream().use { salida ->
                    val buf = ByteArray(64 * 1024)
                    var leidos = 0L
                    var ultimo = -1
                    while (true) {
                        val n = entrada.read(buf)
                        if (n == -1) break
                        if (cancelado()) throw InterruptedException("Cancelado")
                        salida.write(buf, 0, n)
                        leidos += n
                        if (total > 0) {
                            val p = (leidos * 100 / total).toInt().coerceIn(0, 100)
                            if (p != ultimo) { ultimo = p; progreso(p) }
                        }
                    }
                }
            }
        } finally {
            con.disconnect()
        }
        return destino
    }

    /** Entrega el APK al instalador de Android; el sistema mostrará "¿Quieres actualizar?". */
    fun instalar(ctx: Context, apk: File) {
        val instalador = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = instalador.createSession(params)
        instalador.openSession(id).use { sesion ->
            apk.inputStream().use { entrada ->
                sesion.openWrite("descargador.apk", 0, apk.length()).use { salida ->
                    entrada.copyTo(salida)
                    sesion.fsync(salida)
                }
            }
            val intent = Intent(ctx, InstalarReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(ctx, id, intent, flags)
            sesion.commit(pi.intentSender)
        }
    }
}
