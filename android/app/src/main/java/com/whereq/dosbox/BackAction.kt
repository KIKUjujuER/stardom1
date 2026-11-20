package com.whereq.dosbox

/**
 * What one press of the back button should do.
 *
 * The back button is the app's only "undo" gesture, and WhereQ-DOSBox has
 * three things that can be dismissed with it: the game launcher, the Game Mode
 * gamepad and the on-screen keyboard. When none of them is open, the press
 * leaves the app - which is what the back button has always done here.
 *
 * The decision lives in its own file, away from the activity, because it is the
 * part that has to be right: it is the piece that Android 13+ stopped
 * delivering to the activity (see [WhereqDosboxActivity]), and it is the piece
 * the regression test checks.
 */
enum class BackAction {

    /** The game launcher is open: close it and stay in the app. */
    CLOSE_LAUNCHER,

    /** The Game Mode gamepad is on screen: go back to the keyboard pads. */
    EXIT_GAME_MODE,

    /** The on-screen keyboard panel is up: fold it away and stay in the app. */
    FOLD_KEYBOARD,

    /** Nothing of ours is open: the press leaves the app. */
    FINISH,
}

/**
 * Decides what the back button does, given what is on screen.
 *
 * The launcher wins over everything: it is the larger surface, it is drawn on
 * top of everything, and it has its own Close button, so a back press while it
 * is open is about the launcher and nothing else. The Game Mode comes next -
 * it covers the keyboard pads, so it has to be left before the keyboard can be
 * folded.
 *
 * @param launcherVisible whether the game launcher is currently on screen
 * @param gameModeVisible whether the Game Mode gamepad is currently on screen
 * @param keyboardPanelVisible whether the on-screen keyboard panel is up
 * @return the action the back press performs
 */
fun resolveBackAction(
    launcherVisible: Boolean,
    gameModeVisible: Boolean,
    keyboardPanelVisible: Boolean
): BackAction =
    when {
        launcherVisible -> BackAction.CLOSE_LAUNCHER
        gameModeVisible -> BackAction.EXIT_GAME_MODE
        keyboardPanelVisible -> BackAction.FOLD_KEYBOARD
        else -> BackAction.FINISH
    }
