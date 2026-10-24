package com.octopus.agent.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
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
    )
    private val targets = linkedMapOf<String, Target>()

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
                val target = Target(vd, reader)
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
        val target = synchronized(targets) { requireNotNull(targets[handle]) }
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
    override fun closeDisplay(handle: String) {
        val target = synchronized(targets) { targets.remove(handle) } ?: return
        target.reader.setOnImageAvailableListener(null, null)
        target.display.release()
        target.reader.close()
        target.frame = null
    }
    override fun destroy() {
        synchronized(targets) { targets.keys.toList() }.forEach(::closeDisplay)
        thread.quitSafely()
        exitProcess(0)
    }
}
