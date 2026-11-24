package com.whereq.dosbox

import android.view.KeyEvent

/**
 * Forwards the Android keyboard into DOSBox.
 *
 * SDL2's Android back end has no hardware-key path at all: the Java layer
 * (`org.libsdl.app.SDLActivity`) only feeds the IME, and the native side never
 * sees an `AKEY_EVENT`. A key pressed on a Bluetooth or USB keyboard therefore
 * never reaches the emulator - and neither does an injected
 * `adb shell input keyevent`. DOSBox, which only knows SDL events, sees
 * nothing at all.
 *
 * The on-screen keyboard ([VirtualKeyboardView]) works around that by pushing
 * SDL key events into DOSBox's event queue itself
 * ([DosboxNative.nativePushKey]); this class does the same for the real
 * keyboard, so a phone with a keyboard can play the DOS games whose start key
 * is not on the on-screen keyboard face - Mortal Kombat starts on F1, for
 * example.
 *
 * The Android key code is mapped to a DOSBox key *name* ("f1", "a", "up", …)
 * and the name is turned into the SDL scancode by the native table in
 * [DosboxNative.nativeScancodeFromName] - the same table the on-screen
 * keyboard uses, so both paths end in exactly the same scancode.
 */
object HardwareKeyboard {

    /** Android key code -> DOSBox key name. */
    private val NAMES: Map<Int, String> = buildNames()

    /** DOSBox key name -> SDL scancode; resolved once per name. */
    private val scancodes = HashMap<String, Int>()

    /** A physical Shift / Ctrl / Alt that is currently held down. */
    private var shiftHeld = false
    private var ctrlHeld = false
    private var altHeld = false

    /**
     * Pushes one Android key event into DOSBox.
     *
     * @param event the key event to forward.
     * @return true if the event was a keyboard key that DOSBox now knows
     *         about, false if it should keep travelling through the normal
     *         Android dispatch (the back button, the volume keys, …).
     */
    @Synchronized
    fun forward(event: KeyEvent): Boolean {
        val name = NAMES[event.keyCode] ?: return false
        val pressed = event.action == KeyEvent.ACTION_DOWN

        // Android repeats a held key by sending more ACTION_DOWN events.
        // DOSBox does its own auto-repeat (the BIOS keyboard timer in
        // src/hardware/keyboard.cpp), and every extra event would be one more
        // scancode in the BIOS buffer, so only the first press is forwarded.
        if (pressed && event.repeatCount > 0) {
            return true
        }

        // A modifier that is physically down is already pressed in the BIOS,
        // so it must not be wrapped around the key a second time.
        when (name) {
            "lshift", "rshift" -> shiftHeld = pressed
            "lctrl", "rctrl" -> ctrlHeld = pressed
            "lalt", "ralt" -> altHeld = pressed
        }

        if (!pressed) {
            push(name, false)
            return true
        }

        // DOS reads the shift state of the BIOS, not the Android one, so a
        // key that was pressed with a modifier held has to carry it along.
        // Keyboards that report the modifier only in the event's meta state
        // (and never send a key event for the modifier itself) are covered by
        // this as well.
        val shift = event.isShiftPressed && !shiftHeld
        val ctrl = event.isCtrlPressed && !ctrlHeld
        val alt = event.isAltPressed && !altHeld
        if (shift) push("lshift", true)
        if (ctrl) push("lctrl", true)
        if (alt) push("lalt", true)
        push(name, true)
        if (alt) push("lalt", false)
        if (ctrl) push("lctrl", false)
        if (shift) push("lshift", false)
        return true
    }

    /** Pushes one key into DOSBox; an unknown name is ignored. */
    private fun push(name: String, pressed: Boolean) {
        try {
            val scancode = scancodes.getOrPut(name) { DosboxNative.nativeScancodeFromName(name) }
            if (scancode == 0) {
                return
            }
            DosboxNative.nativePushKey(scancode, pressed)
        } catch (e: UnsatisfiedLinkError) {
            // The library is not loaded (yet): there is nothing to push into.
        }
    }

