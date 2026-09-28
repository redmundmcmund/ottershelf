package io.github.ottershelf.devicetest

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * The phone the device tests run on, with the installed app's sign-in, downloads and settings in
 * this very process. So nothing here reads or writes the app's preferences, DataStores, databases,
 * downloads or tokens, and nothing calls its server: fixtures go into a folder of their own in the
 * app's cache ([fixtureDir], deleted afterwards), screenshots into a folder of their own in the
 * app's external files ([outDir], pulled with adb and deleted by the run script).
 */
object Device {
    const val TAG = "DeviceTest"

    val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** The app's context (the tests run in its process). */
    val context: Context get() = instrumentation.targetContext

    val ui: UiDevice get() = UiDevice.getInstance(instrumentation)

    /** `/sdcard/Android/data/<app>/files/device-tests`: the screenshots, for `adb pull`. */
    val outDir: File get() = File(context.getExternalFilesDir(null), "device-tests").apply { mkdirs() }

    /** Every fixture lives under here (the app's cache, a folder no app code uses). */
    val fixtureRoot: File get() = File(context.cacheDir, "device-tests")

    /** An empty fixture folder [name]. */
    fun fixtureDir(name: String): File = File(fixtureRoot, name).apply {
        deleteRecursively()
        mkdirs()
    }

    fun log(message: String) {
        Log.i(TAG, message)
    }

    /** Logs [message] and adds it to `device-tests/report.txt` (pulled with the screenshots). */
    fun report(message: String) {
        log(message)
        runCatching { File(outDir, "report.txt").appendText(message + "\n") }
    }

    /** Runs [command] as the shell (the instrumentation's UiAutomation) and returns its output. */
    fun shell(command: String): String {
        val pfd: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
    }

    /** Wakes the screen (not unlocking it: the keyguard stays). */
    fun wake() {
        shell("input keyevent KEYCODE_WAKEUP")
    }

    /** The whole screen as it is now, saved as `<name>.png` in [outDir]. */
    fun screenshot(name: String): Bitmap {
        instrumentation.waitForIdleSync()
        val shot = instrumentation.uiAutomation.takeScreenshot() ?: error("no screenshot")
        val file = File(outDir, "$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        log("screenshot ${file.absolutePath} ${shot.width}x${shot.height}")
        return shot
    }

    /** Polls [condition] until it holds, or fails with [what] after [timeoutMs]. */
    fun waitUntil(what: String, timeoutMs: Long = 15_000, pollMs: Long = 50, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            if (condition()) return
            if (SystemClock.uptimeMillis() > end) throw AssertionError("Timed out after $timeoutMs ms waiting for $what")
            SystemClock.sleep(pollMs)
        }
    }

    fun <T> onMain(block: () -> T): T {
        val out = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { out.set(runCatching(block)) }
        return out.get().getOrThrow()
    }
}

/**
 * A plain [ComponentActivity] (ui-test-manifest's, already in the debug app) shown over the lock
 * screen like an alarm clock: `setShowWhenLocked` and `setTurnScreenOn` before it is created, in a
 * task of its own (never the app's task, so none of its screens come up under or after
 * it). It shows only what a test sets. The phone stays locked: when it closes, the keyguard is back.
 *
 * Every injected gesture first checks [requireOnTop]: the host is resumed and has the window focus,
 * so a touch can't land on the lock screen.
 */
class LockScreenHost private constructor(val activity: ComponentActivity, private val callbacks: Application.ActivityLifecycleCallbacks) : AutoCloseable {

    fun setContent(content: @Composable () -> Unit) {
        Device.onMain { activity.setContent { content() } }
        Device.instrumentation.waitForIdleSync()
    }

    /** Fails (without touching anything) unless the host is on top, resumed and focused. */
    fun requireOnTop(allowDialog: Boolean = false) {
        val (resumed, focused) = Device.onMain { (activity.lifecycle.currentState == Lifecycle.State.RESUMED) to activity.hasWindowFocus() }
        check(resumed) { "The host activity isn't resumed: no gesture injected" }
        check(focused || allowDialog) { "The host activity hasn't the window focus: no gesture injected" }
        val top = Device.ui.currentPackageName
        check(top == Device.context.packageName) { "The top window is $top, not the host: no gesture injected" }
    }

    override fun close() {
        Device.onMain { activity.finish() }
        runCatching { Device.waitUntil("the host to close", 10_000) { activity.isDestroyed } }
        (Device.context.applicationContext as Application).unregisterActivityLifecycleCallbacks(callbacks)
    }

    companion object {
        fun launch(): LockScreenHost {
            Device.wake()
            val app = Device.context.applicationContext as Application
            val created = AtomicReference<ComponentActivity?>(null)
            val callbacks = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                    if (activity.javaClass != ComponentActivity::class.java) return
                    activity.setShowWhenLocked(true)
                    activity.setTurnScreenOn(true)
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    created.compareAndSet(null, activity as ComponentActivity)
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            }
            app.registerActivityLifecycleCallbacks(callbacks)
            val intent = Intent(Device.context, ComponentActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
            Device.context.startActivity(intent)
            try {
                Device.waitUntil("the host activity to be created", 15_000) { created.get() != null }
                val activity = created.get()!!
                Device.waitUntil("the host activity to resume over the lock screen", 20_000) {
                    Device.onMain { activity.lifecycle.currentState == Lifecycle.State.RESUMED && activity.hasWindowFocus() }
                }
                return LockScreenHost(activity, callbacks)
            } catch (e: Throwable) {
                created.get()?.let { a -> Device.onMain { a.finish() } }
                app.unregisterActivityLifecycleCallbacks(callbacks)
                Device.log("host state: " + Device.shell("dumpsys activity activities").lineSequence().filter { "ResumedActivity" in it || "mCurrentFocus" in it || "KeyguardController" in it }.joinToString(" | "))
                throw e
            }
        }
    }
}

