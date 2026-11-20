package com.whereq.dosbox

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The back button on a device.
 *
 * This is the test that covers the defect the JVM test cannot: from Android 13
 * on, the system does not send `KEYCODE_BACK` to the activity any more, it
 * calls the registered `OnBackInvokedCallback`. Without that callback the back
 * button silently does nothing - the launcher stays open and the keyboard stays
 * up - which is exactly what the app got on API 33+ before the callback was
 * added.
 *
 * The back press is delivered the way a user delivers it: through the system
 * input pipeline (`input keyevent 4`), not by calling the activity directly.
 * That is the only way to reach the predictive-back dispatcher.
 *
 * Needs a device or emulator on API 33 or newer:
 *
 *     ./android/gradlew --project-dir android :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class BackHandlingTest {

    private val instrumentation: Instrumentation =
        InstrumentationRegistry.getInstrumentation()

    private var activity: WhereqDosboxActivity? = null

    @Before
    fun launchTheApp() {
        assumeTrue(
            "the predictive-back callback only exists on API 33 and newer",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        )
        val intent = Intent(
            InstrumentationRegistry.getInstrumentation().targetContext,
            WhereqDosboxActivity::class.java
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity = instrumentation.startActivitySync(intent) as WhereqDosboxActivity
    }

    @After
    fun closeTheApp() {
        activity?.let {
            instrumentation.runOnMainSync {
                if (!it.isFinishing) {
                    it.finish()
                }
            }
        }
        activity = null
    }

    @Test
    fun backClosesTheLauncherAndKeepsTheAppAlive() {
        val activity = activity!!

        onMain { activity.showLauncher() }
        assertTrue("the launcher should be open", waitFor { launcherVisible(activity) })

        pressSystemBack()

        assertTrue(
            "the back press should close the launcher",
            waitFor { !launcherVisible(activity) }
        )
        assertFalse(
            "the back press must not leave the app while the launcher was open",
            onMain { activity.isFinishing }
        )
    }

    @Test
    fun backLeavesTheAppWhenThereIsNothingToClose() {
        val activity = activity!!

        assertFalse("the launcher should be closed to begin with", launcherVisible(activity))

        pressSystemBack()

        assertTrue(
            "with nothing of ours open, the back press should leave the app",
            waitFor { onMain { activity.isFinishing } }
        )
    }

    /* ------------------------------------------------------------------ */

    private fun launcherVisible(activity: WhereqDosboxActivity): Boolean =
        onMain { activity.isLauncherVisible() }

    /** Presses the back button the way the system does. */
    private fun pressSystemBack() {
        val shell = instrumentation.uiAutomation.executeShellCommand("input keyevent 4")
        shell.close()
    }

    /** Runs [block] on the UI thread and returns its result. */
    private fun <T> onMain(block: () -> T): T {
        var result: Any? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /** Waits for [condition] to become true, up to a few seconds. */
    private fun waitFor(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                return true
            }
            instrumentation.waitForIdleSync()
            Thread.sleep(50)
        }
        return condition()
    }
}
