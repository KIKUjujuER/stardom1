package com.whereq.dosbox

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The licence screen on a device.
 *
 * The app embeds the DOSBox core, which is GPLv2: the licence and the
 * copyright notices have to travel with the program, and the complete source
 * has to be reachable from the app. This test checks that the screen really
 * carries all three, from the bundled assets rather than from a copy in the
 * source tree.
 *
 * It also writes a picture of the screen to the app's external files
 * directory, so the rendered page can be looked at without a screenshot of
 * the whole device:
 *
 *     adb pull /sdcard/Android/data/com.whereq.dosbox/files/licences.png
 */
@RunWith(AndroidJUnit4::class)
class LicensesActivityTest {

    private var activity: LicensesActivity? = null

    @Before
    fun openTheScreen() {
        val intent = android.content.Intent(
            InstrumentationRegistry.getInstrumentation().targetContext,
            LicensesActivity::class.java
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        activity = InstrumentationRegistry.getInstrumentation()
            .startActivitySync(intent) as LicensesActivity
    }

    @After
    fun closeTheScreen() {
        activity?.let {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                if (!it.isFinishing) {
                    it.finish()
                }
            }
        }
        activity = null
    }

    @Test
    fun theScreenCarriesTheLicenceTheNoticesAndTheSource() {
        val text = allText()

        assertTrue(
            "the licence screen should name the licence it carries",
            text.contains("GNU General Public License")
        )
        assertTrue(
            "the licence screen should link to the complete source",
            text.contains("https://github.com/whereq/whereq-dosbox")
        )
        assertTrue(
            "the licence screen should show the licence text of the bundled COPYING",
            text.contains("GNU GENERAL PUBLIC LICENSE")
        )
        assertTrue(
            "the licence screen should show the notices of the bundled NOTICE",
            text.contains("DOSBox")
        )
    }

    @Test
    fun theBundledLicenceFilesAreRead() {
        // The screen reads its text from the assets, so the assets have to be
        // in the APK. Reading them here is the same read the screen does.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val copying = context.assets.open("COPYING").bufferedReader().use { it.readText() }
        val notice = context.assets.open("NOTICE").bufferedReader().use { it.readText() }

        assertTrue("COPYING should hold the GPL", copying.contains("GNU GENERAL PUBLIC LICENSE"))
        assertTrue(
            "NOTICE should hold the DOSBox attribution",
            notice.contains("DOSBox")
        )
        assertTrue(
            "NOTICE should name this port, not the sibling one",
            notice.contains("WhereQ-DOSBox") && !notice.contains("A-DOS")
        )
    }

    @Test
    fun theScreenCanBeCaptured() {
        val file = saveAPictureOfTheScreen()
        assertTrue("the licence screen should be drawable", file != null && file.length() > 0)
    }

    /* ------------------------------------------------------------------ */

    /** @return the text of every TextView on the screen. */
    private fun allText(): String {
        val views = mutableListOf<View>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            collect(activity!!.window.decorView, views)
        }
        return views.filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
    }

    private fun collect(view: View, into: MutableList<View>) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collect(view.getChildAt(i), into)
            }
        }
        into.add(view)
    }

    /**
     * Writes a picture of the screen to the app's external files directory.
     *
     * The view is drawn into a bitmap rather than photographed, so the picture
     * does not depend on what else is on the device's screen.
     */
    private fun saveAPictureOfTheScreen(): File? {
        var bitmap: Bitmap? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = activity?.window?.decorView
            bitmap = if (view == null || view.width <= 0 || view.height <= 0) {
                null
            } else {
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                    view.draw(Canvas(it))
                }
            }
        }
        val image = bitmap ?: return null

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // The app's own external files directory: the app can write there,
        // and the shell can read it.
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "licences.png")
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()

        // The test runner uninstalls the app when the run is over, so the
        // picture is copied out while the app is still installed.
        copyOutOfTheApp(context, file)
        return file
    }

    /** Copies the picture to the shared storage, where it can be pulled. */
    private fun copyOutOfTheApp(context: android.content.Context, file: File) {
        val source = file.absolutePath
        val command = "cp $source /sdcard/wq-licences.png"
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        val output = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .use { it.readBytes().toString(Charsets.UTF_8) }
        android.util.Log.i("LicensesActivityTest", "copy '$command' said: $output")
    }
}
