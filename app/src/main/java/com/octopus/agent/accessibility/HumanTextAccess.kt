package com.octopus.agent.accessibility

import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.octopus.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Resolves only the top window on the requested virtual display. No global focus/root fallback. */
class HumanTextAccess(private val broker: SessionBroker,
    private val windows: (Int) -> List<AccessibilityWindowInfo>,
    private val clock: () -> Long = SystemClock::uptimeMillis) : HumanTextPort {
    private data class Ref(val node: AccessibilityNodeInfo, val path: List<Int>, val bounds: Rect,
        val viewId: String?, val role: String?, val original: String)
    private data class Snapshot(val token: SessionToken, val display: Int, val pkg: String,
        val window: Int, val at: Long, val refs: Map<String, Ref>)
    private val snapshots = mutableMapOf<String, Snapshot>()

    override suspend fun capture(session: SessionState, draftId: String): List<HumanTextField> =
        withContext(Dispatchers.Main.immediate) {
            fence(session)
            val window = topWindow(session)
            val root = window.root ?: throw BindingLost()
            val refs = linkedMapOf<String, Ref>()
            val fields = mutableListOf<HumanTextField>()
            var visited = 0
            fun walk(node: AccessibilityNodeInfo, path: List<Int>, depth: Int) {
                if (++visited > 500 || depth > 30) throw ActionRejected("Text tree too large")
                if (secret(node)) return
                if (editable(node, session.packageName)) {
                    val value = node.text?.toString().orEmpty()
                    if (value.length <= HumanTextController.MAX_LENGTH) {
                        val id = (fields.size + 1).toString()
                        val label = node.hintText?.toString()?.takeIf(String::isNotBlank)
                            ?: node.contentDescription?.toString()?.takeIf(String::isNotBlank)
                            ?: node.viewIdResourceName?.substringAfterLast('/') ?: "输入框 $id"
                        fields += HumanTextField(id, "$id · ${label.take(80)}", value, node.isFocused)
                        refs[id] = Ref(node, path, Rect().also(node::getBoundsInScreen),
                            node.viewIdResourceName, node.className?.toString(), value)
                    }
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, path + i, depth + 1) }
            }
            walk(root, emptyList(), 0)
            snapshots[draftId] = Snapshot(session.token, session.displayId, session.packageName, window.id, clock(), refs)
            fields
        }

    override suspend fun replace(session: SessionState, draftId: String, fieldId: String, value: String,
        stillCurrent: () -> Boolean): TextWriteResult = withContext(Dispatchers.Main.immediate) {
        val snapshot = snapshots.remove(draftId) ?: throw ActionRejected("Text draft expired")
        if (snapshot.token != session.token || snapshot.display != session.displayId || snapshot.pkg != session.packageName ||
            clock() - snapshot.at !in 0..120_000 || value.length > HumanTextController.MAX_LENGTH) throw BindingLost()
        val ref = snapshot.refs[fieldId] ?: throw BindingLost()
        // No suspension from this final fence to ACTION_SET_TEXT. Search/Save/Send are separate actions.
        fence(session)
        if (!stillCurrent()) throw BindingLost()
        val node = resolve(session, snapshot, ref)
        if (node.text?.toString().orEmpty() != ref.original) throw BindingLost()
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        if (!runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)) {
            return@withContext TextWriteResult.UNKNOWN
        }
        // An accepted action can settle asynchronously. Never replay it when readback is inconclusive.
        withContext(NonCancellable) settlement@{
            repeat(6) { attempt ->
                if (attempt > 0) delay(50)
                if (!stillCurrent()) return@settlement TextWriteResult.UNKNOWN
                val confirmed = runCatching {
                    fence(session)
                    val current = resolve(session, snapshot, ref)
                    current.refresh() && editable(current,session.packageName) && current.text?.toString().orEmpty() == value
                }.getOrDefault(false)
                if (confirmed) return@settlement TextWriteResult.VERIFIED
            }
            TextWriteResult.UNKNOWN
        }
    }

    override fun clear(draftId: String) { snapshots.remove(draftId) }
    fun clearAll() { snapshots.clear() }

    private suspend fun fence(session: SessionState) {
        val current = broker.state(session.token.sessionId)
        if (current.token != session.token || current.owner != Owner.HUMAN || current.inFlight != 1 || session.displayId <= 0 ||
            current.displayId != session.displayId || current.packageName != session.packageName) throw BindingLost()
    }
    private fun topWindow(session: SessionState): AccessibilityWindowInfo {
        val top = windows(session.displayId).maxByOrNull { it.layer } ?: throw BindingLost()
        if (top.displayId != session.displayId || top.type != AccessibilityWindowInfo.TYPE_APPLICATION ||
            top.root?.packageName?.toString() != session.packageName) throw BindingLost()
        return top
    }
    private fun resolve(session: SessionState, snapshot: Snapshot, ref: Ref): AccessibilityNodeInfo {
        val window = topWindow(session)
        if (window.id != snapshot.window) throw BindingLost()
        var node = window.root ?: throw BindingLost()
        if (secret(node)) throw BindingLost()
        for (index in ref.path) {
            node = node.getChild(index) ?: throw BindingLost()
            if (secret(node)) throw BindingLost()
        }
        if (node != ref.node || !editable(node, session.packageName) || node.viewIdResourceName != ref.viewId ||
            node.className?.toString() != ref.role || Rect().also(node::getBoundsInScreen) != ref.bounds) throw BindingLost()
        return node
    }
    private fun editable(node: AccessibilityNodeInfo, pkg: String) = node.isVisibleToUser && node.isEnabled &&
        node.isEditable && node.packageName?.toString() == pkg && !secret(node) &&
        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }

    private fun secret(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return true
        val type = node.inputType
        val variation = type and InputType.TYPE_MASK_VARIATION
        return when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }
}
