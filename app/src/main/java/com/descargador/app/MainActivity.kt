package com.descargador.app

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        const val PROCESO = "descarga"
        const val SPLASH_MIN_MS = 1500L
        val RED = listOf("timed out", "Name or service", "Temporary failure", "Connection",
            "Network", "Errno", "HTTP Error 5", "IncompleteRead", "SSL", "Unable to download")
    }

    private val formatos = mapOf(
        false to listOf("mp4", "mkv", "webm"),
        true to listOf("mp3", "m4a", "opus", "flac", "wav"))
    private val calidades = linkedMapOf(
        "Mejor disponible" to 0, "1080p" to 1080, "720p" to 720, "480p" to 480, "360p" to 360)

    private lateinit var url: EditText
    private lateinit var rVideo: RadioButton
    private lateinit var rAudio: RadioButton
    private lateinit var fmt: Spinner
    private lateinit var cal: Spinner
    private lateinit var go: Button
    private lateinit var stop: Button
    private lateinit var barra: ProgressBar
    private lateinit var estado: TextView
    private lateinit var log: TextView
    private lateinit var initThread: Thread
    @Volatile private var initError: String? = null
    @Volatile private var cancelado = false

    // Interfaz nueva: cabecera, aviso de actualización y pantalla de carga
    private lateinit var headerLogo: ImageView
    private lateinit var banner: TextView
    private lateinit var splash: FrameLayout
    private lateinit var splashLogo: ImageView
    private lateinit var splashTextos: LinearLayout
    private var splashTerminado = false
    private var inicioMs = 0L
    private var nuevaVersion: Release? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        inicioMs = System.currentTimeMillis()
        // yt-dlp y ffmpeg se extraen la primera vez; se hace en segundo plano
        initThread = thread {
            try {
                YoutubeDL.getInstance().init(application)
                FFmpeg.getInstance().init(application)
            } catch (e: Exception) { initError = e.message ?: e.toString() }
        }

        val raiz = FrameLayout(this)
        raiz.addView(crearContenido(), FrameLayout.LayoutParams(-1, -1))
        raiz.addView(crearSplash(), FrameLayout.LayoutParams(-1, -1))
        setContentView(raiz)

        recibirLink(intent)
        esperarInicio()
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); recibirLink(i) }

    // ------------------------------------------------------------------ utilidades de interfaz

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun c(id: Int) = getColor(id)

    private fun margen(top: Int = 0, izq: Int = 0, der: Int = 0) =
        LinearLayout.LayoutParams(-1, -2).apply { setMargins(izq, top, der, 0) }

    private fun tarjeta(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), c(R.color.borde))
        }
    }

    private fun estilo(b: Button, fondo: Int, texto: Int, borde: Int = Color.TRANSPARENT) {
        fun forma() = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(fondo)
            if (borde != Color.TRANSPARENT) setStroke(dp(2), borde)
        }
        b.background = RippleDrawable(ColorStateList.valueOf(0x33000000), forma(), forma())
        b.setTextColor(texto)
        b.isAllCaps = false
        b.textSize = 16f
        b.minHeight = dp(48)
        b.stateListAnimator = null
    }

    private fun accion(icono: Int, texto: String, onClick: () -> Unit): LinearLayout {
        val celda = LinearLayout(this)
        celda.orientation = LinearLayout.VERTICAL
        celda.gravity = Gravity.CENTER
        celda.setPadding(dp(10), dp(4), dp(10), dp(4))
        celda.isClickable = true
        celda.contentDescription = texto
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        celda.setBackgroundResource(tv.resourceId)
        val img = ImageView(this)
        img.setImageResource(icono)
        celda.addView(img, LinearLayout.LayoutParams(dp(24), dp(24)))
        val t = TextView(this)
        t.text = texto
        t.textSize = 10f
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        celda.addView(t)
        celda.setOnClickListener { onClick() }
        return celda
    }

    // ------------------------------------------------------------------ pantalla principal

    private fun crearContenido(): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(c(R.color.fondo))
        col.addView(crearHeader(), LinearLayout.LayoutParams(-1, -2))

        banner = TextView(this)
        banner.visibility = View.GONE
        banner.textSize = 14f
        banner.setTextColor(Color.WHITE)
        banner.setBackgroundColor(c(R.color.naranja))
        banner.setPadding(dp(16), dp(10), dp(16), dp(10))
        banner.setOnClickListener { nuevaVersion?.let { dialogoActualizar(it) } }
        col.addView(banner, LinearLayout.LayoutParams(-1, -2))

        col.addView(crearFormulario(), LinearLayout.LayoutParams(-1, 0, 1f))
        return col
    }

    private fun crearHeader(): LinearLayout {
        val h = LinearLayout(this)
        h.orientation = LinearLayout.HORIZONTAL
        h.gravity = Gravity.CENTER_VERTICAL
        h.setBackgroundColor(c(R.color.teal_oscuro))
        h.setPadding(dp(12), dp(8), dp(6), dp(8))
        h.elevation = dp(4).toFloat()

        // Empieza invisible: el logo grande de la pantalla de carga "vuela" hasta aquí
        headerLogo = ImageView(this)
        headerLogo.setImageResource(R.drawable.logo)
        headerLogo.alpha = 0f
        headerLogo.contentDescription = "Logo de Descargador"
        h.addView(headerLogo, LinearLayout.LayoutParams(dp(44), dp(44)))

        val titulos = LinearLayout(this)
        titulos.orientation = LinearLayout.VERTICAL
        titulos.setPadding(dp(10), 0, 0, 0)
        val nombre = TextView(this)
        nombre.text = "Descargador"
        nombre.textSize = 20f
        nombre.typeface = Typeface.DEFAULT_BOLD
        nombre.setTextColor(Color.WHITE)
        val version = TextView(this)
        version.text = "versión ${Actualizador.versionActual(this)}"
        version.textSize = 12f
        version.setTextColor(0xCCFFFFFF.toInt())
        titulos.addView(nombre)
        titulos.addView(version)
        h.addView(titulos, LinearLayout.LayoutParams(0, -2, 1f))

        h.addView(accion(R.drawable.ic_actualizar, "Actualizar") { buscarActualizacion(manual = true) })
        h.addView(accion(R.drawable.ic_feedback, "Feedback") { dialogoFeedback() })
        return h
    }

    private fun crearFormulario(): ScrollView {
        val p = dp(16)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(p, p, p, p)

        // --- Tarjeta 1: qué descargar
        val t1 = tarjeta()

        url = EditText(this).apply { hint = "Pega aquí el link..."; setSingleLine() }
        val pegar = Button(this).apply { text = "Pegar"; setOnClickListener { pegarLink() } }
        estilo(pegar, c(R.color.teal), Color.WHITE)
        val filaUrl = LinearLayout(this)
        filaUrl.gravity = Gravity.CENTER_VERTICAL
        filaUrl.addView(url, LinearLayout.LayoutParams(0, -2, 1f))
        filaUrl.addView(pegar, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        t1.addView(filaUrl)

        val grupo = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        rVideo = RadioButton(this).apply { text = "Video"; id = View.generateViewId() }
        rAudio = RadioButton(this).apply { text = "Solo audio"; id = View.generateViewId() }
        grupo.addView(rVideo); grupo.addView(rAudio)
        rVideo.isChecked = true
        t1.addView(grupo, margen(top = dp(8)))

        fmt = Spinner(this)
        cal = Spinner(this)
        cal.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, calidades.keys.toList())
        t1.addView(fmt, margen(top = dp(4)))
        t1.addView(cal, margen(top = dp(4)))
        grupo.setOnCheckedChangeListener { _, _ -> cambiarModo() }
        cambiarModo()

        val filaBtn = LinearLayout(this)
        go = Button(this).apply { text = "Descargar"; setOnClickListener { iniciar() } }
        stop = Button(this).apply { text = "Cancelar"; isEnabled = false; alpha = 0.45f; setOnClickListener { cancelar() } }
        estilo(go, c(R.color.teal), Color.WHITE)
        estilo(stop, Color.WHITE, c(R.color.rojo), c(R.color.rojo))
        filaBtn.addView(go, LinearLayout.LayoutParams(0, -2, 1f))
        filaBtn.addView(stop, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        t1.addView(filaBtn, margen(top = dp(14)))

        root.addView(t1, LinearLayout.LayoutParams(-1, -2))

        // --- Tarjeta 2: progreso
        val t2 = tarjeta()
        barra = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        estado = TextView(this).apply { text = "Listo para descargar"; textSize = 15f; setTextColor(0xFF1F2D33.toInt()) }
        log = TextView(this).apply { textSize = 11f }
        t2.addView(barra)
        t2.addView(estado, margen(top = dp(6)))
        t2.addView(log, margen(top = dp(6)))
        root.addView(t2, margen(top = dp(12)))

        return ScrollView(this).apply { addView(root) }
    }

    // ------------------------------------------------------------------ pantalla de carga

    private fun crearSplash(): FrameLayout {
        splash = FrameLayout(this)
        splash.setBackgroundColor(c(R.color.splash))
        splash.isClickable = true          // bloquea toques mientras carga
        splash.clipChildren = false

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        col.clipChildren = false

        splashLogo = ImageView(this)
        splashLogo.setImageResource(R.drawable.logo)
        splashLogo.contentDescription = "Descargador"
        col.addView(splashLogo, LinearLayout.LayoutParams(dp(200), dp(200)))

        splashTextos = LinearLayout(this)
        splashTextos.orientation = LinearLayout.VERTICAL
        splashTextos.gravity = Gravity.CENTER_HORIZONTAL
        splashTextos.setPadding(dp(24), dp(20), dp(24), 0)
        val titulo = TextView(this)
        titulo.text = "Descargador"
        titulo.textSize = 26f
        titulo.typeface = Typeface.DEFAULT_BOLD
        titulo.setTextColor(Color.WHITE)
        titulo.gravity = Gravity.CENTER
        val rueda = ProgressBar(this)
        rueda.indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
        val msg = TextView(this)
        msg.text = "Preparando todo...\n(la primera vez tarda unos segundos)"
        msg.textSize = 13f
        msg.gravity = Gravity.CENTER
        msg.setTextColor(0xCCFFFFFF.toInt())
        splashTextos.addView(titulo)
        splashTextos.addView(rueda, LinearLayout.LayoutParams(dp(40), dp(40)).apply { topMargin = dp(16) })
        splashTextos.addView(msg, margen(top = dp(12)))
        col.addView(splashTextos, LinearLayout.LayoutParams(-1, -2))

        splash.addView(col, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        return splash
    }

    /** Espera a que yt-dlp termine de iniciar (mínimo 1,5 s para que se vea el logo) y quita la pantalla de carga. */
    private fun esperarInicio() {
        thread {
            initThread.join()
            val falta = SPLASH_MIN_MS - (System.currentTimeMillis() - inicioMs)
            if (falta > 0) Thread.sleep(falta)
            ui {
                initError?.let { estado.text = "No se pudo iniciar yt-dlp: $it" }
                terminarSplash()
                buscarActualizacion(manual = false)
            }
        }
    }

    /** El logo grande se achica y se acomoda en la esquina superior izquierda; el fondo se desvanece. */
    private fun terminarSplash() {
        if (splashTerminado) return
        splashTerminado = true
        if (headerLogo.width == 0 || splashLogo.width == 0) {
            splash.visibility = View.GONE; headerLogo.alpha = 1f; return
        }
        val a = IntArray(2); val d = IntArray(2)
        splashLogo.getLocationOnScreen(a)
        headerLogo.getLocationOnScreen(d)
        val escala = headerLogo.width.toFloat() / splashLogo.width
        splashLogo.pivotX = 0f
        splashLogo.pivotY = 0f

        splashTextos.animate().alpha(0f).setDuration(200).start()
        ValueAnimator.ofInt(255, 0).apply {
            duration = 550
            addUpdateListener { splash.background.alpha = it.animatedValue as Int }
            start()
        }
        splashLogo.animate()
            .translationX((d[0] - a[0]).toFloat())
            .translationY((d[1] - a[1]).toFloat())
            .scaleX(escala).scaleY(escala)
            .setDuration(550)
            .withEndAction { splash.visibility = View.GONE; headerLogo.alpha = 1f }
            .start()
    }

    // ------------------------------------------------------------------ feedback

    private fun dialogoFeedback() {
        val caja = EditText(this)
        caja.hint = "Cuéntanos qué pasó o qué mejorarías..."
        caja.minLines = 3
        caja.gravity = Gravity.TOP
        val marco = FrameLayout(this)
        marco.setPadding(dp(20), dp(8), dp(20), 0)
        marco.addView(caja, FrameLayout.LayoutParams(-1, -2))

        AlertDialog.Builder(this)
            .setTitle("Enviar feedback")
            .setMessage("Se adjuntan la versión de la app, tu modelo de celular y el último error (si hubo).")
            .setView(marco)
            .setPositiveButton("Por GitHub") { _, _ ->
                Feedback.abrirGithub(this, caja.text.toString(), log.text.toString())
            }
            .setNeutralButton("Compartir") { _, _ ->
                Feedback.compartir(this, caja.text.toString(), log.text.toString())
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ------------------------------------------------------------------ actualizar

    private fun buscarActualizacion(manual: Boolean) {
        if (manual) Toast.makeText(this, "Buscando actualizaciones...", Toast.LENGTH_SHORT).show()
        thread {
            val r = Actualizador.buscar(this)
            ui {
                when (r) {
                    is Resultado.Nueva -> {
                        nuevaVersion = r.release
                        banner.text = "⬇  Hay una versión nueva (${r.release.nombre}). Toca aquí para actualizar."
                        banner.visibility = View.VISIBLE
                        if (manual) dialogoActualizar(r.release)
                    }
                    is Resultado.AlDia -> {
                        banner.visibility = View.GONE
                        if (manual) Toast.makeText(this,
                            "Ya tienes la última versión (${Actualizador.versionActual(this)})", Toast.LENGTH_LONG).show()
                    }
                    is Resultado.Error -> {
                        if (manual) AlertDialog.Builder(this)
                            .setTitle("No se pudo buscar")
                            .setMessage(r.mensaje)
                            .setPositiveButton("Ok", null)
                            .show()
                    }
                }
            }
        }
    }

    private fun dialogoActualizar(r: Release) {
        val peso = if (r.bytes > 0) " (${r.bytes / 1_048_576} MB)" else ""
        AlertDialog.Builder(this)
            .setTitle("Versión ${r.nombre} disponible")
            .setMessage("Tienes la ${Actualizador.versionActual(this)}.$peso\n\n${r.notas.trim().take(600)}")
            .setPositiveButton("Actualizar") { _, _ ->
                if (Actualizador.puedeInstalar(this)) bajarEInstalar(r) else pedirPermisoInstalar()
            }
            .setNegativeButton("Ahora no", null)
            .show()
    }

    /** Android exige que la persona autorice a esta app a instalar actualizaciones (una sola vez). */
    private fun pedirPermisoInstalar() {
        AlertDialog.Builder(this)
            .setTitle("Un permiso, una sola vez")
            .setMessage("Para actualizarse, Descargador necesita permiso para instalar apps. " +
                "Se abrirá la configuración: activa \"Permitir desde esta fuente\", vuelve atrás y toca Actualizar otra vez.")
            .setPositiveButton("Abrir configuración") { _, _ ->
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun bajarEInstalar(r: Release) {
        val barraDlg = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val texto = TextView(this).apply { text = "Descargando la actualización..." }
        val cont = LinearLayout(this)
        cont.orientation = LinearLayout.VERTICAL
        cont.setPadding(dp(24), dp(16), dp(24), dp(8))
        cont.addView(texto)
        cont.addView(barraDlg)
        val cancel = AtomicBoolean(false)
        val dlg = AlertDialog.Builder(this)
            .setTitle("Actualizando")
            .setView(cont)
            .setCancelable(false)
            .setNegativeButton("Cancelar") { _, _ -> cancel.set(true) }
            .show()

        thread {
            try {
                val apk = Actualizador.descargar(this, r,
                    progreso = { pr -> ui { barraDlg.progress = pr; texto.text = "Descargando... $pr%" } },
                    cancelado = { cancel.get() })
                ui { dlg.dismiss() }
                Actualizador.instalar(this, apk)
            } catch (e: Exception) {
                ui {
                    dlg.dismiss()
                    if (!cancel.get()) AlertDialog.Builder(this)
                        .setTitle("No se pudo actualizar")
                        .setMessage((e.message ?: e.toString()).take(300))
                        .setPositiveButton("Ok", null)
                        .show()
                }
            }
        }
    }

    // ------------------------------------------------------------------ descarga (lógica original)

    private fun recibirLink(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
            Regex("https?://\\S+").find(t)?.let { url.setText(it.value) }
        }
    }

    private fun pegarLink() {
        val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val t = cb.primaryClip?.getItemAt(0)?.text?.toString() ?: return
        Regex("https?://\\S+").find(t)?.let { url.setText(it.value) }
    }

    private fun cambiarModo() {
        val audio = rAudio.isChecked
        fmt.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, formatos[audio]!!)
        cal.visibility = if (audio) View.GONE else View.VISIBLE
    }

    private fun ui(f: () -> Unit) = runOnUiThread { f() }

    private fun ocupado(si: Boolean) {
        go.isEnabled = !si; stop.isEnabled = si
        go.alpha = if (si) 0.45f else 1f
        stop.alpha = if (si) 1f else 0.45f
        if (si) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun iniciar() {
        val link = Regex("https?://\\S+").find(url.text.toString())?.value
        if (link == null) { estado.text = "Pega un link válido (empieza con http)"; return }
        val audio = rAudio.isChecked
        val f = fmt.selectedItem as String
        val h = calidades[cal.selectedItem as String] ?: 0
        cancelado = false; ocupado(true); barra.progress = 0; log.text = ""
        thread { trabajo(link, audio, f, h) }
    }

    private fun cancelar() {
        cancelado = true
        YoutubeDL.getInstance().destroyProcessById(PROCESO)
    }

    private fun pedido(link: String, audio: Boolean, f: String, h: Int, tmp: File) =
        YoutubeDLRequest(link).apply {
            addOption("--no-playlist")
            addOption("--retries", "infinite")          // reintenta si el internet se corta
            addOption("--fragment-retries", "infinite")
            addOption("--retry-sleep", "3")
            addOption("--socket-timeout", "30")
            addOption("--no-mtime")
            addOption("-o", tmp.absolutePath + "/%(title).80s [%(id)s].%(ext)s")
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

    private fun trabajo(link: String, audio: Boolean, f: String, h: Int) {
        val tmp = File(getExternalFilesDir(null), "tmp").apply { deleteRecursively(); mkdirs() }
        try {
            ui { estado.text = "Preparando (la primera vez tarda unos segundos)..." }
            initThread.join()
            initError?.let { throw RuntimeException("No se pudo iniciar yt-dlp: $it") }

            var ok = false
            var intento = 0
            while (!ok && !cancelado && intento < 30) {   // yt-dlp reanuda lo ya bajado
                intento++
                try {
                    YoutubeDL.getInstance().execute(
                        request = pedido(link, audio, f, h, tmp),
                        processId = PROCESO,
                        callback = { prog, eta, _ ->
                            ui {
                                barra.progress = prog.toInt().coerceIn(0, 100)
                                estado.text = "${prog.toInt()}%  -  quedan ${eta}s"
                            }
                        })
                    ok = true
                } catch (e: Exception) {
                    if (cancelado) break
                    val m = e.message ?: ""
                    ui { log.append(m.take(300) + "\n") }
                    if (RED.none { m.contains(it, ignoreCase = true) }) throw e
                    for (s in 8 downTo 1) {
                        if (cancelado) break
                        ui { estado.text = "Sin conexión. Reintentando en ${s}s (intento $intento)..." }
                        Thread.sleep(1000)
                    }
                }
            }
            when {
                ok -> { val n = guardar(tmp); ui { barra.progress = 100; estado.text = "¡Listo! $n archivo(s) en Descargas/Descargador" } }
                cancelado -> ui { estado.text = "Cancelado" }
                else -> ui { estado.text = "No se pudo completar tras varios intentos" }
            }
        } catch (e: Exception) {
            ui { log.append((e.message ?: e.toString()).take(500) + "\n"); estado.text = "Falló. Revisa el detalle abajo." }
        } finally {
            ui { ocupado(false) }
        }
    }

    /** Mueve los archivos terminados a Descargas/Descargador (visible en cualquier gestor de archivos). */
    private fun guardar(tmp: File): Int {
        var n = 0
        tmp.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }?.forEach { f ->
            val mime = when (f.extension.lowercase()) {
                "mp4" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"
                "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "opus" -> "audio/ogg"
                "flac" -> "audio/flac"; "wav" -> "audio/wav"; else -> "application/octet-stream"
            }
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/Descargador")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return@forEach
            contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            f.delete(); n++
        }
        return n
    }
}
