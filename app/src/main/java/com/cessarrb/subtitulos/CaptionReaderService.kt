package com.cessarrb.subtitulos

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Servicio de accesibilidad:
 *   lee el cuadro de "Subtítulos instantáneos" de Android → traduce con ML Kit → ventanita.
 */
class CaptionReaderService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var overlay: CaptionOverlay
    private lateinit var translator: Translator
    private var translatorReady = false

    private var scanScheduled = false
    private var lastText = ""
    private var pendingText: String? = null
    private var translating = false
    private var lastDiagAt = 0L

    private val hideRunnable = Runnable { overlay.hide(); lastText = "" }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = CaptionOverlay(this)
        translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.SPANISH)
                .build()
        )
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { translatorReady = true; status = "Traductor listo." }
            .addOnFailureListener { status = "No se pudo bajar el traductor (¿sin internet?): ${it.message}" }
        status = "Bajando el traductor (solo la primera vez, ~30 MB)…"
        running = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Llegan muchísimos avisos; revisamos la pantalla como mucho cada 200 ms
        if (scanScheduled) return
        scanScheduled = true
        main.postDelayed({ scanScheduled = false; scan() }, SCAN_EVERY_MS)
    }

    /** Busca la ventana de Subtítulos instantáneos y lee su texto. */
    private fun scan() {
        val now = SystemClock.uptimeMillis()
        val writeDiag = now - lastDiagAt > 1000
        val diag = StringBuilder()
        var captionText: String? = null

        for (w in windows) {
            val root = w.root ?: continue
            val pkg = root.packageName?.toString() ?: ""
            if (pkg == packageName) continue
            val title = w.title?.toString() ?: ""
            val isCaption = pkg in CAPTION_PACKAGES || CAPTION_TITLE.containsMatchIn(title)

            if (isCaption) captionText = collectText(root).trim()
            if (writeDiag) {
                diag.append(if (isCaption) "★ " else "• ")
                    .append(title.ifEmpty { "(sin título)" }).append(" — ").append(pkg)
                if (isCaption) diag.append("\n   texto: \"").append(captionText?.take(80)).append('"')
                diag.append('\n')
            }
        }
        if (writeDiag) { diagnostic = diag.toString(); lastDiagAt = now }

        if (captionText == null) {
            // No hay cuadro de subtítulos: escondemos la traducción en un ratito
            main.removeCallbacks(hideRunnable)
            main.postDelayed(hideRunnable, HIDE_AFTER_MS)
            return
        }
        main.removeCallbacks(hideRunnable)
        if (!translatorReady) { overlay.setText(status); return }

        val text = tail(captionText)
        if (text.isEmpty() || text == lastText) return
        lastText = text
        lastCaption = text
        pendingText = text
        translateNext()
    }

    /** Traduce el texto más nuevo; si mientras tanto cambió, vuelve a traducir. */
    private fun translateNext() {
        if (translating) return
        val t = pendingText ?: return
        pendingText = null
        translating = true
        translator.translate(t)
            .addOnSuccessListener { es -> overlay.setText(es) }
            .addOnCompleteListener { translating = false; translateNext() }
    }

    private fun collectText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            n.text?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(node)
        return sb.toString()
    }

    /** Nos quedamos con el final del texto (lo más reciente), cortando en una palabra. */
    private fun tail(s: String): String {
        val clean = s.replace(Regex("\\s+"), " ").trim()
        if (clean.length <= MAX_CHARS) return clean
        val cut = clean.substring(clean.length - MAX_CHARS)
        return cut.substringAfter(' ', cut)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        if (::overlay.isInitialized) overlay.hide()
        if (::translator.isInitialized) translator.close()
        super.onDestroy()
    }

    companion object {
        // Lo lee la pantalla principal para el modo diagnóstico
        @Volatile var running = false
        @Volatile var status = ""
        @Volatile var diagnostic = ""
        @Volatile var lastCaption = ""

        // App del sistema que dibuja los Subtítulos instantáneos en la mayoría de los celulares
        private val CAPTION_PACKAGES = setOf("com.google.android.as")
        private val CAPTION_TITLE = Regex("(?i)live ?caption|subt[ií]tulos? instant")

        private const val SCAN_EVERY_MS = 200L
        private const val HIDE_AFTER_MS = 1500L
        private const val MAX_CHARS = 220
    }
}
