package com.cessarrb.subtitulos

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * La ventanita con la traducción.
 * - Tamaño fijo: no se agranda ni se achica sola.
 * - Se mueve arrastrándola; se cambia el tamaño con la manija ⤡ de la esquina.
 * - El texto corre de corrido: lo nuevo entra abajo y lo viejo sube.
 */
class CaptionOverlay(private val context: Context) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val prefs = context.getSharedPreferences("overlay", Context.MODE_PRIVATE)
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val screenW = context.resources.displayMetrics.widthPixels
    private val screenH = context.resources.displayMetrics.heightPixels

    private val text = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 19f
        setLineSpacing(0f, 1.1f)
        isVerticalScrollBarEnabled = false
        setPadding(dp(14), dp(10), dp(30), dp(10))
    }

    private val handle = TextView(context).apply {
        text = "⤡"
        textSize = 20f
        setTextColor(Color.argb(170, 255, 255, 255))
        gravity = Gravity.CENTER
    }

    private val root = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            setColor(Color.argb(215, 15, 30, 70))
            cornerRadius = dp(14).toFloat()
        }
        clipToOutline = true
        addView(text, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addView(handle, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.BOTTOM or Gravity.END))
    }

    private val params = WindowManager.LayoutParams(
        prefs.getInt("w", screenW - dp(24)),
        prefs.getInt("h", dp(130)),
        // Las apps de accesibilidad pueden dibujar encima sin pedir otro permiso
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = prefs.getInt("x", dp(12))
        y = prefs.getInt("y", (screenH * 0.30).toInt())
    }
    private var shown = false

    init {
        enableMove()
        enableResize()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableMove() {
        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        text.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y; touchX = ev.rawX; touchY = ev.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (ev.rawX - touchX).toInt()
                    params.y = startY + (ev.rawY - touchY).toInt()
                    relayout()
                }
                MotionEvent.ACTION_UP -> save()
            }
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableResize() {
        var startW = 0; var startH = 0
        var touchX = 0f; var touchY = 0f
        handle.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startW = params.width; startH = params.height; touchX = ev.rawX; touchY = ev.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    params.width = (startW + (ev.rawX - touchX).toInt()).coerceIn(dp(160), screenW)
                    params.height = (startH + (ev.rawY - touchY).toInt()).coerceIn(dp(60), screenH / 2)
                    relayout()
                    scrollToEnd()
                }
                MotionEvent.ACTION_UP -> save()
            }
            true
        }
    }

    private fun relayout() { if (shown) wm.updateViewLayout(root, params) }

    private fun save() {
        prefs.edit()
            .putInt("x", params.x).putInt("y", params.y)
            .putInt("w", params.width).putInt("h", params.height)
            .apply()
    }

    /** Pone el texto y lo deja pegado abajo (lo más nuevo siempre a la vista). */
    fun setContent(s: CharSequence) {
        text.text = s
        show()
        text.post { scrollToEnd() }
    }

    private fun scrollToEnd() {
        val layout = text.layout ?: return
        val contentH = layout.height + text.totalPaddingTop + text.totalPaddingBottom
        text.scrollTo(0, (contentH - text.height).coerceAtLeast(0))
    }

    fun show() {
        if (!shown) { wm.addView(root, params); shown = true }
    }

    fun hide() {
        if (shown) { wm.removeView(root); shown = false }
    }
}
