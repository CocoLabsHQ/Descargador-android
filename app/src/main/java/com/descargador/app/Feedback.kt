package com.descargador.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * Feedback: abre una "Issue" nueva en el repositorio de GitHub con el mensaje y los datos
 * del equipo ya escritos, o lo comparte por WhatsApp / correo / lo que elija la persona.
 */
object Feedback {

    fun informe(ctx: Context, mensaje: String, detalleError: String): String = buildString {
        appendLine(mensaje.trim().ifEmpty { "(escribe aquí tu comentario)" })
        appendLine()
        appendLine("---")
        appendLine("App: Descargador ${Actualizador.versionActual(ctx)}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Equipo: ${Build.MANUFACTURER} ${Build.MODEL}")
        if (detalleError.isNotBlank()) {
            appendLine()
            appendLine("Último error:")
            appendLine("```")
            appendLine(detalleError.trim().take(800))
            appendLine("```")
        }
    }

    fun abrirGithub(ctx: Context, mensaje: String, detalleError: String) {
        val primera = mensaje.trim().lines().firstOrNull().orEmpty().take(60)
        val titulo = if (primera.isEmpty()) "Feedback desde la app" else "Feedback: $primera"
        val uri = Uri.parse(Config.ISSUES_NUEVO).buildUpon()
            .appendQueryParameter("title", titulo)
            .appendQueryParameter("body", informe(ctx, mensaje, detalleError).take(3000))
            .build()
        ctx.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    fun compartir(ctx: Context, mensaje: String, detalleError: String) {
        val texto = informe(ctx, mensaje, detalleError) + "\n" + Config.REPO_URL
        val envio = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Feedback de Descargador")
            putExtra(Intent.EXTRA_TEXT, texto)
        }
        ctx.startActivity(Intent.createChooser(envio, "Enviar feedback con..."))
    }
}
