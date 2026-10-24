package com.octopus.agent.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.octopus.agent.R
import com.octopus.core.HumanTextController
import com.octopus.core.HumanTextDraft

/** Explicit, temporary local keyboard destination. The virtual display's IME remains hidden. */
internal class LocalTextEditor(private val context: Context, private val draft: HumanTextDraft,
    private val apply: (String, String) -> Unit, private val cancel: () -> Unit) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val ime = context.getSystemService(InputMethodManager::class.java)
    private var attached = false
    private var submitting = false
    private var selected: Int? = null
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    private val column = LinearLayout(context).apply {
        id = R.id.text_editor_root; orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply { setColor(Color.rgb(245,243,238)); cornerRadius = dp(16).toFloat() }
    }
    private val value = object : EditText(context) {
        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (hasWindowFocus && isFocused) showKeyboard()
        }
        override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) cancel()
                return true
            }
            return super.onKeyPreIme(keyCode, event)
        }
    }.apply {
        id = R.id.text_editor_value
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        // Avoid personalized learning; no system input-method selection is changed.
        imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
            android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        isSaveEnabled = false; filters = arrayOf(InputFilter.LengthFilter(HumanTextController.MAX_LENGTH))
        minLines = 2; maxLines = 5; setTextColor(Color.DKGRAY); textSize = 15f
        isEnabled = false; hint = "先选择需要修改的输入框"
    }
    private val confirm = Button(context).apply {
        id = R.id.text_editor_apply; text = "写回所选字段"; isEnabled = false
        setOnClickListener {
            val field = selected?.let { draft.fields[it] } ?: return@setOnClickListener
            if (submitting) return@setOnClickListener
            submitting = true; isEnabled = false; value.isEnabled = false; selector.isEnabled = false
            text = "正在核实…"
            apply(field.id, value.text.toString())
        }
    }
    private val selector = Spinner(context).apply { id = R.id.text_editor_field }

    fun show(): Boolean {
        column.addView(TextView(context).apply {
            text = "编辑虚拟屏文字 · 仅替换，不自动保存/发送"; textSize = 13f; setTextColor(Color.DKGRAY)
        })
        selector.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
            listOf("请选择输入框") + draft.fields.map { it.label })
        selector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) { }
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (submitting) return
                selected = (position - 1).takeIf { it in draft.fields.indices }
                value.isEnabled = selected != null; confirm.isEnabled = selected != null
                value.setText(selected?.let { draft.fields[it].value }.orEmpty())
                if (selected != null) { value.setSelection(value.length()); value.requestFocus(); showKeyboard() }
            }
        }
        column.addView(selector, LinearLayout.LayoutParams(-1, dp(48)))
        column.addView(value, LinearLayout.LayoutParams(-1, -2))
        column.addView(TextView(context).apply {
            text = "两分钟内确认；切换输入框、取消、隐藏或交还会丢弃未写回修改。"; textSize = 12f
        })
        column.addView(LinearLayout(context).apply {
            addView(Button(context).apply { id = R.id.text_editor_cancel; text = "取消"; setOnClickListener { cancel() } },
                LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(confirm, LinearLayout.LayoutParams(0, dp(48), 1f))
        })
        val metrics = manager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val params = WindowManager.LayoutParams(minOf(dp(360), metrics.bounds.width()-insets.left-insets.right-dp(24)).coerceAtLeast(1),
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = insets.top + dp(24)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        return try {
            manager.addView(column, params); attached = true
            val focused = draft.fields.indices.filter { draft.fields[it].focused }.singleOrNull()
            val initial = focused ?: 0.takeIf { draft.fields.size == 1 }
            selector.setSelection(initial?.plus(1) ?: 0)
            true
        } catch (_: Exception) { close(); false }
    }
    private fun showKeyboard() {
        value.post {
            if (attached && !submitting && value.isEnabled && value.isFocused && value.hasWindowFocus()) {
                ime.showSoftInput(value, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }
    fun close() {
        if (!attached) return
        attached = false
        ime.hideSoftInputFromWindow(value.windowToken, 0)
        value.clearFocus(); value.setText("")
        runCatching { manager.removeView(column) }
    }
}
