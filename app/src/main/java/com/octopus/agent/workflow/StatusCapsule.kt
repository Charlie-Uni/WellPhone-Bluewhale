package com.octopus.agent.workflow

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.octopus.agent.R

/** Optional status. A user tap opens the existing virtual-screen preview. */
class StatusCapsule(private val context: Context, private val onOpenPreview: () -> Unit) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private var view: TextView? = null
    fun update(text: String, enabled: Boolean) {
        if (!enabled || !Settings.canDrawOverlays(context)) { close(); return }
        if (view == null) {
            val label = TextView(context).apply {
                id = R.id.status_capsule
                setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER
                setPadding(24, 12, 24, 12)
                background = GradientDrawable().apply { setColor(Color.rgb(32,44,41)); cornerRadius = 48f }
                setOnClickListener { onOpenPreview() }
            }
            val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (36 * context.resources.displayMetrics.density).toInt()
            }
            runCatching { manager.addView(label, params); view = label }
        }
        view?.text = "✳ " + text.take(28)
    }
    fun close() { view?.let { runCatching { manager.removeView(it) } }; view = null }
}
