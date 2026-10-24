package com.octopus.agent.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.octopus.core.Action
import com.octopus.core.ActionPort
import com.octopus.core.Observation
import com.octopus.core.ObservationPort
import com.octopus.core.SessionState
import com.octopus.core.UiElement
import com.octopus.core.Owner
import com.octopus.core.BindingLost
import com.octopus.core.TouchPoint
import com.octopus.agent.OctopusApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * No global rootInActiveWindow or global Back. Both observation and action resolve a target window.
 * Caller must invoke perform inside SessionBroker admission.
 */
class OctopusAccessibilityService : AccessibilityService(), ObservationPort, ActionPort {
    companion object { @Volatile var instance: OctopusAccessibilityService? = null; private set }
    private data class NodeRef(val path: List<Int>, val viewId: String?, val className: String?, val text: String?)
    private data class Snapshot(val observation: Observation, val refs: Map<Int, NodeRef>)
    private val snapshots = mutableMapOf<String, Snapshot>()

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* No main-screen content collection. */ }
    override fun onInterrupt() { snapshots.clear() }
    override fun onDestroy() { instance = null; snapshots.clear(); super.onDestroy() }

    /** Fast local hit-window check for human input; system dialogs and main-screen roots fail closed. */
    fun touchWindowId(session: SessionState, point: TouchPoint): Int {
        require(session.displayId > 0 && session.owner == Owner.HUMAN)
        if (instance !== this) throw BindingLost()
        val window = windowsOnAllDisplays[session.displayId].orEmpty().filter {
            val bounds = Rect().also(it::getBoundsInScreen)
            bounds.contains(point.x.toInt(), point.y.toInt())
        }.maxByOrNull { it.layer } ?: throw BindingLost()
        if (window.root?.packageName?.toString() != session.packageName) throw BindingLost()
        return window.id
    }

    override suspend fun observe(session: SessionState): Observation = withContext(Dispatchers.Main.immediate) {
        require(session.displayId > 0)
        if (instance !== this@OctopusAccessibilityService) throw BindingLost()
        val windows = windowsOnAllDisplays[session.displayId].orEmpty()
        val window = windows.firstOrNull { it.root?.packageName?.toString() == session.packageName }
            ?: throw BindingLost()
        val root = window.root ?: throw BindingLost()
        val elements = mutableListOf<UiElement>()
        val refs = mutableMapOf<Int, NodeRef>()
        var truncated = false
        fun walk(node: AccessibilityNodeInfo, path: List<Int>, depth: Int, parent: Int?) {
            if (elements.size >= 500 || depth > 30) { truncated = true; return }
            if (node.isPassword) return
            var nextParent = parent
            if (node.isVisibleToUser && (node.text != null || node.contentDescription != null ||
                    node.isClickable || node.isEditable || node.isScrollable)) {
                val id = elements.size + 1
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                if ((node.text?.length ?: 0) > 24_000) truncated = true
                elements += UiElement(id, node.className?.toString().orEmpty(), node.text?.toString().orEmpty().take(24_000),
                    node.contentDescription?.toString().orEmpty().take(1_000),
                    node.isClickable, node.isEditable, node.isScrollable, node.viewIdResourceName.orEmpty(),
                    parent, bounds.left, bounds.top, bounds.right, bounds.bottom, node.isCheckable)
                refs[id] = NodeRef(path, node.viewIdResourceName, node.className?.toString(), node.text?.toString())
                nextParent = id
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let { walk(it, path + index, depth + 1, nextParent) }
        }
        walk(root, emptyList(), 0, null)
        val observation = Observation(session.token, UUID.randomUUID().toString(), session.displayId,
            window.id, session.packageName, SystemClock.elapsedRealtime(), elements, truncated)
        snapshots[session.token.sessionId] = Snapshot(observation, refs)
        observation
    }

    override suspend fun perform(session: SessionState, observation: Observation, action: Action): Boolean =
        withContext(Dispatchers.Main.immediate) {
            // Android ownership changes and final node dispatch share Main. No suspension is
            // allowed between this final fence and performAction below.
            val authority = (application as OctopusApplication).broker.state(session.token.sessionId)
            if (authority.token != session.token || authority.owner != Owner.AGENT) return@withContext false
            val snapshot = snapshots[session.token.sessionId] ?: return@withContext false
            if (snapshot.observation != observation || observation.token != session.token ||
                observation.displayId != session.displayId || observation.packageName != session.packageName ||
                SystemClock.elapsedRealtime() - observation.capturedAtMillis !in 0..5_000) return@withContext false
            val id = when (action) {
                is Action.Click -> action.nodeId
                is Action.SetText -> action.nodeId
                is Action.SubmitSearch -> action.nodeId
                is Action.Scroll -> action.nodeId
                else -> return@withContext false
            }
            val ref = snapshot.refs[id] ?: return@withContext false
            val window = windowsOnAllDisplays[session.displayId].orEmpty()
                .firstOrNull { it.id == observation.windowId && it.root?.packageName?.toString() == session.packageName }
                ?: return@withContext false
            var node = window.root ?: return@withContext false
            for (index in ref.path) node = node.getChild(index) ?: return@withContext false
            if (node.isPassword || !node.isVisibleToUser || node.packageName?.toString() != session.packageName ||
                node.viewIdResourceName != ref.viewId || node.className?.toString() != ref.className ||
                node.text?.toString() != ref.text) return@withContext false
            val result = when (action) {
                is Action.Click -> node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                is Action.SetText -> node.isEditable && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text) })
                is Action.SubmitSearch -> node.isEditable && node.actionList.any {
                    it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                } && node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                is Action.Scroll -> node.isScrollable && node.performAction(
                    if (action.forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                else -> false
            }
            // Never reuse old node refs, even when the framework reports a rejected action.
            snapshots.remove(session.token.sessionId)
            result
        }
}
