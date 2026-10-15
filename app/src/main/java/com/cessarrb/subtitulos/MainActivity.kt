package com.cessarrb.subtitulos

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Pantalla principal: explica cómo activarla y muestra un diagnóstico
 * para ver si la app logra leer los Subtítulos instantáneos.
 */
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var state: TextView
    private lateinit var diag: TextView

    private val refresher = object : Runnable {
        override fun run() { refresh(); main.postDelayed(this, 700) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()

        fun label(s: String, size: Float = 15f, color: Int = Color.DKGRAY) = TextView(this).apply {
            text = s; textSize = size; setTextColor(color); setPadding(0, pad / 2, 0, pad / 2)
        }

        state = label("", 16f, Color.BLACK)
        diag = label("", 12f, Color.DKGRAY).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#F0F0F0"))
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            addView(label("Subtítulos en vivo", 26f, Color.BLACK))
            addView(label(
                "Lee los Subtítulos instantáneos de Android y te muestra la traducción al español " +
                "en una ventanita azul que podés arrastrar."
            ))
            addView(state)

            addView(label("Paso 1 · Activar el servicio", 18f, Color.BLACK))
            addView(label(
                "Tocá el botón, entrá a \"Subtítulos en vivo\" (puede estar en \"Apps instaladas\" o " +
                "\"Servicios instalados\") y activalo.\n\nSi aparece gris o dice \"Configuración " +
                "restringida\": tocá el segundo botón, después los tres puntitos ⋮ arriba a la " +
                "derecha → \"Permitir configuración restringida\", y volvé a intentar."
            ))
            addView(Button(this@MainActivity).apply {
                text = "Abrir Accesibilidad"
                setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            })
            addView(Button(this@MainActivity).apply {
                text = "Info de la app (configuración restringida)"
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            })

            addView(label("Paso 2 · Prender los Subtítulos instantáneos", 18f, Color.BLACK))
            addView(label(
                "Subí o bajá el volumen, tocá el ícono de subtítulos (un rectángulo con líneas) " +
                "debajo de la barra de volumen. Poné un reel en inglés: arriba de los subtítulos " +
                "debería aparecer la traducción."
            ))

            addView(label("Diagnóstico", 18f, Color.BLACK))
            addView(label(
                "Ventanas que ve la app ahora. La que tiene ★ es la que detectó como subtítulos. " +
                "Si no traduce, sacale captura a esto con un reel andando y mandámela."
            ))
            addView(diag)
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.WHITE); addView(content) })
    }

    override fun onResume() { super.onResume(); main.post(refresher) }
    override fun onPause() { super.onPause(); main.removeCallbacks(refresher) }

    private fun refresh() {
        state.text = if (CaptionReaderService.running)
            "✅ Servicio activo. ${CaptionReaderService.status}"
        else
            "⚠️ El servicio está apagado. Hacé el paso 1."
        diag.text = buildString {
            append(CaptionReaderService.diagnostic.ifEmpty { "(todavía nada)\n" })
            if (CaptionReaderService.lastCaption.isNotEmpty())
                append("\nÚltimo texto leído:\n").append(CaptionReaderService.lastCaption)
        }
    }
}
