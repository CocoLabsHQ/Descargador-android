package com.descargador.app

/**
 * Único lugar donde se define a qué repositorio de GitHub apuntan
 * el botón de Feedback y el botón de Actualizar.
 * Si cambias el nombre del repositorio, cámbialo aquí.
 */
object Config {
    const val GITHUB_USER = "Falkenreich"
    const val GITHUB_REPO = "descargador-android"

    const val REPO_URL = "https://github.com/$GITHUB_USER/$GITHUB_REPO"
    const val ISSUES_NUEVO = "$REPO_URL/issues/new"
    const val API_ULTIMA_VERSION = "https://api.github.com/repos/$GITHUB_USER/$GITHUB_REPO/releases/latest"
}
