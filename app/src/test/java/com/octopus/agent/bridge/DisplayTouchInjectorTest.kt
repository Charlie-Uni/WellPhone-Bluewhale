package com.octopus.agent.bridge

import android.app.Application
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import com.octopus.core.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowInputManagerGlobal
import org.robolectric.shadows.ShadowMotionEvent

/** Robolectric's MotionEvent omits display metadata. Model that field, not system delivery. */
@Implements(value=MotionEvent::class,minSdk=34)
class DisplayMetadataMotionEvent : ShadowMotionEvent() {
    companion object {
        val displays=mutableMapOf<Long,Int>()
        @JvmStatic @Implementation protected fun nativeSetDisplayId(pointer:Long,display:Int) { displays[pointer]=display }
        @JvmStatic @Implementation protected fun nativeGetDisplayId(pointer:Long):Int = displays[pointer]?:0
    }
}

/** Receives framework calls and records arguments; deliberately does not inject system input. */
@Implements(className="android.hardware.input.InputManagerGlobal",isInAndroidSdk=false,minSdk=34)
class RecordingInputManager : ShadowInputManagerGlobal() {
    data class Received(val display:Int,val uid:Int,val mode:Int,val action:Int,val source:Int,
        val tool:Int,val pointers:Int,val x:Float,val y:Float,val pressure:Float,val down:Long,val time:Long)
    companion object { val received=mutableListOf<Received>();var accepted=true }
    @Implementation protected fun injectInputEvent(input:InputEvent,mode:Int,targetUid:Int):Boolean {
        val event=input as MotionEvent
        received+=Received(event.javaClass.getMethod("getDisplayId").invoke(event) as Int,
            targetUid,mode,event.action,event.source,event.getToolType(0),event.pointerCount,event.x,event.y,
            event.pressure,event.downTime,event.eventTime)
        return accepted
    }
}

/** Checks real adapter reflection/arguments against Android 14 and 15 framework method shapes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34,35],application=Application::class,shadows=[RecordingInputManager::class,DisplayMetadataMotionEvent::class])
class DisplayTouchInjectorTest {
    @Before fun setup() { RecordingInputManager.received.clear();RecordingInputManager.accepted=true;DisplayMetadataMotionEvent.displays.clear() }
    private fun packet(phase:TouchPhase=TouchPhase.DOWN) = TouchPacket("test",0,phase,TouchPoint(123f,456f),SystemClock.uptimeMillis())
    @Test fun eventCarriesExactDisplayUidAndFingerSource() {
        val sample=packet()
        assertTrue(DisplayTouchInjector().inject(7,10_042,sample,sample.uptimeMillis))
        val event=RecordingInputManager.received.single()
        assertEquals(7,event.display);assertEquals(10_042,event.uid);assertEquals(2,event.mode)
        assertEquals(MotionEvent.ACTION_DOWN,event.action);assertEquals(InputDevice.SOURCE_TOUCHSCREEN,event.source)
        assertEquals(MotionEvent.TOOL_TYPE_FINGER,event.tool);assertEquals(1,event.pointers)
        assertEquals(123f,event.x,0f);assertEquals(456f,event.y,0f);assertEquals(1f,event.pressure,0f)
        assertEquals(sample.uptimeMillis,event.down);assertEquals(sample.uptimeMillis,event.time)
    }
    @Test fun defaultDisplayAndShellUidAreRejectedBeforeFrameworkCall() {
        val adapter=DisplayTouchInjector();val sample=packet()
        assertThrows(IllegalArgumentException::class.java) { adapter.inject(0,10_042,sample,sample.uptimeMillis) }
        assertThrows(IllegalArgumentException::class.java) { adapter.inject(7,2_000,sample,sample.uptimeMillis) }
        assertTrue(RecordingInputManager.received.isEmpty())
    }
    @Test fun terminalEventsReleasePressure() {
        val adapter=DisplayTouchInjector()
        for(phase in listOf(TouchPhase.UP,TouchPhase.CANCEL)) {
            val sample=packet(phase);assertTrue(adapter.inject(7,10_042,sample,sample.uptimeMillis))
        }
        assertEquals(listOf(MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL),RecordingInputManager.received.map {it.action})
        assertTrue(RecordingInputManager.received.all {it.pressure==0f})
    }
    @Test fun rejectedFrameworkAcknowledgementIsNotReportedAsAccepted() {
        RecordingInputManager.accepted=false
        val sample=packet()
        assertFalse(DisplayTouchInjector().inject(7,10_042,sample,sample.uptimeMillis))
        assertEquals(1,RecordingInputManager.received.size)
    }
}
