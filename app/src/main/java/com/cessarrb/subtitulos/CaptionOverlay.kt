package com.cessarrb.subtitulos

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * La ventanita flotante con los subtítulos.
 * Arriba va la traducción (grande) y abajo el original en inglés (chico).
 * Se arrastra con el dedo; doble toque muestra/oculta el inglés.
 */
class CaptionOverlay(private val context: Context) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val translated = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 19f
        setLineSpacing(0f, 1.1f)
    }
    private val original = TextView(context).apply {
        setTextColor(Color.parseColor("#B0B0B0"))
        textSize = 13f
        setPadding(0, dp(6), 0, 0)
    }
    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = GradientDrawable().apply {
            setColor(Color.argb(205, 0, 0, 0))
            cornerRadius = dp(14).toFloat()
        }
        addView(translated)
        addView(original)
    }

    private val params: WindowManager.LayoutParams
    private var shown = false
    private var showOriginal = true

    // Líneas ya terminadas (las 2 últimas) + la que se está diciendo ahora
    private val doneLines = ArrayDeque<String>()
    private var currentLine = ""

    init {
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        params = WindowManager.LayoutParams(
            screenW - dp(24),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = (screenH * 0.62).toInt()
        }
        enableDragAndDoubleTap()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableDragAndDoubleTap() {
        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                showOriginal = !showOriginal
                original.visibility = if (showOriginal) View.VISIBLE else View.GONE
                return true
            }
        })
        var startY = 0
        var touchY = 0f
        root.setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev)
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { startY = params.y; touchY = ev.rawY }
                MotionEvent.ACTION_MOVE -> {
                    params.y = startY + (ev.rawY - touchY).toInt()
                    if (shown) wm.updateViewLayout(root, params)
                }
            }
            true
        }
    }

    fun show() {
        if (!shown) { wm.addView(root, params); shown = true }
    }

    fun remove() {
        if (shown) { wm.removeView(root); shown = false }
    }

    /** Mensajes del sistema (cargando, errores). */
    fun setStatus(msg: String) {
        translated.text = msg
        original.text = ""
    }

    /** Lo que se está diciendo ahora (todavía puede cambiar). */
    fun setPartial(translation: String, english: String) {
        currentLine = translation
        render(english)
    }

    /** Una frase terminada: queda fija arriba. */
    fun commit(translation: String, english: String) {
        currentLine = ""
        doneLines.addLast(translation)
        while (doneLines.size > 2) doneLines.removeFirst()
        render(english)
    }

    fun setEnglish(english: String) {
        original.text = english
    }

    private fun render(english: String) {
        val lines = doneLines.toMutableList()
        if (currentLine.isNotBlank()) lines += currentLine
        translated.text = lines.joinToString("\n")
        original.text = english
    }
}
