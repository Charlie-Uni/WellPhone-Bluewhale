package com.octopus.agent.bridge

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import com.octopus.core.TouchPacket
import com.octopus.core.TouchPhase

/** Shell-only, hidden API adapter. Requires display AND target UID; no unrestricted fallback. */
class DisplayTouchInjector {
    private val globalClass = Class.forName("android.hardware.input.InputManagerGlobal")
    private val manager = globalClass.getMethod("getInstance").invoke(null)
    private val inject = globalClass.getMethod("injectInputEvent", InputEvent::class.java,
        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    private val setDisplay = MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    fun inject(displayId: Int, uid: Int, packet: TouchPacket, downTime: Long): Boolean {
        require(displayId > 0 && uid >= 10_000 && downTime <= packet.uptimeMillis)
        require(SystemClock.uptimeMillis() - packet.uptimeMillis in 0..5_000)
        val action = when (packet.phase) {
            TouchPhase.DOWN -> MotionEvent.ACTION_DOWN
            TouchPhase.MOVE -> MotionEvent.ACTION_MOVE
            TouchPhase.UP -> MotionEvent.ACTION_UP
            TouchPhase.CANCEL -> MotionEvent.ACTION_CANCEL
        }
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coordinates = MotionEvent.PointerCoords().apply {
            x = packet.point.x; y = packet.point.y
            pressure = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) 0f else 1f
            size = 1f
        }
        val event = MotionEvent.obtain(downTime, packet.uptimeMillis, action, 1, arrayOf(properties),
            arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        return try {
            setDisplay.invoke(event, displayId)
            // WAIT_FOR_FINISHED = 2; Binder acknowledgement, not proof of rendered frame/side effect.
            inject.invoke(manager, event, 2, uid) as Boolean
        } finally { event.recycle() }
    }
}
