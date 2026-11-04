package ai.nidhoggfgg.phoneuse.voice

import android.app.ActivityManager
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.AudioTrack
import android.os.*
import android.view.InputDevice
import android.view.InputEvent
import android.view.Display
import android.view.MotionEvent
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Entry point loaded from the installed APK by adb shell app_process. */
object VoiceShell {
    @Suppress("DEPRECATION")
    @JvmStatic fun main(args: Array<String>) {
        check(android.os.Process.myUid() == 2000) { "Start the voice helper through adb shell" }
        Looper.prepareMainLooper()
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        activityThread.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
        // Framework audio and content-provider calls attribute this process to com.android.shell.
        // app_process does not run ActivityThread's normal package binding sequence.
        val boundType = Class.forName(activityThread.name + "\$AppBindData")
        val bound = boundType.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        boundType.getDeclaredField("appInfo").apply { isAccessible = true }.set(bound, ApplicationInfo().apply {
            packageName = "com.android.shell"
            uid = android.os.Process.myUid()
        })
        activityThread.getDeclaredField("mBoundApplication").apply { isAccessible = true }.set(thread, bound)
        val system = activityThread.getMethod("getSystemContext").invoke(thread) as Context
        val context = system.createPackageContext("com.android.shell", 0)
        activityThread.getDeclaredField("mInitialApplication").apply { isAccessible = true }
            .set(thread, Instrumentation.newApplication(Application::class.java, context))
        val appUid = context.packageManager.getApplicationInfo("ai.nidhoggfgg.phoneuse", 0).uid
        val routingError = VoiceRoute.probe(context, appUid)
        val helper = object : IVoiceHelper.Stub() {
            private var last: ShellSession? = null
            @Synchronized fun shutdown() { last?.close() }
            @Synchronized override fun prepare(packageName: String, sampleRate: Int, byteCount: Int): IVoiceSession {
                check(Binder.getCallingUid() == appUid)
                val identity = Binder.clearCallingIdentity()
                try {
                    check(last?.closed != false) { "Another voice session is active" }
                    require(sampleRate in 8000..48000 && byteCount in 2..sampleRate * 60 && byteCount % 2 == 0)
                    val uid = context.packageManager.getApplicationInfo(packageName, 0).uid
                    return ShellSession(VoiceRoute(context, uid, sampleRate), byteCount, sampleRate).also { last = it }
                } catch (e: Exception) { throw IllegalStateException(e.cause?.message ?: e.message ?: "Cannot prepare audio route") }
                finally { Binder.restoreCallingIdentity(identity) }
            }
        }
        // A standalone shell process isn't an AMS-managed application. Acquire the provider
        // as an external caller, like the platform's 'content' command.
        val authority = "ai.nidhoggfgg.phoneuse.voice"
        val manager = ActivityManager::class.java.getMethod("getService").invoke(null)
        val am = Class.forName("android.app.IActivityManager")
        val token = Binder()
        val holder = am.getMethod("getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType,
            IBinder::class.java, String::class.java).invoke(manager, authority, 0, token, "phoneuse-voice")
        try {
            val provider = holder.javaClass.getField("provider").get(holder) as IInterface
            provider.asBinder().linkToDeath({
                try { helper.shutdown() } finally { kotlin.system.exitProcess(0) }
            }, 0)
            Runtime.getRuntime().addShutdownHook(Thread { helper.shutdown() })
            val extras = Bundle().apply { putBinder("helper", helper); putString("routing_error", routingError) }
            val providerType = Class.forName("android.content.IContentProvider")
            val string = String::class.java
            val attribution = try { Context::class.java.getMethod("getAttributionSource") }
                catch (_: NoSuchMethodException) { null }
            if (attribution != null) {
                providerType.getMethod("call", attribution.returnType, string, string, string, Bundle::class.java)
                    .invoke(provider, attribution.invoke(context), authority, "attach", null, extras)
            } else {
                providerType.getMethod("call", string, string, string, string, string, Bundle::class.java)
                    .invoke(provider, "com.android.shell", null, authority, "attach", null, extras)
            }
        } finally {
            am.getMethod("removeContentProviderExternal", String::class.java, IBinder::class.java).invoke(manager, authority, token)
        }
        println("Phone Use voice helper attached")
        if (routingError != null) println("Voice unavailable: $routingError")
        Looper.loop()
    }
}

