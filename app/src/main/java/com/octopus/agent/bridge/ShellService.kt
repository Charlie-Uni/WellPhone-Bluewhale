package com.octopus.agent.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.view.Display
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import com.octopus.core.CalendarDraft
import com.octopus.core.TargetKind
import com.octopus.core.TouchTarget
import com.octopus.core.TouchPacket
import com.octopus.core.TouchStreamGuard
import com.octopus.core.TouchPhase
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Shizuku UserService, not an Android Service. UID must be shell, including in root environments.
 * Hidden API adapter deliberately fails closed when required isolation flags are unavailable.
 * Constants are obtained from the device framework (AOSP android-15.0.0_r1 reference).
 */
class ShellService(context: Context) : IShellService.Stub() {
    private val shellContext = context.createPackageContext("com.android.shell", 0)
    private val displays = shellContext.getSystemService(DisplayManager::class.java)
    private val thread = HandlerThread("octopus-capture").apply { start() }
    private data class Target(
        val display: VirtualDisplay, val reader: ImageReader,
        @Volatile var frame: ByteArray? = null, @Volatile var lastCapture: Long = 0,
        var sessionId: String? = null, var epoch: Long = -1, var agentAllowed: Boolean = false,
        val width: Int, val height: Int,
        var packageName: String? = null, var human: TouchTarget? = null,
        var humanUid: Int = -1, val touch: TouchStreamGuard = TouchStreamGuard(width, height),
    )
    private val targets = linkedMapOf<String, Target>()
    private val touchInjector by lazy { DisplayTouchInjector() }

    override fun diagnostics(): String = JSONObject()
        .put("uid", Process.myUid()).put("sdk", Build.VERSION.SDK_INT)
        .put("build", Build.FINGERPRINT).put("rootUsed", Process.myUid() == 0)
        .put("displayCount", synchronized(targets) { targets.size }).toString()

    override fun createDisplay(width: Int, height: Int, density: Int): String {
        check(Process.myUid() == 2000) { "Only non-root shell UID 2000 is supported" }
        require(width in 320..1080 && height in 480..1920 && density in 120..480)
        synchronized(targets) {
            check(targets.size < 2) { "At most two displays" }
            val required = listOf("TRUSTED", "OWN_FOCUS", "STEAL_TOP_FOCUS_DISABLED")
            fun requested(name: String) = DisplayManager::class.java
                .getField("VIRTUAL_DISPLAY_FLAG_$name").getInt(null)
            val flags = required.fold(DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                requested("DESTROY_CONTENT_ON_REMOVAL")) { value, name -> value or requested(name) }
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            var vd: VirtualDisplay? = null
            try {
                vd = displays.createVirtualDisplay("Octopus-${targets.size + 1}", width, height, density,
                    reader.surface, flags) ?: error("Framework returned no display")
                val actual = vd.display.flags
                required.forEach { name ->
                    val bit = Display::class.java.getField("FLAG_$name").getInt(null)
                    check(actual and bit != 0) { "Effective display flag missing: $name" }
                }
                check(vd.display.displayId > 0)
                // Hide virtual-display IME. Never fall back to the main-screen IME.
                val wmClass = Class.forName("android.view.WindowManagerGlobal")
                val wm = wmClass.getMethod("getWindowManagerService").invoke(null)
                Class.forName("android.view.IWindowManager").getMethod("setDisplayImePolicy",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(wm, vd.display.displayId, 2)
                val target = Target(vd, reader, width = width, height = height)
                val handle = UUID.randomUUID().toString()
                targets[handle] = target
                reader.setOnImageAvailableListener({ source ->
                    // Always drain producer frames, even when nobody is watching.
                    source.acquireLatestImage()?.use { image ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - target.lastCapture < 500) return@use
                        target.lastCapture = now
                        val plane = image.planes[0]
                        val paddedWidth = plane.rowStride / plane.pixelStride
                        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                        padded.copyPixelsFromBuffer(plane.buffer)
                        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
                        target.frame = ByteArrayOutputStream().use { stream ->
                            cropped.compress(Bitmap.CompressFormat.JPEG, 65, stream)
                            stream.toByteArray()
                        }
                        if (cropped !== padded) cropped.recycle()
                        padded.recycle()
                    }
                }, Handler(thread.looper))
                return JSONObject().put("handle", handle).put("displayId", vd.display.displayId)
                    .put("requestedFlags", flags).put("effectiveFlags", actual)
                    .put("width", width).put("height", height).put("imePolicy", "HIDE")
                    .put("behaviorVerified", false).toString()
            } catch (failure: Throwable) {
                vd?.release()
                reader.close()
                throw IllegalStateException("Virtual display preflight failed: ${failure.javaClass.simpleName}", failure)
            }
        }
    }

