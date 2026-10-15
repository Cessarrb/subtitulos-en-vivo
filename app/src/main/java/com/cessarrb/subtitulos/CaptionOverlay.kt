package com.cessarrb.subtitulos

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView

/**
 * La ventanita flotante con la traducción.
 * Se arrastra con el dedo y recuerda dónde la dejaste.
 */
class CaptionOverlay(private val context: Context) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val prefs = context.getSharedPreferences("overlay", Context.MODE_PRIVATE)
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val text = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 19f
        setLineSpacing(0f, 1.1f)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = GradientDrawable().apply {
            setColor(Color.argb(215, 15, 30, 70))
            cornerRadius = dp(14).toFloat()
        }
    }

    private val params: WindowManager.LayoutParams
    private var shown = false

    init {
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        params = WindowManager.LayoutParams(
            screenW - dp(24),
            WindowManager.LayoutParams.WRAP_CONTENT,
            // Las apps de accesibilidad pueden dibujar encima sin pedir otro permiso
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = prefs.getInt("y", (screenH * 0.30).toInt())
        }
        enableDrag()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableDrag() {
        var startY = 0
        var touchY = 0f
        text.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { startY = params.y; touchY = ev.rawY }
                MotionEvent.ACTION_MOVE -> {
                    params.y = startY + (ev.rawY - touchY).toInt()
                    if (shown) wm.updateViewLayout(text, params)
                }
                MotionEvent.ACTION_UP -> prefs.edit().putInt("y", params.y).apply()
            }
            true
        }
    }

    fun setText(s: String) {
        text.text = s
        show()
    }

    fun show() {
        if (!shown) { wm.addView(text, params); shown = true }
    }

    fun hide() {
        if (shown) { wm.removeView(text); shown = false }
    }
}
