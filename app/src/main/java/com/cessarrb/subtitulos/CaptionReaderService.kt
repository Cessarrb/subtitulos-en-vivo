package com.cessarrb.subtitulos

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

/**
 * Servicio de accesibilidad:
 *   lee el cuadro de "Subtítulos instantáneos" de Android
 *   → detecta el idioma → traduce al español con ML Kit → ventanita.
 *
 * Para que el texto no "baile":
 *   - Las frases terminadas se traducen UNA vez y quedan fijas.
 *   - Solo la frase que se está diciendo se va actualizando (en gris, al final).
 *   - Si alguien habla sin punto final mucho rato, se van fijando trozos igual.
 */
class CaptionReaderService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var overlay: CaptionOverlay
    private val languageId = LanguageIdentification.getClient()

    // ---- Traductores: uno por idioma de origen ----
    private val translators = mutableMapOf<String, Translator>()
    private val readyLangs = mutableSetOf<String>()
    private val downloadingLangs = mutableSetOf<String>()
    private var currentLang = TranslateLanguage.ENGLISH

    // ---- Lo que se muestra ----
    private class Entry(var es: String? = null)
    private val entries = ArrayList<Entry>()            // frases fijas, ya traducidas
    private val recentSources = ArrayDeque<String>()    // originales ya fijados (para no repetir)
    private var forcedWords = 0                          // palabras de la frase actual ya fijadas
    private var liveSource = ""                          // frase que se está diciendo
    private var livePreview = ""                         // su traducción provisoria
    private var liveInFlight = false
    private var livePending = false

    // ---- Lectura de pantalla ----
    private var scanScheduled = false
    private var lastText = ""
    private var lastDiagAt = 0L

    private val hideRunnable = Runnable { overlay.hide(); resetTranscript() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = CaptionOverlay(this)
        running = true
        prepare(TranslateLanguage.ENGLISH) // el más común, lo dejamos listo
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (scanScheduled) return
        scanScheduled = true
        main.postDelayed({ scanScheduled = false; scan() }, SCAN_EVERY_MS)
    }

    // =====================================================================
    // 1) Leer el cuadro de Subtítulos instantáneos
    // =====================================================================

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

            if (isCaption) captionText = collectText(root)
            if (writeDiag) {
                diag.append(if (isCaption) "★ " else "• ")
                    .append(title.ifEmpty { "(sin título)" }).append(" — ").append(pkg)
                if (isCaption) diag.append("\n   texto: \"").append(captionText?.take(80)).append('"')
                diag.append('\n')
            }
        }
        if (writeDiag) {
            diag.append("\nIdioma detectado: ").append(langName(currentLang))
            diagnostic = diag.toString()
            lastDiagAt = now
        }

        if (captionText == null) {
            main.removeCallbacks(hideRunnable)
            main.postDelayed(hideRunnable, HIDE_AFTER_MS)
            return
        }
        main.removeCallbacks(hideRunnable)

        val text = captionText.replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty() || text == lastText) return
        lastText = text
        lastCaption = text
        process(text)
    }

    // =====================================================================
    // 2) Separar lo terminado (fijo) de lo que se está diciendo (provisorio)
    // =====================================================================

    private fun process(text: String) {
        val sentences = text.split(SENTENCE_END).map { it.trim() }.filter { it.isNotEmpty() }
        if (sentences.isEmpty()) return

        // Todas menos la última ya terminaron: se fijan (si no estaban)
        sentences.dropLast(1).forEach { commitSentence(it) }

        // La última es la que se está diciendo
        val liveWords = sentences.last().split(' ')
        if (liveWords.size < forcedWords) forcedWords = 0 // empezó otra frase
        var pending = liveWords.drop(forcedWords)

        // Si habla mucho sin punto, fijamos un trozo y dejamos las últimas palabras abiertas
        if (pending.size >= FORCE_AFTER_WORDS) {
            val chunk = pending.dropLast(KEEP_OPEN_WORDS)
            addEntry(chunk.joinToString(" "))
            forcedWords += chunk.size
            pending = pending.takeLast(KEEP_OPEN_WORDS)
        }
        updateLive(pending.joinToString(" "))
    }

    private fun commitSentence(sentence: String) {
        val key = normalize(sentence)
        if (key.isEmpty() || isKnown(key)) return
        remember(key)

        // Si ya habíamos fijado el principio de esta frase, agregamos solo el resto
        val rest = sentence.split(' ').drop(forcedWords).joinToString(" ")
        forcedWords = 0
        if (rest.isNotBlank()) addEntry(rest)
        if (liveSource.isNotEmpty() && normalize(sentence).contains(normalize(liveSource))) {
            liveSource = ""; livePreview = ""
        }
    }

    /** ¿Ya fijamos esta frase? (tolera que el cuadro la recorte o la corrija un poco) */
    private fun isKnown(key: String): Boolean = recentSources.any { old ->
        old == key || old.endsWith(key) || old.startsWith(key) || key.startsWith(old) || similar(old, key)
    }

    private fun similar(a: String, b: String): Boolean {
        val wa = a.split(' ').toSet(); val wb = b.split(' ').toSet()
        if (wa.isEmpty() || wb.isEmpty()) return false
        return wa.intersect(wb).size.toFloat() / maxOf(wa.size, wb.size) >= 0.7f
    }

    private fun remember(key: String) {
        recentSources.addLast(key)
        while (recentSources.size > 40) recentSources.removeFirst()
    }

    private fun normalize(s: String) =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), "").replace(Regex("\\s+"), " ").trim()

    /** Agrega un trozo fijo; su traducción se completa cuando llega. */
    private fun addEntry(source: String) {
        val entry = Entry()
        entries.add(entry)
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        translate(source) { es -> entry.es = es ?: source; render() }
    }

    /** La frase en curso: se traduce de a una, siempre la versión más nueva. */
    private fun updateLive(source: String) {
        if (source == liveSource) return
        if (!source.startsWith(liveSource.take(10))) livePreview = "" // es otra frase
        liveSource = source
        if (source.isEmpty()) { livePreview = ""; render(); return }
        if (liveInFlight) { livePending = true; return }
        liveInFlight = true
        val asked = source
        translate(asked) { es ->
            if (es != null && liveSource.isNotEmpty()) livePreview = es
            render()
            liveInFlight = false
            if (livePending) { livePending = false; val s = liveSource; liveSource = ""; updateLive(s) }
        }
    }

    private fun resetTranscript() {
        entries.clear(); recentSources.clear()
        forcedWords = 0; liveSource = ""; livePreview = ""; lastText = ""
    }

    // =====================================================================
    // 3) Mostrar: frases fijas en blanco + la provisoria en gris, todo seguido
    // =====================================================================

    private fun render() {
        val sb = SpannableStringBuilder()
        if (downloadingLangs.isNotEmpty()) {
            sb.append("⏬ Bajando traductor de ${downloadingLangs.joinToString { langName(it) }}…\n")
        }
        entries.mapNotNull { it.es }.forEach { sb.append(it).append(' ') }
        if (livePreview.isNotEmpty()) {
            val start = sb.length
            sb.append(livePreview)
            sb.setSpan(ForegroundColorSpan(Color.argb(190, 200, 210, 230)),
                start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        overlay.setContent(sb)
    }

    // =====================================================================
    // 4) Traducir (detecta el idioma y baja el traductor si hace falta)
    // =====================================================================

    private fun translate(text: String, done: (String?) -> Unit) {
        languageId.identifyLanguage(text)
            .addOnSuccessListener { tag ->
                // Con textos cortos la detección falla: mantenemos el idioma anterior
                if (tag != "und" && text.length >= MIN_CHARS_TO_DETECT) {
                    TranslateLanguage.fromLanguageTag(tag)?.let { currentLang = it }
                }
                translateWith(currentLang, text, done)
            }
            .addOnFailureListener { translateWith(currentLang, text, done) }
    }

    private fun translateWith(lang: String, text: String, done: (String?) -> Unit) {
        if (lang == TranslateLanguage.SPANISH) { done(text); return }
        if (lang !in readyLangs) { prepare(lang); done(null); return }
        translators.getValue(lang).translate(text)
            .addOnSuccessListener { done(it) }
            .addOnFailureListener { done(null) }
    }

    private fun prepare(lang: String) {
        if (lang in readyLangs || lang in downloadingLangs) return
        downloadingLangs += lang
        val tr = translators.getOrPut(lang) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(lang)
                    .setTargetLanguage(TranslateLanguage.SPANISH)
                    .build()
            )
        }
        status = "Bajando el traductor de ${langName(lang)}…"
        tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                readyLangs += lang
                status = "Traductores listos: " + readyLangs.joinToString { langName(it) }
            }
            .addOnFailureListener { status = "No se pudo bajar el traductor (¿sin internet?): ${it.message}" }
            .addOnCompleteListener { downloadingLangs -= lang; resetTranscript() }
    }

    private fun langName(tag: String): String =
        Locale.forLanguageTag(tag).getDisplayLanguage(Locale("es")).ifEmpty { tag }

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

    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        if (::overlay.isInitialized) overlay.hide()
        translators.values.forEach { it.close() }
        languageId.close()
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
        private val SENTENCE_END = Regex("(?<=[.!?…。？！])\\s+")

        private const val SCAN_EVERY_MS = 100L
        private const val HIDE_AFTER_MS = 4000L
        private const val MIN_CHARS_TO_DETECT = 20
        private const val MAX_ENTRIES = 30
        private const val FORCE_AFTER_WORDS = 16  // frase sin punto: fijar cada tanto
        private const val KEEP_OPEN_WORDS = 5     // últimas palabras que quedan provisorias
    }
}
