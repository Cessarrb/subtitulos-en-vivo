package com.cessarrb.subtitulos

import android.content.Context
import java.io.File

/**
 * El modelo de voz viene adentro del APK (carpeta assets/model-en-us).
 * Vosk necesita archivos reales, así que la primera vez se copia a la memoria de la app.
 */
object ModelAssets {
    private const val ASSET_DIR = "model-en-us"

    fun ensure(context: Context): File {
        val dest = File(context.filesDir, ASSET_DIR)
        val marker = File(dest, ".copiado-v${BuildConfigVersion.get(context)}")
        if (marker.exists()) return dest

        if (context.assets.list(ASSET_DIR).isNullOrEmpty()) {
            throw IllegalStateException("El APK no trae el modelo de voz (assets/$ASSET_DIR).")
        }
        dest.deleteRecursively()
        copyDir(context, ASSET_DIR, dest)
        marker.createNewFile()
        return dest
    }

    private fun copyDir(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            // Es un archivo
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            return
        }
        target.mkdirs()
        for (child in children) copyDir(context, "$assetPath/$child", File(target, child))
    }
}

/** versionCode de la app, para volver a copiar el modelo si se actualiza. */
private object BuildConfigVersion {
    fun get(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
}