    /**
     * The Android key codes DOSBox can use, as DOSBox key names.
     *
     * The names are the ones of DOSBox's own key table (`DefaultKeys[]` in
     * `src/gui/sdl_mapper.cpp`), which is also what the native table in
     * [DosboxNative.nativeScancodeFromName] knows.
     */
    private fun buildNames(): Map<Int, String> {
        val names = HashMap<Int, String>()
        // The letters, the digits and the function keys have consecutive key
        // codes, so they are filled in in one go.
        for (letter in 'a'..'z') {
            names[KeyEvent.KEYCODE_A + (letter - 'a')] = letter.toString()
        }
        for (digit in 0..9) {
            names[KeyEvent.KEYCODE_0 + digit] = digit.toString()
        }
        for (fn in 1..12) {
            names[KeyEvent.KEYCODE_F1 + (fn - 1)] = "f$fn"
        }
        for (key in 0..9) {
            names[KeyEvent.KEYCODE_NUMPAD_0 + key] = "kp_$key"
        }
        names[KeyEvent.KEYCODE_SPACE] = "space"
        names[KeyEvent.KEYCODE_ENTER] = "enter"
        names[KeyEvent.KEYCODE_NUMPAD_ENTER] = "kp_enter"
        names[KeyEvent.KEYCODE_ESCAPE] = "esc"
        names[KeyEvent.KEYCODE_TAB] = "tab"
        // The key on a keyboard that deletes backwards is KEYCODE_DEL; the one
        // that deletes forwards is KEYCODE_FORWARD_DEL.
        names[KeyEvent.KEYCODE_DEL] = "bspace"
        names[KeyEvent.KEYCODE_FORWARD_DEL] = "delete"
        names[KeyEvent.KEYCODE_INSERT] = "insert"
        names[KeyEvent.KEYCODE_DPAD_UP] = "up"
        names[KeyEvent.KEYCODE_DPAD_DOWN] = "down"
        names[KeyEvent.KEYCODE_DPAD_LEFT] = "left"
        names[KeyEvent.KEYCODE_DPAD_RIGHT] = "right"
        names[KeyEvent.KEYCODE_SHIFT_LEFT] = "lshift"
        names[KeyEvent.KEYCODE_SHIFT_RIGHT] = "rshift"
        names[KeyEvent.KEYCODE_CTRL_LEFT] = "lctrl"
        names[KeyEvent.KEYCODE_CTRL_RIGHT] = "rctrl"
        names[KeyEvent.KEYCODE_ALT_LEFT] = "lalt"
        names[KeyEvent.KEYCODE_ALT_RIGHT] = "ralt"
        names[KeyEvent.KEYCODE_CAPS_LOCK] = "capslock"
        names[KeyEvent.KEYCODE_NUM_LOCK] = "numlock"
        names[KeyEvent.KEYCODE_SCROLL_LOCK] = "scrolllock"
        names[KeyEvent.KEYCODE_PAGE_UP] = "pageup"
        names[KeyEvent.KEYCODE_PAGE_DOWN] = "pagedown"
        names[KeyEvent.KEYCODE_MOVE_HOME] = "home"
        names[KeyEvent.KEYCODE_MOVE_END] = "end"
        names[KeyEvent.KEYCODE_COMMA] = "comma"
        names[KeyEvent.KEYCODE_PERIOD] = "period"
        names[KeyEvent.KEYCODE_SLASH] = "slash"
        names[KeyEvent.KEYCODE_MINUS] = "minus"
        names[KeyEvent.KEYCODE_EQUALS] = "equals"
        names[KeyEvent.KEYCODE_LEFT_BRACKET] = "lbracket"
        names[KeyEvent.KEYCODE_RIGHT_BRACKET] = "rbracket"
        names[KeyEvent.KEYCODE_BACKSLASH] = "backslash"
        names[KeyEvent.KEYCODE_SEMICOLON] = "semicolon"
        names[KeyEvent.KEYCODE_APOSTROPHE] = "quote"
        names[KeyEvent.KEYCODE_GRAVE] = "grave"
        names[KeyEvent.KEYCODE_NUMPAD_DIVIDE] = "kp_divide"
        names[KeyEvent.KEYCODE_NUMPAD_MULTIPLY] = "kp_multiply"
        names[KeyEvent.KEYCODE_NUMPAD_SUBTRACT] = "kp_minus"
        names[KeyEvent.KEYCODE_NUMPAD_ADD] = "kp_plus"
        names[KeyEvent.KEYCODE_NUMPAD_DOT] = "kp_period"
        return names
    }
}
