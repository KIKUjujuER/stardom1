package com.whereq.dosbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The back button, on the JVM.
 *
 * The decision itself is what Android 13+ stopped handing to the activity, so
 * it is kept in [resolveBackAction] where it can be checked without a device.
 * The device-side test that presses the real back button lives in
 * `src/androidTest/java/com/whereq/dosbox/BackHandlingTest.kt`.
 */
class BackActionTest {

    @Test
    fun theLauncherWinsOverTheKeyboard() {
        // The launcher is drawn on top of the keyboard and has its own Close
        // button, so a back press while it is open is about the launcher.
        assertEquals(
            BackAction.CLOSE_LAUNCHER,
            resolveBackAction(
                launcherVisible = true,
                gameModeVisible = false,
                keyboardPanelVisible = true
            )
        )
        assertEquals(
            BackAction.CLOSE_LAUNCHER,
            resolveBackAction(
                launcherVisible = true,
                gameModeVisible = false,
                keyboardPanelVisible = false
            )
        )
    }

    @Test
    fun theGamePadIsLeftBeforeTheKeyboard() {
        // The gamepad covers the keyboard pads, so a back press has to leave
        // the gamepad first - the keyboard is not on screen to be folded.
        assertEquals(
            BackAction.EXIT_GAME_MODE,
            resolveBackAction(
                launcherVisible = false,
                gameModeVisible = true,
                keyboardPanelVisible = true
            )
        )
        assertEquals(
            BackAction.EXIT_GAME_MODE,
            resolveBackAction(
                launcherVisible = false,
                gameModeVisible = true,
                keyboardPanelVisible = false
            )
        )
    }

    @Test
    fun theLauncherWinsOverTheGamePad() {
        // The launcher can be opened from the gamepad, and it is drawn on top
        // of it, so a back press is about the launcher.
        assertEquals(
            BackAction.CLOSE_LAUNCHER,
            resolveBackAction(
                launcherVisible = true,
                gameModeVisible = true,
                keyboardPanelVisible = true
            )
        )
    }

    @Test
    fun theKeyboardFoldsWhenTheLauncherIsClosed() {
        assertEquals(
            BackAction.FOLD_KEYBOARD,
            resolveBackAction(
                launcherVisible = false,
                gameModeVisible = false,
                keyboardPanelVisible = true
            )
        )
    }

    @Test
    fun nothingOpenLeavesTheApp() {
        assertEquals(
            BackAction.FINISH,
            resolveBackAction(
                launcherVisible = false,
                gameModeVisible = false,
                keyboardPanelVisible = false
            )
        )
    }

    @Test
    fun thePressesWalkTheAppBackOut() {
        // One press per thing that is open, in the order they are on screen:
        // the launcher closes, the gamepad goes back to the keyboard, the
        // keyboard folds, and only then does the press leave the app.
        var launcherVisible = true
        var gameModeVisible = true
        var keyboardVisible = true

        val steps = mutableListOf<BackAction>()
        repeat(4) {
            val action = resolveBackAction(launcherVisible, gameModeVisible, keyboardVisible)
            steps.add(action)
            when (action) {
                BackAction.CLOSE_LAUNCHER -> launcherVisible = false
                BackAction.EXIT_GAME_MODE -> gameModeVisible = false
                BackAction.FOLD_KEYBOARD -> keyboardVisible = false
                BackAction.FINISH -> Unit
            }
        }

        assertEquals(
            listOf(
                BackAction.CLOSE_LAUNCHER,
                BackAction.EXIT_GAME_MODE,
                BackAction.FOLD_KEYBOARD,
                BackAction.FINISH
            ),
            steps
        )
    }

    @Test
    fun theManifestDoesNotOptOutOfPredictiveBack() {
        // Opting out is what made the back button do nothing at all on API 33+:
        // the system sends KEYCODE_BACK to the activity, SDL2's surface takes
        // it, and neither onKeyDown() nor onBackPressed() is ever called. The
        // app handles the back gesture through an OnBackInvokedCallback
        // instead, so the manifest must not opt out.
        val manifest = manifestFile().readText()
        val attribute = Regex("""android:enableOnBackInvokedCallback\s*=\s*"([^"]*)"""")
            .find(manifest)
        val value = attribute?.groupValues?.get(1)
        val shown = value ?: "unset"
        assertTrue(
            "the app must not opt out of predictive back, but the manifest says " +
                "\"enableOnBackInvokedCallback=\\\"$shown\\\"\"",
            value != "false"
        )
    }

    /** Finds `src/main/AndroidManifest.xml`, from the module directory or the
     *  repository root - the unit test's working directory is one or the
     *  other depending on how Gradle was started. */
    private fun manifestFile(): File {
        val roots = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        for (root in roots) {
            for (relative in listOf("src/main/AndroidManifest.xml", "android/app/src/main/AndroidManifest.xml")) {
                val candidate = File(root, relative)
                if (candidate.isFile) {
                    return candidate
                }
            }
        }
        throw AssertionError(
            "could not find src/main/AndroidManifest.xml below " +
                System.getProperty("user.dir")
        )
    }
}
