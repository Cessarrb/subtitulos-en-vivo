package com.cessarrb.subtitulos

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * El corazón de la app:
 *   audio (interno o micrófono) → Vosk (texto en inglés) → ML Kit (español) → ventanita.
 */
class CaptionService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var overlay: CaptionOverlay? = null
    private var translator: Translator? = null
    private var projection: MediaProjection? = null
    private var worker: Thread? = null
    @Volatile private var listening = false

    // Para no traducir cada 100 ms lo que todavía se está diciendo
    private var lastPartialText = ""
    private var lastPartialTranslateAt = 0L
    private var partialTranslating = false
    private var sentenceId = 0  // descarta traducciones viejas que llegan tarde

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        val useMic = intent?.getBooleanExtra(EXTRA_MIC, false) ?: false
        val type = if (useMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                   else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        startForeground(NOTIF_ID, buildNotification(), type)
        running = true

        if (!useMic) {
            val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
            @Suppress("DEPRECATION")
            val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
            if (data == null) { shutdown(); return START_NOT_STICKY }
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projection = mpm.getMediaProjection(code, data).also {
                it.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { main.post { shutdown() } }
                }, main)
            }
        }

        translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.SPANISH)
                .build()
        )
        overlay = CaptionOverlay(this).apply {
            show()
            setStatus("Cargando el reconocedor de voz…")
        }

        listening = true
        worker = Thread({ listenLoop(useMic) }, "subtitulos-audio").apply { start() }
        return START_NOT_STICKY
    }

    // ---------- Hilo de audio ----------

    @SuppressLint("MissingPermission") // el permiso se pide en MainActivity
    private fun listenLoop(useMic: Boolean) {
        var record: AudioRecord? = null
        var model: Model? = null
        var recognizer: Recognizer? = null
        try {
            model = Model(ModelAssets.ensure(this).absolutePath)
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat())

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val builder = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf, SAMPLE_RATE)) // ~0,5 s

            if (useMic) {
                builder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            } else {
                val config = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                builder.setAudioPlaybackCaptureConfig(config)
            }
            record = builder.build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("No se pudo abrir el audio.")
            }
            record.startRecording()
            main.post { overlay?.setStatus("Escuchando… poné un video en inglés.") }

            val buffer = ShortArray(SAMPLE_RATE / 10) // 100 ms
            while (listening) {
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) continue
                if (recognizer.acceptWaveForm(buffer, n)) {
                    deliverFinal(field(recognizer.result, "text"))
                } else {
                    val partial = field(recognizer.partialResult, "partial")
                    // Si alguien habla sin pausa, cortamos la frase para que no quede eterna
                    if (partial.split(' ').size >= MAX_WORDS) {
                        deliverFinal(field(recognizer.finalResult, "text"))
                    } else {
                        deliverPartial(partial)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en el audio", e)
            main.post { overlay?.setStatus("Error: ${e.message}") }
        } finally {
            try { record?.stop() } catch (_: Exception) {}
            record?.release()
            recognizer?.close()
            model?.close()
        }
    }

    private fun field(json: String, key: String): String =
        try { JSONObject(json).optString(key, "").trim() } catch (_: Exception) { "" }

    // ---------- Traducción (hilo principal) ----------

    private fun deliverPartial(text: String) = main.post {
        if (text.isEmpty() || text == lastPartialText) return@post
        lastPartialText = text
        overlay?.setEnglish(text)
        val now = SystemClock.uptimeMillis()
        if (partialTranslating || now - lastPartialTranslateAt < PARTIAL_EVERY_MS) return@post
        partialTranslating = true
        lastPartialTranslateAt = now
        val id = sentenceId
        translator?.translate(text)
            ?.addOnSuccessListener { es -> if (id == sentenceId) overlay?.setPartial(es, text) }
            ?.addOnCompleteListener { partialTranslating = false }
    }

    private fun deliverFinal(text: String) = main.post {
        lastPartialText = ""
        if (text.isEmpty()) return@post
        sentenceId++ // invalida traducciones parciales pendientes
        translator?.translate(text)
            ?.addOnSuccessListener { es -> overlay?.commit(es, text) }
            ?.addOnFailureListener { overlay?.commit(text, text) }
    }

    // ---------- Notificación y cierre ----------

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Subtítulos activos", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, CaptionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Subtítulos en vivo")
            .setContentText("Traduciendo lo que escuchás")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Detener", stop).build())
            .setOngoing(true)
            .build()
    }

    private fun shutdown() {
        listening = false
        worker?.join(1500)
        worker = null
        overlay?.remove(); overlay = null
        translator?.close(); translator = null
        projection?.stop(); projection = null
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) shutdown()
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false
            private set

        const val ACTION_STOP = "com.cessarrb.subtitulos.STOP"
        const val EXTRA_MIC = "mic"
        const val EXTRA_RESULT_CODE = "code"
        const val EXTRA_DATA = "data"

        private const val TAG = "Subtitulos"
        private const val CHANNEL = "subtitulos"
        private const val NOTIF_ID = 1
        private const val SAMPLE_RATE = 16000
        private const val MAX_WORDS = 18
        private const val PARTIAL_EVERY_MS = 250L
    }
}
