package com.descargador.app

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        const val SPLASH_MIN_MS = 1500L
        const val REQ_FOTO = 41
        const val REQ_AVISOS = 42
        /** Para no repetir la pantalla de carga cuando la pantalla se recrea (por ejemplo al cambiar de tema). */
        @Volatile var splashMostrado = false
    }

    private val formatos = mapOf(
        false to listOf("mp4", "mkv", "webm"),
        true to listOf("mp3", "m4a", "opus", "flac", "wav"))
    private val calidades = linkedMapOf(
        "Mejor disponible" to 0, "1080p" to 1080, "720p" to 720, "480p" to 480, "360p" to 360)

    // --- apariencia
    private lateinit var p: Temas.Paleta
    private val aplicadores = ArrayList<() -> Unit>()
    private val aplicadoresRes = ArrayList<() -> Unit>()
    private lateinit var raiz: FrameLayout
    private lateinit var fondoFoto: ImageView
    private lateinit var velo: View
    private var fotoBmp: Bitmap? = null
    private var fotoMod = 0L
    private var dialogoRefrescos: ArrayList<() -> Unit>? = null

    // --- pantalla
    private lateinit var url: EditText
    private lateinit var rVideo: RadioButton
    private lateinit var rAudio: RadioButton
    private lateinit var chkLista: CheckBox
    private lateinit var fmt: Spinner
    private lateinit var cal: Spinner
    private lateinit var go: Button
    private lateinit var stop: Button
    private lateinit var barra: ProgressBar
    private lateinit var estado: TextView
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var tarjetaRes: LinearLayout
    private lateinit var tarjetaOpc: LinearLayout
    private lateinit var tarjetaProg: LinearLayout
    private lateinit var msgBusqueda: TextView
    private lateinit var resultados: LinearLayout

    // --- cabecera, aviso de actualización y pantalla de carga
    private lateinit var headerLogo: ImageView
    private lateinit var banner: TextView
    private lateinit var splash: FrameLayout
    private lateinit var splashLogo: ImageView
    private lateinit var splashTextos: LinearLayout
    private var splashTerminado = false
    private var inicioMs = 0L
    private var nuevaVersion: Release? = null

    // --- búsqueda y permiso de avisos
    private var fuente = Fuente.YOUTUBE
    private var busquedaId = 0
    private var despuesDePermiso: (() -> Unit)? = null

    override fun onCreate(b: Bundle?) {
        setTheme(if (Temas.esOscuro(this)) R.style.Theme_DescargadorOscuro else R.style.Theme_Descargador)
        super.onCreate(b)
        inicioMs = System.currentTimeMillis()
        Motor.iniciar(application)     // yt-dlp y ffmpeg arrancan en segundo plano
        p = Temas.paleta(this)

        raiz = FrameLayout(this)
        fondoFoto = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        velo = View(this)
        raiz.addView(fondoFoto, FrameLayout.LayoutParams(-1, -1))
        raiz.addView(velo, FrameLayout.LayoutParams(-1, -1))
        raiz.addView(crearContenido(), FrameLayout.LayoutParams(-1, -1))
        val conSplash = !splashMostrado
        if (conSplash) raiz.addView(crearSplash(), FrameLayout.LayoutParams(-1, -1))
        setContentView(raiz)
        aplicarTema()

        recibirLink(intent)
        if (conSplash) esperarInicio()
        else {
            splashTerminado = true
            headerLogo.alpha = 1f
            buscarActualizacion(manual = false)
        }
    }

    override fun onStart() {
        super.onStart()
        DescargaEstado.oyente = { runOnUiThread { pintarEstado() } }
        pintarEstado()
    }

    override fun onStop() {
        DescargaEstado.oyente = null
        super.onStop()
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); recibirLink(i) }

    // ------------------------------------------------------------------ apariencia

    /** Aplica un color a una vista ahora y cada vez que cambie el tema. */
    private fun <T : View> T.reg(f: T.() -> Unit): T { f(); aplicadores.add { f() }; return this }
    private fun <T : View> T.regR(f: T.() -> Unit): T { f(); aplicadoresRes.add { f() }; return this }

    private fun repintar() {
        aplicadores.forEach { it() }
        aplicadoresRes.forEach { it() }
    }

    /** Lee la paleta guardada (estilo, modo, color de botones, foto) y la aplica a toda la pantalla. */
    private fun aplicarTema() {
        p = Temas.paleta(this)
        window.statusBarColor = p.cabecera
        raiz.setBackgroundColor(p.fondo)
        val foto = Temas.archivoFoto(this)
        if (foto != null && (fotoBmp == null || fotoMod != foto.lastModified())) {
            fotoBmp = Temas.cargarFoto(foto)
            fotoMod = foto.lastModified()
        }
        val bmp = if (foto != null) fotoBmp else null
        if (bmp != null) {
            fondoFoto.setImageBitmap(bmp)
            fondoFoto.visibility = View.VISIBLE
            val alfa = (Temas.velo(this) * 255) / 100
            velo.setBackgroundColor((alfa shl 24) or (p.fondo and 0xFFFFFF))
        } else {
            fondoFoto.visibility = View.GONE
            velo.setBackgroundColor(Color.TRANSPARENT)
        }
        repintar()
    }

    private fun pintarBoton(b: Button, fondo: Int, texto: Int, borde: Int) {
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

    private fun pintarChip(t: TextView, sel: Boolean) {
        t.background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            if (sel) setColor(p.acento) else { setColor(Color.TRANSPARENT); setStroke(dp(1), p.suave) }
        }
        t.setTextColor(if (sel) p.sobreAcento else p.texto)
    }

    private fun chip(texto: String, sel: () -> Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = texto
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isClickable = true
            setOnClickListener { onClick() }
        }.reg { pintarChip(this, sel()) }

    private fun adaptador(items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, items) {
            override fun getView(pos: Int, v: View?, parent: ViewGroup): View {
                val t = super.getView(pos, v, parent) as TextView
                t.setTextColor(p.texto)
                return t
            }
            override fun getDropDownView(pos: Int, v: View?, parent: ViewGroup): View {
                val t = super.getDropDownView(pos, v, parent) as TextView
                t.setTextColor(p.texto)
                t.setBackgroundColor(p.tarjetaSolida)
                t.setPadding(dp(16), dp(12), dp(16), dp(12))
                return t
            }
        }

    private fun rellenar(sp: Spinner, items: List<String>, resetear: Boolean = false) {
        val sel = if (resetear) 0 else sp.selectedItemPosition.coerceIn(0, items.size - 1)
        sp.adapter = adaptador(items)
        sp.setSelection(sel)
    }

    // ------------------------------------------------------------------ utilidades de interfaz

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun margen(top: Int = 0, izq: Int = 0, der: Int = 0) =
        LinearLayout.LayoutParams(-1, -2).apply { setMargins(izq, top, der, 0) }

    private fun tarjeta(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }.reg {
        background = GradientDrawable().apply {
            setColor(p.tarjeta)
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), p.borde)
        }
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
        t.maxLines = 1
        // Importante: con ancho propio; si no, el texto se recorta al ancho del ícono ("Actuali")
        celda.addView(t, LinearLayout.LayoutParams(-2, -2))
        celda.setOnClickListener { onClick() }
        return celda
    }

    private fun ocultarTeclado() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(url.windowToken, 0)
    }

    private fun ui(f: () -> Unit) = runOnUiThread { f() }

    // ------------------------------------------------------------------ pantalla principal

    private fun crearContenido(): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(crearHeader(), LinearLayout.LayoutParams(-1, -2))

        banner = TextView(this)
        banner.visibility = View.GONE
        banner.textSize = 14f
        banner.setTextColor(Color.WHITE)
        banner.setBackgroundColor(0xFFE8742A.toInt())
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
        h.setPadding(dp(12), dp(8), dp(6), dp(8))
        h.elevation = dp(4).toFloat()
        h.reg { setBackgroundColor(p.cabecera) }

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
        titulos.addView(nombre, LinearLayout.LayoutParams(-2, -2))
        titulos.addView(version, LinearLayout.LayoutParams(-2, -2))
        h.addView(titulos, LinearLayout.LayoutParams(0, -2, 1f))

        h.addView(accion(R.drawable.ic_paleta, "Tema") { dialogoApariencia() })
        h.addView(accion(R.drawable.ic_actualizar, "Actualizar") { buscarActualizacion(manual = true) })
        h.addView(accion(R.drawable.ic_feedback, "Feedback") { dialogoFeedback() })
        return h
    }

    private fun crearFormulario(): ScrollView {
        val pad = dp(16)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)

        // --- Tarjeta 1: pegar un link o buscar
        val t1 = tarjeta()
        url = EditText(this).apply {
            hint = "Pega un link o escribe para buscar..."
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, accion, _ ->
                if (accion == EditorInfo.IME_ACTION_SEARCH) { accionEnter(); true } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (Regex("playlist\\?list=|/sets/").containsMatchIn(s?.toString() ?: "")) chkLista.isChecked = true
                }
            })
        }.reg {
            setTextColor(p.texto)
            setHintTextColor(p.suave)
            backgroundTintList = ColorStateList.valueOf(p.acento)
        }
        t1.addView(url, LinearLayout.LayoutParams(-1, -2))

        val btnPegar = Button(this).apply { text = "Pegar"; setOnClickListener { pegarLink() } }
            .reg { pintarBoton(this, Color.TRANSPARENT, p.acento, p.acento) }
        val btnBuscar = Button(this).apply { text = "Buscar"; setOnClickListener { buscar() } }
            .reg { pintarBoton(this, p.acento, p.sobreAcento, Color.TRANSPARENT) }
        val filaBtn1 = LinearLayout(this)
        filaBtn1.addView(btnPegar, LinearLayout.LayoutParams(0, -2, 1f))
        filaBtn1.addView(btnBuscar, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        t1.addView(filaBtn1, margen(top = dp(8)))

        val lblFuente = TextView(this).apply { text = "Buscar en:"; textSize = 12f }
            .reg { setTextColor(p.suave) }
        t1.addView(lblFuente, margen(top = dp(12)))
        val chips = LinearLayout(this)
        for (f in Fuente.values()) {
            chips.addView(chip(f.etiqueta, { fuente == f }, { fuente = f; repintar() }),
                LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        }
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.addView(chips)
        t1.addView(hs, margen(top = dp(6)))
        root.addView(t1, LinearLayout.LayoutParams(-1, -2))

        // --- Tarjeta 2: resultados de la búsqueda (aparece al buscar)
        tarjetaRes = tarjeta()
        tarjetaRes.visibility = View.GONE
        val tituloRes = TextView(this).apply { text = "Resultados"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD }
            .reg { setTextColor(p.texto) }
        msgBusqueda = TextView(this).apply { textSize = 14f }.reg { setTextColor(p.suave) }
        resultados = LinearLayout(this)
        resultados.orientation = LinearLayout.VERTICAL
        tarjetaRes.addView(tituloRes, LinearLayout.LayoutParams(-2, -2))
        tarjetaRes.addView(msgBusqueda, margen(top = dp(6)))
        tarjetaRes.addView(resultados, margen(top = dp(4)))
        root.addView(tarjetaRes, margen(top = dp(12)))

        // --- Tarjeta 3: opciones y botón de descarga
        tarjetaOpc = tarjeta()
        val grupo = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        rVideo = RadioButton(this).apply { text = "Video"; id = View.generateViewId() }.reg {
            setTextColor(p.texto); buttonTintList = ColorStateList.valueOf(p.acento)
        }
        rAudio = RadioButton(this).apply { text = "Solo audio"; id = View.generateViewId() }.reg {
            setTextColor(p.texto); buttonTintList = ColorStateList.valueOf(p.acento)
        }
        grupo.addView(rVideo); grupo.addView(rAudio)
        rVideo.isChecked = true
        tarjetaOpc.addView(grupo, margen())

        fmt = Spinner(this).reg { rellenar(this, formatos[rAudio.isChecked]!!) }
        cal = Spinner(this).reg { rellenar(this, calidades.keys.toList()) }
        tarjetaOpc.addView(fmt, margen(top = dp(4)))
        tarjetaOpc.addView(cal, margen(top = dp(4)))
        grupo.setOnCheckedChangeListener { _, _ -> cambiarModo() }

        chkLista = CheckBox(this).apply { text = "Descargar toda la lista (playlist)" }.reg {
            setTextColor(p.texto); buttonTintList = ColorStateList.valueOf(p.acento)
        }
        tarjetaOpc.addView(chkLista, margen(top = dp(4)))

        go = Button(this).apply { text = "Descargar"; setOnClickListener { iniciar() } }
            .reg { pintarBoton(this, p.acento, p.sobreAcento, Color.TRANSPARENT) }
        stop = Button(this).apply { text = "Cancelar"; isEnabled = false; alpha = 0.45f; setOnClickListener { cancelar() } }
            .reg { pintarBoton(this, Color.TRANSPARENT, p.peligro, p.peligro) }
        val filaBtn = LinearLayout(this)
        filaBtn.addView(go, LinearLayout.LayoutParams(0, -2, 1f))
        filaBtn.addView(stop, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        tarjetaOpc.addView(filaBtn, margen(top = dp(14)))
        root.addView(tarjetaOpc, margen(top = dp(12)))

        // --- Tarjeta 4: progreso
        tarjetaProg = tarjeta()
        barra = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }.reg {
            progressTintList = ColorStateList.valueOf(p.acento)
            progressBackgroundTintList = ColorStateList.valueOf(p.borde)
            indeterminateTintList = ColorStateList.valueOf(p.acento)
        }
        estado = TextView(this).apply { text = DescargaEstado.estado; textSize = 15f }.reg { setTextColor(p.texto) }
        log = TextView(this).apply { textSize = 11f }.reg { setTextColor(p.suave) }
        tarjetaProg.addView(barra, margen())
        tarjetaProg.addView(estado, margen(top = dp(6)))
        tarjetaProg.addView(log, margen(top = dp(6)))
        root.addView(tarjetaProg, margen(top = dp(12)))

        scroll = ScrollView(this)
        scroll.addView(root)
        return scroll
    }

    // ------------------------------------------------------------------ pantalla de carga

    private fun crearSplash(): FrameLayout {
        splash = FrameLayout(this)
        splash.setBackgroundColor(getColor(R.color.splash))
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
        splashTextos.addView(titulo, margen())
        splashTextos.addView(rueda, LinearLayout.LayoutParams(dp(40), dp(40)).apply { topMargin = dp(16) })
        splashTextos.addView(msg, margen(top = dp(12)))
        col.addView(splashTextos, LinearLayout.LayoutParams(-1, -2))

        splash.addView(col, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        return splash
    }

    /** Espera a que yt-dlp termine de iniciar (mínimo 1,5 s para que se vea el logo) y quita la pantalla de carga. */
    private fun esperarInicio() {
        thread {
            val error = Motor.esperarInicio()
            val falta = SPLASH_MIN_MS - (System.currentTimeMillis() - inicioMs)
            if (falta > 0) Thread.sleep(falta)
            ui {
                if (error != null && !DescargaEstado.activa) {
                    DescargaEstado.estado = "No se pudo iniciar yt-dlp: $error"
                    pintarEstado()
                }
                terminarSplash()
                buscarActualizacion(manual = false)
            }
        }
    }

    /** El logo grande se achica y se acomoda en la esquina superior izquierda; el fondo se desvanece. */
    private fun terminarSplash() {
        if (splashTerminado) return
        splashTerminado = true
        splashMostrado = true
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

    // ------------------------------------------------------------------ apariencia: diálogo

    private fun elegirFoto() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(Intent.createChooser(i, "Elige una foto"), REQ_FOTO)
        } catch (e: Exception) {
            Toast.makeText(this, "No encontré una app de galería", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_FOTO && res == RESULT_OK) {
            val uri = data?.data ?: return
            Toast.makeText(this, "Cargando foto...", Toast.LENGTH_SHORT).show()
            thread {
                val ok = Temas.guardarFoto(this, uri)
                ui {
                    if (ok) {
                        fotoBmp = null
                        aplicarTema()
                        dialogoRefrescos?.forEach { it() }
                    } else {
                        Toast.makeText(this, "No pude usar esa foto, prueba con otra", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun dialogoApariencia() {
        val cx = ContextThemeWrapper(this,
            if (p.oscuro) R.style.Theme_DescargadorOscuro else R.style.Theme_Descargador)
        val refrescos = ArrayList<() -> Unit>()
        dialogoRefrescos = refrescos
        var dlg: AlertDialog? = null

        fun subtitulo(t: String) = TextView(cx).apply {
            text = t; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(16), 0, dp(6))
        }

        fun chipD(texto: String, sel: () -> Boolean, onClick: () -> Unit): TextView {
            val t = TextView(cx)
            t.text = texto
            t.textSize = 13f
            t.gravity = Gravity.CENTER
            t.setPadding(dp(14), dp(8), dp(14), dp(8))
            t.isClickable = true
            val pintar = { pintarChip(t, sel()) }
            pintar()
            refrescos.add(pintar)
            t.setOnClickListener { onClick(); refrescos.forEach { it() } }
            return t
        }

        val col = LinearLayout(cx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(4), dp(20), dp(8))

        // --- Modo
        col.addView(subtitulo("Modo"))
        val filaModo = LinearLayout(cx)
        listOf("claro" to "Claro", "oscuro" to "Oscuro", "auto" to "Automático").forEach { (id, nombre) ->
            filaModo.addView(chipD(nombre, { Temas.modo(this) == id }) {
                if (Temas.modo(this) != id) {
                    Temas.poner(this, "modo", id)
                    aplicarTema()
                    dlg?.dismiss()
                    dialogoApariencia()     // se vuelve a abrir con los colores del modo nuevo
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        }
        col.addView(filaModo)

        // --- Estilo
        col.addView(subtitulo("Estilo"))
        val estilos = Temas.estilos
        for (fila in estilos.chunked(3)) {
            val f = LinearLayout(cx)
            for (e in fila) {
                val pp = Temas.paleta(this, e, p.oscuro)
                val caja = LinearLayout(cx)
                caja.orientation = LinearLayout.VERTICAL
                caja.clipToOutline = true
                caja.outlineProvider = ViewOutlineProvider.BACKGROUND
                val franja = View(cx)
                franja.setBackgroundColor(pp.cabecera)
                caja.addView(franja, LinearLayout.LayoutParams(-1, dp(22)))
                val nombre = TextView(cx)
                nombre.text = e.nombre
                nombre.textSize = 12f
                nombre.maxLines = 1
                nombre.ellipsize = TextUtils.TruncateAt.END
                nombre.setTextColor(pp.texto)
                nombre.setPadding(dp(8), dp(8), dp(8), dp(4))
                caja.addView(nombre, LinearLayout.LayoutParams(-1, -2))
                val punto = View(cx)
                punto.setBackgroundColor(if (p.oscuro) e.acentoOscuro else e.acentoClaro)
                caja.addView(punto, LinearLayout.LayoutParams(dp(28), dp(6)).apply {
                    leftMargin = dp(8); bottomMargin = dp(8)
                })
                val pintarCaja = {
                    caja.background = GradientDrawable().apply {
                        setColor(pp.fondo)
                        cornerRadius = dp(12).toFloat()
                        if (Temas.estiloId(this@MainActivity) == e.id) setStroke(dp(3), p.acento)
                        else setStroke(dp(1), pp.borde)
                    }
                }
                pintarCaja()
                refrescos.add(pintarCaja)
                caja.setOnClickListener {
                    Temas.poner(this, "estilo", e.id)
                    aplicarTema()
                    refrescos.forEach { it() }
                }
                f.addView(caja, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            repeat(3 - fila.size) { f.addView(View(cx), LinearLayout.LayoutParams(0, 1, 1f)) }
            col.addView(f, LinearLayout.LayoutParams(-1, -2))
        }

        // --- Color de los botones
        col.addView(subtitulo("Color de los botones"))
        for (grupoColores in Temas.acentos.toList().chunked(5)) {
            val f = LinearLayout(cx)
            for (color in grupoColores) {
                val t = TextView(cx)
                t.gravity = Gravity.CENTER
                t.textSize = 16f
                t.isClickable = true
                val pintar = {
                    val real = if (color != 0) color else Temas.estilo(this).let { if (p.oscuro) it.acentoOscuro else it.acentoClaro }
                    val elegido = Temas.acentoElegido(this) == color
                    t.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(real)
                        setStroke(dp(1), p.suave)
                    }
                    t.text = if (elegido) "✓" else if (color == 0) "A" else ""
                    t.setTextColor(if (Color.luminance(real) > 0.5f) 0xFF111111.toInt() else Color.WHITE)
                }
                pintar()
                refrescos.add(pintar)
                t.contentDescription = if (color == 0) "Color del estilo" else "Color de botones"
                t.setOnClickListener {
                    Temas.poner(this, "acento", color)
                    aplicarTema()
                    refrescos.forEach { it() }
                }
                f.addView(t, LinearLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(dp(4), dp(4), dp(10), dp(4)) })
            }
            col.addView(f)
        }
        val lblOtro = TextView(cx)
        lblOtro.text = "Otro color:"
        lblOtro.textSize = 12f
        lblOtro.setPadding(0, dp(8), 0, 0)
        col.addView(lblOtro)
        val tono = SeekBar(cx)
        tono.max = 360
        val arcoiris = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 0.7f, 0.85f)) }
        tono.progressDrawable = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, arcoiris).apply {
            cornerRadius = dp(4).toFloat()
            setSize(dp(100), dp(8))
        }
        tono.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, deDedo: Boolean) {
                if (!deDedo) return
                Temas.poner(this@MainActivity, "acento",
                    Color.HSVToColor(floatArrayOf(v.coerceAtMost(359).toFloat(), 0.70f, 0.85f)))
                aplicarTema()
                refrescos.forEach { it() }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        col.addView(tono, LinearLayout.LayoutParams(-1, -2))

        // --- Foto de fondo
        col.addView(subtitulo("Foto de fondo"))
        val filaFoto = LinearLayout(cx)
        val bElegir = Button(cx)
        bElegir.text = "Elegir de la galería"
        bElegir.isAllCaps = false
        bElegir.setOnClickListener { elegirFoto() }
        val bQuitar = Button(cx)
        bQuitar.text = "Quitar"
        bQuitar.isAllCaps = false
        bQuitar.setOnClickListener {
            Temas.quitarFoto(this)
            fotoBmp = null
            aplicarTema()
            refrescos.forEach { it() }
        }
        filaFoto.addView(bElegir, LinearLayout.LayoutParams(0, -2, 1f))
        filaFoto.addView(bQuitar, LinearLayout.LayoutParams(-2, -2))
        col.addView(filaFoto)

        val lblVelo = TextView(cx)
        lblVelo.text = "Cuánto cubrir la foto (para que se lea mejor):"
        lblVelo.textSize = 12f
        lblVelo.setPadding(0, dp(8), 0, 0)
        val veloBar = SeekBar(cx)
        veloBar.max = 90
        veloBar.progress = Temas.velo(this)
        veloBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, deDedo: Boolean) {
                if (!deDedo) return
                Temas.poner(this@MainActivity, "velo", v)
                aplicarTema()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        col.addView(lblVelo)
        col.addView(veloBar, LinearLayout.LayoutParams(-1, -2))
        refrescos.add {
            val hay = Temas.archivoFoto(this) != null
            val v = if (hay) View.VISIBLE else View.GONE
            bQuitar.visibility = v; lblVelo.visibility = v; veloBar.visibility = v
        }
        refrescos.forEach { it() }

        val sv = ScrollView(cx)
        sv.addView(col)
        val d = AlertDialog.Builder(cx)
            .setTitle("Apariencia")
            .setView(sv)
            .setPositiveButton("Listo", null)
            .setNeutralButton("Restablecer", null)
            .create()
        dlg = d
        d.setOnDismissListener { if (dialogoRefrescos === refrescos) dialogoRefrescos = null }
        d.show()
        // Se asigna después de mostrarlo para que "Restablecer" no cierre el diálogo por sí solo
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            Temas.restablecer(this)
            fotoBmp = null
            aplicarTema()
            d.dismiss()
            dialogoApariencia()
        }
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
        cont.addView(texto, margen())
        cont.addView(barraDlg, margen())
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

    // ------------------------------------------------------------------ links y búsqueda

    private fun recibirLink(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
            Regex("https?://\\S+").find(t)?.let { url.setText(it.value) }
        }
    }

    private fun pegarLink() {
        val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val t = cb.primaryClip?.getItemAt(0)?.text?.toString() ?: return
        val link = Regex("https?://\\S+").find(t)?.value
        // Si lo copiado no es un link, se pega igual: sirve para buscar por texto
        url.setText(link ?: t.trim())
    }

    private fun cambiarModo() {
        val audio = rAudio.isChecked
        rellenar(fmt, formatos[audio]!!, resetear = true)
        cal.visibility = if (audio) View.GONE else View.VISIBLE
    }

    private fun accionEnter() {
        if (Regex("https?://\\S+").containsMatchIn(url.text)) iniciar() else buscar()
    }

    private fun buscar() {
        val q = url.text.toString().trim()
        if (q.isEmpty()) {
            Toast.makeText(this, "Escribe qué quieres buscar", Toast.LENGTH_SHORT).show(); return
        }
        if (Regex("^https?://\\S+$").matches(q)) {
            Toast.makeText(this, "Eso es un link: toca Descargar", Toast.LENGTH_SHORT).show(); return
        }
        ocultarTeclado()
        val id = ++busquedaId
        Motor.cancelarBusqueda()
        val f = fuente
        tarjetaRes.visibility = View.VISIBLE
        resultados.removeAllViews()
        aplicadoresRes.clear()
        msgBusqueda.visibility = View.VISIBLE
        msgBusqueda.text = "Buscando \"$q\" en ${f.etiqueta}..."
        scroll.post { scroll.smoothScrollTo(0, tarjetaRes.top) }
        thread {
            try {
                val r = Busqueda.buscar(f, q)
                ui {
                    if (id == busquedaId) {
                        if (r.isEmpty()) msgBusqueda.text = "No encontré resultados para \"$q\" en ${f.etiqueta}."
                        else { msgBusqueda.visibility = View.GONE; mostrarResultados(r) }
                    }
                }
            } catch (e: Exception) {
                ui {
                    if (id == busquedaId) msgBusqueda.text = "No se pudo buscar: " +
                        Motor.resumen(e.message ?: e.toString()) + "\n(Revisa tu internet o prueba otra fuente)"
                }
            }
        }
    }

    private fun mostrarResultados(lista: List<Hallazgo>) {
        resultados.removeAllViews()
        aplicadoresRes.clear()
        for (h in lista) resultados.addView(filaResultado(h), LinearLayout.LayoutParams(-1, -2))
    }

    private fun filaResultado(h: Hallazgo): View {
        val fila = LinearLayout(this)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.gravity = Gravity.CENTER_VERTICAL
        fila.setPadding(0, dp(6), 0, dp(6))
        fila.isClickable = true
        fila.setOnClickListener { elegir(h, false) }

        val mini = ImageView(this)
        mini.scaleType = ImageView.ScaleType.CENTER_CROP
        mini.clipToOutline = true
        mini.outlineProvider = ViewOutlineProvider.BACKGROUND
        mini.regR { background = GradientDrawable().apply { setColor(p.borde); cornerRadius = dp(8).toFloat() } }
        Miniaturas.cargar(h.miniatura, mini)
        fila.addView(mini, LinearLayout.LayoutParams(dp(112), dp(63)))

        val textos = LinearLayout(this)
        textos.orientation = LinearLayout.VERTICAL
        textos.setPadding(dp(10), 0, dp(6), 0)
        val titulo = TextView(this)
        titulo.text = h.titulo
        titulo.textSize = 14f
        titulo.typeface = Typeface.DEFAULT_BOLD
        titulo.maxLines = 2
        titulo.ellipsize = TextUtils.TruncateAt.END
        titulo.regR { setTextColor(p.texto) }
        val lista = if (h.esLista) "LISTA" + (if (h.cantidad > 0) " · ${h.cantidad} videos" else "") else ""
        val sub = TextView(this)
        sub.text = listOf(lista, h.autor, h.duracion).filter { it.isNotBlank() }.joinToString(" · ")
        sub.textSize = 12f
        sub.maxLines = 1
        sub.ellipsize = TextUtils.TruncateAt.END
        sub.regR { setTextColor(p.suave) }
        textos.addView(titulo, LinearLayout.LayoutParams(-2, -2))
        textos.addView(sub, LinearLayout.LayoutParams(-2, -2))
        fila.addView(textos, LinearLayout.LayoutParams(0, -2, 1f))

        val bajar = ImageView(this)
        bajar.setImageResource(R.drawable.ic_bajar)
        bajar.setPadding(dp(10), dp(10), dp(10), dp(10))
        bajar.contentDescription = "Descargar"
        bajar.isClickable = true
        bajar.setOnClickListener { elegir(h, true) }
        bajar.regR { setColorFilter(p.acento, PorterDuff.Mode.SRC_IN) }
        fila.addView(bajar, LinearLayout.LayoutParams(dp(44), dp(44)))
        return fila
    }

    /** Toca un resultado: pone su link y, si se pide, empieza a descargar con las opciones actuales. */
    private fun elegir(h: Hallazgo, ya: Boolean) {
        url.setText(h.url)
        chkLista.isChecked = h.esLista
        if (ya) iniciar()
        else {
            Toast.makeText(this, "Link elegido. Ajusta el formato y toca Descargar", Toast.LENGTH_SHORT).show()
            scroll.post { scroll.smoothScrollTo(0, tarjetaOpc.top) }
        }
    }

    // ------------------------------------------------------------------ descarga (la hace el servicio)

    private fun pintarEstado() {
        val s = DescargaEstado
        barra.isIndeterminate = s.activa && s.indeterminado
        barra.progress = s.progreso
        estado.text = s.estado
        log.text = s.detalle
        go.isEnabled = !s.activa
        stop.isEnabled = s.activa
        go.alpha = if (s.activa) 0.45f else 1f
        stop.alpha = if (s.activa) 1f else 0.45f
    }

    private fun iniciar() {
        if (DescargaEstado.activa) return
        val txt = url.text.toString()
        val link = Regex("https?://\\S+").find(txt)?.value
        if (link == null) {
            // No es un link: se trata como búsqueda
            if (txt.isBlank()) { DescargaEstado.estado = "Pega un link o escribe algo para buscar"; pintarEstado() }
            else buscar()
            return
        }
        pedirPermisoAvisos { lanzar(link) }
    }

    private fun lanzar(link: String) {
        val audio = rAudio.isChecked
        val f = fmt.selectedItem as String
        val h = calidades[cal.selectedItem as String] ?: 0
        ocultarTeclado()
        DescargaEstado.empezar()
        try {
            DescargaService.iniciar(this, link, audio, f, h, chkLista.isChecked)
        } catch (e: Exception) {
            DescargaEstado.activa = false
            DescargaEstado.estado = "No se pudo iniciar la descarga: ${e.message}"
        }
        pintarEstado()
        scroll.post { scroll.smoothScrollTo(0, tarjetaProg.top) }
    }

    private fun cancelar() {
        DescargaEstado.estado = "Cancelando..."
        pintarEstado()
        Motor.cancelarDescarga()
    }

    /** Android 13+: pide permiso para mostrar el progreso en la barra de notificaciones (una sola vez). */
    private fun pedirPermisoAvisos(seguir: () -> Unit) {
        val yaPidio = getSharedPreferences("tema", MODE_PRIVATE).getBoolean("pidio_avisos", false)
        if (Build.VERSION.SDK_INT >= 33 && !yaPidio &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            getSharedPreferences("tema", MODE_PRIVATE).edit().putBoolean("pidio_avisos", true).apply()
            despuesDePermiso = seguir
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_AVISOS)
        } else seguir()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == REQ_AVISOS) {
            if (res.isEmpty() || res[0] != PackageManager.PERMISSION_GRANTED)
                Toast.makeText(this, "Sin avisos no verás el progreso en la barra, pero la descarga sigue igual",
                    Toast.LENGTH_LONG).show()
            despuesDePermiso?.invoke()
            despuesDePermiso = null
        }
    }
}
