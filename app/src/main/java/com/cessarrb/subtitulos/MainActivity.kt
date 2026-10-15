package com.cessarrb.subtitulos

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Pantalla principal: pide los permisos, descarga el traductor
 * y arranca/detiene el servicio que muestra los subtítulos.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var button: Button
    private lateinit var micSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()

        val title = TextView(this).apply {
            text = "Subtítulos en vivo"
            textSize = 26f
            setTextColor(Color.BLACK)
        }
        val info = TextView(this).apply {
            text = "Escucha lo que suena en el celular (reels, videos), lo pasa a texto en inglés " +
                "y lo traduce al español en una ventanita flotante. Todo funciona sin internet " +
                "después de la primera vez.\n\nPodés arrastrar la ventanita y tocarla dos veces " +
                "para mostrar u ocultar el texto en inglés."
            textSize = 15f
            setPadding(0, pad / 2, 0, pad)
        }
        micSwitch = Switch(this).apply {
            text = "Usar el micrófono en vez del audio interno\n(probalo si alguna app no deja capturar su audio)"
            textSize = 14f
        }
        button = Button(this).apply {
            textSize = 18f
            setOnClickListener { onButton() }
        }
        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, pad, 0, 0)
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setBackgroundColor(Color.WHITE)
            setPadding(pad, pad * 2, pad, pad)
            addView(title); addView(info); addView(micSwitch); addView(button); addView(status)
        })
    }

    override fun onResume() {
        super.onResume()
        refreshButton()
    }

    private fun refreshButton() {
        button.text = if (CaptionService.running) "Detener" else "Iniciar"
    }

    private fun onButton() {
        if (CaptionService.running) {
            startService(Intent(this, CaptionService::class.java).setAction(CaptionService.ACTION_STOP))
            button.postDelayed({ refreshButton() }, 300)
            status.text = "Detenido."
            return
        }
        startFlow()
    }

    // Paso 1: permisos de micrófono/audio y notificaciones
    private fun startFlow() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
            return
        }
        checkOverlay()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Sin el permiso de audio la app no puede escuchar nada."
            return
        }
        checkOverlay()
    }

    // Paso 2: permiso para dibujar encima de otras apps
    private fun checkOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            status.text = "Activá \"Mostrar sobre otras apps\" para Subtítulos en vivo, volvé y tocá Iniciar otra vez."
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        downloadTranslator()
    }

    // Paso 3: bajar el traductor inglés→español (solo la primera vez, ~30 MB)
    private fun downloadTranslator() {
        status.text = "Preparando el traductor… (la primera vez baja unos 30 MB)"
        button.isEnabled = false
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.SPANISH)
                .build()
        )
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                translator.close()
                button.isEnabled = true
                startCapture()
            }
            .addOnFailureListener {
                translator.close()
                button.isEnabled = true
                status.text = "No se pudo bajar el traductor. Revisá la conexión y probá de nuevo.\n(${it.message})"
            }
    }

    // Paso 4: arrancar (con micrófono directo, o pidiendo capturar el audio interno)
    private fun startCapture() {
        if (micSwitch.isChecked) {
            launchService(Intent(this, CaptionService::class.java).putExtra(CaptionService.EXTRA_MIC, true))
        } else {
            status.text = "Elegí \"Compartir pantalla completa\" para que pueda escuchar el audio de todas las apps."
            val mpm = getSystemService(MediaProjectionManager::class.java)
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) return
        if (resultCode != RESULT_OK || data == null) {
            status.text = "Cancelado. Sin ese permiso no puedo escuchar el audio interno."
            return
        }
        launchService(
            Intent(this, CaptionService::class.java)
                .putExtra(CaptionService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(CaptionService.EXTRA_DATA, data)
        )
    }

    private fun launchService(intent: Intent) {
        startForegroundService(intent)
        status.text = "¡Listo! Abrí Instagram o cualquier video. La primera vez tarda unos segundos en cargar."
        button.postDelayed({ refreshButton() }, 500)
    }

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_PROJECTION = 2
    }
}
