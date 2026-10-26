package com.octopus.agent.accessibility

import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.octopus.core.LoadSample

/** Primary-display metadata only: never calls event.source/text or window.root. Main-thread confined. */
class MainLoadReader(private val windows: () -> List<AccessibilityWindowInfo>, private val thermal: () -> Int,
    private val clock: () -> Long) {
    private var primaryIds = emptySet<Int>()
    private var activeUntil = -1L
    private var known = false
    fun reset() { primaryIds = emptySet(); activeUntil = -1; known = false }
    fun event(windowId: Int, type: Int) {
        if (windowId !in primaryIds) return
        if (type in setOf(AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED, AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_END, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) {
            activeUntil = clock() + 1_500
        }
    }
    fun sample(): LoadSample = try {
        val current = windows()
        val status = thermal()
        primaryIds = current.map { it.id }.filter { it >= 0 }.toSet()
        known = current.any { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } && status in 0..PowerManager.THERMAL_STATUS_SHUTDOWN
        LoadSample(current.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD },
            clock() <= activeUntil || status >= PowerManager.THERMAL_STATUS_MODERATE, known)
    } catch (_: Exception) { reset(); LoadSample(false, true, false) }
}
