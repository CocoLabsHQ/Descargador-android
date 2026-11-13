package com.descargador.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File

/**
 * Apariencia de la app: estilos (paletas) en modo claro u oscuro, color de botones a elección
 * y foto de la galería como fondo. Todo se guarda en SharedPreferences y se aplica desde el código.
 */
object Temas {

    class Estilo(
        val id: String, val nombre: String,
        val tono: Float, val sat: Float,
        val acentoClaro: Int, val acentoOscuro: Int)

    val estilos = listOf(
        Estilo("bosque", "Bosque nórdico", 146f, 1.0f, 0xFF2E7D5B.toInt(), 0xFF5FCB9A.toInt()),
        Estilo("mate", "Mate", 38f, 1.0f, 0xFFA5651A.toInt(), 0xFFF2B35E.toInt()),
        Estilo("atardecer", "Atardecer", 14f, 1.0f, 0xFFD9552B.toInt(), 0xFFFF8A65.toInt()),
        Estilo("oceano", "Océano", 204f, 1.0f, 0xFF1E73BE.toInt(), 0xFF64B5F6.toInt()),
        Estilo("lavanda", "Lavanda", 262f, 0.85f, 0xFF6C4DC4.toInt(), 0xFFB39DFF.toInt()),
        Estilo("sakura", "Sakura", 338f, 0.8f, 0xFFC2336F.toInt(), 0xFFF48FB1.toInt()),
        Estilo("grafito", "Grafito", 215f, 0.18f, 0xFF3F4F5A.toInt(), 0xFFB0BEC5.toInt()))

    /** Colores sugeridos para los botones (el primero, 0, significa "el del estilo"). */
    val acentos = intArrayOf(
        0,
        0xFF2E7D5B.toInt(), 0xFFE8742A.toInt(), 0xFF1E88E5.toInt(), 0xFF7E57C2.toInt(),
        0xFFD81B60.toInt(), 0xFFD32F2F.toInt(), 0xFFF9A825.toInt(), 0xFF00ACC1.toInt(),
        0xFF455A64.toInt())

    class Paleta(
        val oscuro: Boolean,
        val fondo: Int,
        val tarjeta: Int,          // puede ser translucida si hay foto de fondo
        val tarjetaSolida: Int,    // para menús desplegables
        val borde: Int,
        val texto: Int,
        val suave: Int,
        val cabecera: Int,
        val acento: Int,
        val sobreAcento: Int,
        val peligro: Int,
        val hayFoto: Boolean)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("tema", Context.MODE_PRIVATE)

    fun modo(ctx: Context): String = sp(ctx).getString("modo", "claro") ?: "claro"
    fun estiloId(ctx: Context): String = sp(ctx).getString("estilo", "bosque") ?: "bosque"
    fun acentoElegido(ctx: Context): Int = sp(ctx).getInt("acento", 0)
    fun velo(ctx: Context): Int = sp(ctx).getInt("velo", 35)

    fun poner(ctx: Context, clave: String, valor: String) = sp(ctx).edit().putString(clave, valor).apply()
    fun poner(ctx: Context, clave: String, valor: Int) = sp(ctx).edit().putInt(clave, valor).apply()
    fun restablecer(ctx: Context) {
        sp(ctx).edit().clear().apply()
        quitarFoto(ctx)
    }

    fun esOscuro(ctx: Context): Boolean = when (modo(ctx)) {
        "oscuro" -> true
        "auto" -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        else -> false
    }

    fun estilo(ctx: Context): Estilo = estilos.firstOrNull { it.id == estiloId(ctx) } ?: estilos[0]

    private fun hsv(h: Float, s: Float, v: Float): Int =
        Color.HSVToColor(floatArrayOf(h, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)))

    private fun conAlfa(color: Int, alfa: Int): Int = (alfa shl 24) or (color and 0xFFFFFF)

    fun archivoFoto(ctx: Context): File? =
        File(ctx.filesDir, "fondo.jpg").takeIf { it.exists() && it.length() > 0 }

    fun paleta(ctx: Context): Paleta = paleta(ctx, estilo(ctx), esOscuro(ctx))

    /** Paleta de un estilo cualquiera (para la vista previa del selector). */
    fun paleta(ctx: Context, e: Estilo, oscuro: Boolean): Paleta {
        val h = e.tono
        val s = e.sat
        val foto = archivoFoto(ctx) != null
        val elegido = acentoElegido(ctx)
        val acento = if (elegido != 0) elegido else if (oscuro) e.acentoOscuro else e.acentoClaro
        val sobre = if (Color.luminance(acento) > 0.5f) 0xFF111111.toInt() else Color.WHITE
        return if (oscuro) {
            val tarjeta = hsv(h, 0.32f * s, 0.15f)
            Paleta(true,
                fondo = hsv(h, 0.40f * s, 0.09f),
                tarjeta = if (foto) conAlfa(tarjeta, 0xD8) else tarjeta,
                tarjetaSolida = tarjeta,
                borde = hsv(h, 0.28f * s, 0.27f),
                texto = hsv(h, 0.06f * s, 0.94f),
                suave = hsv(h, 0.12f * s, 0.70f),
                cabecera = hsv(h, 0.50f * s, 0.20f),
                acento = acento, sobreAcento = sobre,
                peligro = 0xFFEF5350.toInt(), hayFoto = foto)
        } else {
            val tarjeta = hsv(h, 0.02f * s, 1.0f)
            Paleta(false,
                fondo = hsv(h, 0.07f * s, 0.97f),
                tarjeta = if (foto) conAlfa(tarjeta, 0xE6) else tarjeta,
                tarjetaSolida = tarjeta,
                borde = hsv(h, 0.16f * s, 0.88f),
                texto = hsv(h, 0.35f * s, 0.17f),
                suave = hsv(h, 0.20f * s, 0.42f),
                cabecera = hsv(h, 0.72f * s, 0.30f),
                acento = acento, sobreAcento = sobre,
                peligro = 0xFFC62828.toInt(), hayFoto = foto)
        }
    }

    // ------------------------------------------------------------------ foto de fondo

    /** Copia la foto elegida (ya reducida y con la rotación correcta) dentro de la app. */
    fun guardarFoto(ctx: Context, uri: Uri): Boolean {
        try {
            val cr = ctx.contentResolver
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            if (o.outWidth <= 0 || o.outHeight <= 0) return false
            var s = 1
            while (maxOf(o.outWidth, o.outHeight) / s > 2400) s *= 2
            val o2 = BitmapFactory.Options().apply { inSampleSize = s }
            var bmp: Bitmap = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
                ?: return false
            val orient = try {
                cr.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }
            val m = Matrix()
            when (orient) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                else -> {}
            }
            if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            val destino = File(ctx.filesDir, "fondo.jpg")
            destino.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun quitarFoto(ctx: Context) { File(ctx.filesDir, "fondo.jpg").delete() }

    /** Lee la foto guardada, reducida para no gastar memoria de más. */
    fun cargarFoto(f: File, maxLado: Int = 1920): Bitmap? {
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, o)
            var s = 1
            while (maxOf(o.outWidth, o.outHeight) / s > maxLado) s *= 2
            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
        } catch (e: Exception) { null }
    }
}
