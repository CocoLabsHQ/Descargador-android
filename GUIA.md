# Guía rápida (Linux Mint XFCE)

Nada de Android Studio: GitHub compila el APK en la nube. En el celular solo se instala el `.apk` final.

```bash
# 1) Herramientas (una vez)
sudo apt update && sudo apt install -y git gh unzip default-jre-headless
gh auth login            # GitHub.com > HTTPS > Login with a web browser
gh auth setup-git
git config --global user.name "Falkenreich"
git config --global user.email "TU_CORREO@ejemplo.com"

# 2) Proyecto
cd ~ && unzip ~/Descargas/descargador-android-personalizado.zip && cd descargador-android

# 3) Firma estable (guarda ~/descargador.jks en un lugar seguro; usa la MISMA contraseña para store y key)
keytool -genkeypair -v -keystore ~/descargador.jks -alias descargador -keyalg RSA -keysize 2048 -validity 10000

# 4) Repositorio público y primer push
git init -b main && git add . && git commit -m "Descargador personalizado"
gh repo create Falkenreich/Descargador-android --public --source=. --remote=origin --push

# 5) Secretos para que GitHub firme el APK
base64 -w0 ~/descargador.jks | gh secret set KEYSTORE_BASE64
gh secret set KEYSTORE_PASSWORD      # pega la contraseña
gh secret set KEY_PASSWORD           # la misma
gh secret set KEY_ALIAS --body descargador

# 6) Publicar la versión 0.2
git tag v0.2 && git push origin v0.2
gh run watch                          # espera el tilde verde
```

APK: https://github.com/Falkenreich/Descargador-android/releases/latest

Versiones siguientes: editar, `git add . && git commit -m "..." && git push`, luego `git tag v0.3 && git push origin v0.3`.
En los celulares: botón **Actualizar**.

Si falla la compilación: `gh run view --log-failed` y pásame el texto.
