package com.octopus.agent.accessibility

import android.app.Application
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34,35],application=Application::class)
class MainLoadReaderTest {
    private fun window(id:Int,type:Int)=AccessibilityWindowInfo.obtain().apply {shadowOf(this).setId(id);shadowOf(this).setType(type)}
    @Test fun windowMetadataWithoutAnyRootsCanSupplySampleAndImeBackoff() {
        val app=window(1,AccessibilityWindowInfo.TYPE_APPLICATION)
        var windows=listOf(app)
        val reader=MainLoadReader({windows},{PowerManager.THERMAL_STATUS_NONE},{0})
        assertTrue(reader.sample().reliable);assertFalse(reader.sample().busy)
        windows=windows+window(2,AccessibilityWindowInfo.TYPE_INPUT_METHOD)
        assertTrue(reader.sample().imeVisible)
    }
    @Test fun onlyKnownPrimaryWindowEventsAffectInteractionBackoff() {
        var now=0L
        val reader=MainLoadReader({listOf(window(1,AccessibilityWindowInfo.TYPE_APPLICATION))},{0},{now})
        reader.sample();reader.event(99,AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED);assertFalse(reader.sample().busy)
        reader.event(1,AccessibilityEvent.TYPE_VIEW_SCROLLED);assertTrue(reader.sample().busy)
        now=1_501;assertFalse(reader.sample().busy)
    }
    @Test fun thermalOrUnavailableSignalsBackOffConservatively() {
        var thermal=PowerManager.THERMAL_STATUS_MODERATE
        val reader=MainLoadReader({listOf(window(1,AccessibilityWindowInfo.TYPE_APPLICATION))},{thermal},{0})
        assertTrue(reader.sample().busy)
        thermal=-1;assertFalse(reader.sample().reliable)
        assertFalse(MainLoadReader({error("disconnected")},{0},{0}).sample().reliable)
        assertFalse(MainLoadReader({emptyList()},{0},{0}).sample().reliable)
    }
    @Test fun resetAndWindowReplacementDropOldInteractionIdentity() {
        var windows=listOf(window(1,AccessibilityWindowInfo.TYPE_APPLICATION))
        val reader=MainLoadReader({windows},{0},{0})
        reader.sample();reader.event(1,AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED);reader.reset();assertFalse(reader.sample().busy)
        windows=listOf(window(3,AccessibilityWindowInfo.TYPE_APPLICATION));reader.sample()
        reader.event(1,AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED);assertFalse(reader.sample().busy)
    }
}