private object ShellTouch {
    private val type = try { Class.forName("android.hardware.input.InputManagerGlobal") }
        catch (_: ClassNotFoundException) { Class.forName("android.hardware.input.InputManager") }
    private val manager = type.getMethod("getInstance").invoke(null)
    private val inject = type.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    private val setDisplay = MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    fun send(action: Int, down: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        try {
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            setDisplay.invoke(event, Display.DEFAULT_DISPLAY)
            check(inject.invoke(manager, event, 2) == true) { "Touch injection rejected" }
        } finally { event.recycle() }
    }
}

private class ShellSession(private val route: VoiceRoute, private val byteCount: Int, rate: Int) : IVoiceSession.Stub() {
    companion object { private val timer = Executors.newSingleThreadScheduledExecutor() }
    // Short clips on tracks with a fixed start threshold need trailing silence to start.
    private val pcm = ByteArray(maxOf(byteCount, route.startFrames * 2))
    private var received = 0
    private val playback = VoicePlayback(byteCount, rate, pcm.size)
    private var x = 0f
    private var y = 0f
    private var down = 0L
    private var lastCall = SystemClock.elapsedRealtime()
    @Volatile var closed = false
        private set
    private var sentUp = false
    private var failure: String? = null
    private val pump = timer.scheduleWithFixedDelay(::tick, 8, 8, TimeUnit.MILLISECONDS)

    @Synchronized override fun append(pcm: ByteArray) {
        check(!closed && down == 0L && pcm.size in 1..32768 && received + pcm.size <= byteCount)
        pcm.copyInto(this.pcm, received)
        received += pcm.size
        lastCall = SystemClock.elapsedRealtime()
    }
    @Synchronized override fun press(x: Float, y: Float) {
        check(!closed && received == byteCount && down == 0L)
        shell { route.requireIdleTarget() }
        this.x = x; this.y = y
        down = SystemClock.uptimeMillis()
        lastCall = SystemClock.elapsedRealtime()
        shell { ShellTouch.send(MotionEvent.ACTION_DOWN, down, x, y) }
    }
    @Synchronized override fun released(): Boolean {
        if (sentUp) return true
        check(!closed && down != 0L) { failure ?: "Voice session closed" }
        lastCall = SystemClock.elapsedRealtime()
        return false
    }
    @Synchronized private fun tick() {
        if (closed) return
        try {
            check(SystemClock.elapsedRealtime() - lastCall <= 2500) { "Phone Use stopped monitoring the gesture" }
            if (down == 0L) return
            val track = route.track
            val drained = playback.advance(SystemClock.uptimeMillis() - down, route.recorderSession(),
                { offset, length -> track.write(pcm, offset, length, AudioTrack.WRITE_NON_BLOCKING) }, track::getPlaybackHeadPosition)
            if (drained) {
                ShellTouch.send(MotionEvent.ACTION_UP, down, x, y)
                down = 0L
                sentUp = true
                close()
            }
        } catch (e: Exception) {
            failure = e.cause?.message ?: e.message
            runCatching { close() }
        }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        pump.cancel(false)
        pcm.fill(0)
        shell {
            try { if (down != 0L) ShellTouch.send(MotionEvent.ACTION_CANCEL, down, x, y) }
            finally { route.close() }
        }
    }
    private inline fun <T> shell(block: () -> T): T {
        val identity = Binder.clearCallingIdentity()
        try { return block() }
        catch (e: Exception) { throw IllegalStateException(e.cause?.message ?: e.message ?: "Voice helper failed") }
        finally { Binder.restoreCallingIdentity(identity) }
    }
}
