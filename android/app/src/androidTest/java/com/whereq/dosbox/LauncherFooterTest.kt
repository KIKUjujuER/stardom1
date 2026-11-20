package com.whereq.dosbox

import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The foot of the bundle launcher.
 *
 * The launcher is the one screen that is always reachable, so the two
 * documents that have to be reachable from the app live in its footer: the
 * licence (which the GPL requires to travel with the program) and the privacy
 * policy.
 *
 * This test checks that both links are really there, and that the privacy
 * link really asks the app to open the privacy policy.
 *
 * The link itself is checked against the repository by
 * `PrivacyPolicyTest` on the JVM - the policy has to live in the file the
 * link names, and that is a question about the source tree, not about the
 * device.
 */
@RunWith(AndroidJUnit4::class)
class LauncherFooterTest {

    private val instrumentation: Instrumentation =
        InstrumentationRegistry.getInstrumentation()

    private var activity: WhereqDosboxActivity? = null

    @Before
    fun launchTheApp() {
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
    fun theFooterLinksToTheLicencesAndToThePrivacyPolicy() {
        openTheLauncher()

        val labels = allText()
        assertTrue(
            "the footer should link to the licences, but it carries: $labels",
            labels.contains("Licences")
        )
        assertTrue(
            "the footer should link to the privacy policy, but it carries: $labels",
            labels.contains("Privacy policy")
        )
    }

    @Test
    fun thePrivacyLinkAsksTheAppToOpenThePrivacyPolicy() {
        openTheLauncher()

        // A launcher of its own, with a listener that only writes down what
        // it was asked to do. The footer's links are wired to the listener,
        // so this is the same code path the app takes.
        val listener = RecordingListener()
        val launcher = GameLauncherView(activity!!, listener)
        onMain { launcher.measure(0, 0) }

        val link = findLink(launcher, "Privacy policy")
        assertNotNull("the footer should have a privacy-policy link", link)

        onMain { link?.performClick() }

        assertEquals(
            "tapping the privacy link should ask the app to open the privacy policy",
            listOf("onPrivacyPolicyRequested"),
            listener.asked
        )
    }

    @Test
    fun theLicencesLinkAsksTheAppToOpenTheLicenceScreen() {
        openTheLauncher()

        val listener = RecordingListener()
        val launcher = GameLauncherView(activity!!, listener)
        onMain { launcher.measure(0, 0) }

        val link = findLink(launcher, "Licences")
        assertNotNull("the footer should have a licences link", link)

        onMain { link?.performClick() }

        assertEquals(
            "tapping the licences link should ask the app to open the licence screen",
            listOf("onOpenSourceLicensesRequested"),
            listener.asked
        )
    }

    /* ------------------------------------------------------------------ */

    /** Opens the launcher and waits for it to be on screen. */
    private fun openTheLauncher() {
        val activity = activity!!
        onMain { activity.showLauncher() }
        assertTrue("the launcher should be open", waitFor { activity.isLauncherVisible() })
    }

    /** @return the link with that label, inside [view] or below it. */
    private fun findLink(view: View, label: String): TextView? {
        var found: TextView? = null
        onMain {
            collect(view).filterIsInstance<TextView>()
                .firstOrNull { it.text.toString() == label }
                ?.let { found = it }
        }
        return found
    }

    /** @return the text of every TextView on the screen. */
    private fun allText(): String {
        val views = mutableListOf<View>()
        onMain { collect(activity!!.window.decorView).forEach { views.add(it) } }
        return views.filterIsInstance<TextView>().joinToString(" | ") { it.text.toString() }
    }

    /** Every view below [view], [view] itself included. */
    private fun collect(view: View): List<View> {
        val views = mutableListOf<View>()
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                views.addAll(collect(view.getChildAt(i)))
            }
        }
        views.add(view)
        return views
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

    /**
     * A listener that writes down what it was asked to do.
     *
     * Every method of the interface is here; only the two the footer uses
     * are of interest to the tests, and the rest do nothing.
     */
    private class RecordingListener : GameLauncherView.Listener {
        /** The methods that were called, in order. */
        val asked = mutableListOf<String>()

        override fun onInstallRequested() { asked.add("onInstallRequested") }
        override fun onBrowseRequested() { asked.add("onBrowseRequested") }
        override fun onPlayRequested(bundle: GameBundle) { asked.add("onPlayRequested") }
        override fun onEditLaunchRequested(bundle: GameBundle) { asked.add("onEditLaunchRequested") }
        override fun onLaunchChosen(bundle: GameBundle, script: String) { asked.add("onLaunchChosen") }
        override fun onDeleteRequested(bundle: GameBundle) { asked.add("onDeleteRequested") }
        override fun onExitToPromptRequested() { asked.add("onExitToPromptRequested") }
        override fun onConfigRequested() { asked.add("onConfigRequested") }
        override fun onResumeRequested() { asked.add("onResumeRequested") }
        override fun onSaveStateRequested(bundle: GameBundle, slot: Int) { asked.add("onSaveStateRequested") }
        override fun onLoadStateRequested(bundle: GameBundle, slot: Int) { asked.add("onLoadStateRequested") }
        override fun stateSlotLabel(bundle: GameBundle, slot: Int): String? = null
        override fun hasSavesFolder(): Boolean = false
        override fun onConnectSavesFolderRequested(bundle: GameBundle) { asked.add("onConnectSavesFolderRequested") }
        override fun onCloseRequested() { asked.add("onCloseRequested") }
        override fun onOpenSourceLicensesRequested() { asked.add("onOpenSourceLicensesRequested") }
        override fun onPrivacyPolicyRequested() { asked.add("onPrivacyPolicyRequested") }
    }
}