    override fun launch(handle: String, packageName: String): String {
        require(packageName in setOf("com.google.android.gm", "com.google.android.calendar"))
        val target = synchronized(targets) {
            requireNotNull(targets[handle]).also { it.packageName = packageName }
        }
        val component = requireNotNull(shellContext.packageManager.getLaunchIntentForPackage(packageName)?.component) {
            "App is not installed or has no launch activity"
        }
        val process = ProcessBuilder("/system/bin/am", "start", "--display",
            target.display.display.displayId.toString(), "-n", component.flattenToString(), "-f", "0x10000000")
            .redirectErrorStream(true).start()
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Launch timed out; inspect target before retry")
        }
        val output = process.inputStream.bufferedReader().use { it.readText().take(4_096) }
        check(process.exitValue() == 0 && !output.contains("Error:")) { "Launch rejected; inspect device" }
        return JSONObject().put("package", packageName).put("commandAccepted", true)
            .put("bindingVerified", false).toString()
    }

    override fun capture(handle: String): ParcelFileDescriptor? {
        val frame = synchronized(targets) { requireNotNull(targets[handle]).frame } ?: return null
        val pipe = ParcelFileDescriptor.createPipe()
        Thread {
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(frame) } }
        }.start()
        return pipe[0]
    }
    override fun bindControl(handle: String, sessionId: String, epoch: Long, agentAllowed: Boolean) {
        synchronized(targets) {
            val target = requireNotNull(targets[handle])
            require(target.sessionId == null || target.sessionId == sessionId)
            require(epoch >= target.epoch)
            check(epoch != target.epoch || target.agentAllowed || !agentAllowed) { "A revoked epoch cannot be reenabled" }
            cancelTouch(target)
            target.human = null
            target.sessionId = sessionId; target.epoch = epoch; target.agentAllowed = agentAllowed
        }
    }
    private fun calendarCommand(handle: String, sessionId: String, epoch: Long, arguments: List<String>) {
        val process = synchronized(targets) {
            val target = requireNotNull(targets[handle])
            check(target.sessionId == sessionId && target.epoch == epoch && target.agentAllowed)
            target.packageName = "com.google.android.calendar"
            val displayId = target.display.display.displayId.also { check(it > 0) }
            val command = listOf("/system/bin/am", "start", "--display", displayId.toString(),
                "-p", "com.google.android.calendar", "-f", "0x10000000") + arguments
            ProcessBuilder(command).redirectErrorStream(true).start()
        }
        // Fixed argument arrays; no shell interpretation, provider writes or model-supplied commands.
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
            process.destroyForcibly(); error("Calendar launch acknowledgement unknown")
        }
        val result = process.inputStream.bufferedReader().use { it.readText().take(4096) }
        check(process.exitValue() == 0 && !result.contains("Error:"))
    }
    override fun calendarView(handle: String, sessionId: String, epoch: Long, millis: Long) {
        require(millis > 0)
        calendarCommand(handle, sessionId, epoch, listOf("-a", "android.intent.action.VIEW",
            "-d", "content://com.android.calendar/time/$millis"))
    }
    override fun calendarInsert(handle: String, sessionId: String, epoch: Long, draftJson: String) {
        require(draftJson.length <= 40_000)
        val draft = Json.decodeFromString<CalendarDraft>(draftJson)
        require(draft.kind == TargetKind.EVENT && draft.title.isNotBlank())
        val start = Instant.parse(draft.start).toEpochMilli()
        val end = Instant.parse(draft.end).toEpochMilli()
        require(start < end)
        calendarCommand(handle, sessionId, epoch, listOf("-a", "android.intent.action.INSERT",
            "-d", "content://com.android.calendar/events", "--es", "title", draft.title,
            "--es", "description", draft.notes, "--es", "eventTimezone", draft.localZone,
            "--el", "beginTime", start.toString(), "--el", "endTime", end.toString()))
    }
    override fun closeDisplay(handle: String) {
        val target = synchronized(targets) {
            targets.remove(handle)?.also { cancelTouch(it); it.human = null }
        } ?: return
        target.reader.setOnImageAvailableListener(null, null)
        target.display.release()
        target.reader.close()
        target.frame = null
    }
    override fun bindHuman(handle: String, targetJson: String) = synchronized(targets) {
        check(Process.myUid() == 2000)
        require(targetJson.length < 2_000)
        val binding = Json.decodeFromString<TouchTarget>(targetJson)
        val target = requireNotNull(targets[handle])
        check(!target.agentAllowed && target.sessionId == binding.token.sessionId && target.epoch == binding.token.epoch)
        check(binding.displayId == target.display.display.displayId && binding.displayId > 0)
        check(binding.packageName == target.packageName && binding.width == target.width && binding.height == target.height)
        check(binding.packageName in setOf("com.google.android.gm", "com.google.android.calendar"))
        val previous = target.human
        check(previous == null || binding.viewportVersion >= previous.viewportVersion)
        check(target.touch.cancellation(SystemClock.uptimeMillis()) == null)
        target.humanUid = shellContext.packageManager.getApplicationInfo(binding.packageName, 0).uid
        check(target.humanUid >= 10_000)
        target.human = binding
    }
    override fun injectTouch(handle: String, targetJson: String, packetJson: String): Boolean = synchronized(targets) {
        check(Process.myUid() == 2000)
        require(targetJson.length < 2_000 && packetJson.length < 2_000)
        val binding = Json.decodeFromString<TouchTarget>(targetJson)
        val packet = Json.decodeFromString<TouchPacket>(packetJson)
        val target = requireNotNull(targets[handle])
        check(target.human == binding && !target.agentAllowed && target.epoch == binding.token.epoch)
        val display = target.display.display
        check(display.displayId == binding.displayId && display.displayId > 0)
        // Geometry changes reject new input, but must still allow cleanup of the old stroke.
        if (packet.phase != TouchPhase.CANCEL) {
            val size = Point().also { display.getRealSize(it) }
            check(display.rotation == android.view.Surface.ROTATION_0 && size.x == binding.width && size.y == binding.height)
        }
        val downTime = target.touch.accept(packet, SystemClock.uptimeMillis())
        try {
            val accepted = touchInjector.inject(display.displayId, target.humanUid, packet, downTime)
            if (!accepted) {
                runCatching { touchInjector.inject(display.displayId, target.humanUid,
                    packet.copy(phase = TouchPhase.CANCEL, uptimeMillis = SystemClock.uptimeMillis()), downTime) }
                target.touch.clear()
            }
            accepted
        } catch (failure: Exception) {
            runCatching { touchInjector.inject(display.displayId, target.humanUid,
                packet.copy(phase = TouchPhase.CANCEL, uptimeMillis = SystemClock.uptimeMillis()), downTime) }
            target.touch.clear()
            throw failure
        }
    }
    private fun cancelTouch(target: Target) {
        target.touch.cancellation(SystemClock.uptimeMillis())?.let { (packet, downTime) ->
            runCatching { touchInjector.inject(target.display.display.displayId, target.humanUid, packet, downTime) }
        }
        target.touch.clear()
    }
    override fun destroy() {
        synchronized(targets) { targets.keys.toList() }.forEach(::closeDisplay)
        thread.quitSafely()
        exitProcess(0)
    }
}
