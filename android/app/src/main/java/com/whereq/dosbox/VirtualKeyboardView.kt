package com.whereq.dosbox

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * On-screen keyboard for the DOSBox shell.
 *
 * How a key press reaches DOSBox:
 *  1. the key is turned into an SDL key event carrying the right
 *     `SDL_Scancode` and pushed into SDL's event queue by
 *     [DosboxNative.nativePushKey];
 *  2. DOSBox's `GFX_Events()` hands the event to the mapper
 *     (`src/gui/sdl_mapper.cpp`), which indexes its bind list by the scancode
 *     and activates the matching `CKeyEvent`;
 *  3. that event calls `KEYBOARD_AddKey()`, which fills the BIOS keyboard buffer
 *     the DOS shell reads.
 *
 * So no DOSBox patch is needed — the virtual keyboard is just another producer
 * of SDL key events, exactly like the hardware keyboard.
 *
 * **Layout.** This view is a transparent full-screen overlay on top of the SDL
 * surface. The DOS picture is 4:3 and centred, so in landscape a phone has a
 * black bar on either side of it. The keys live in those bars, laid out like a
 * handheld console — the picture in the middle, one hand on each side:
 *
 *  - **pads** (a normal landscape phone): the *left pad* has the left half of
 *    a split QWERTY on top and the arrow keys, the D-pad, under the left
 *    thumb; the *right pad* has the right half of the QWERTY on top and the
 *    action keys — Ctrl, Alt, Shift, Space, Enter — under the right thumb.
 *    The QWERTY halves switch between three layers (letters, symbols,
 *    function keys). Nothing covers the DOS picture, so a game is steered
 *    with the left thumb and fired with the right, and typing is two-thumbed;
 *  - **columns** (a 16:9 screen, bars too narrow for half a keyboard): one
 *    column of keys per side, arrows left and action keys right; the full
 *    QWERTY folds up from the bottom on demand;
 *  - **bar** (portrait, or a 4:3 tablet): a slim bar at the bottom plus the
 *    fold-up QWERTY.
 *
 * The part of the overlay above the DOS picture does not intercept touches, so
 * a tap there still reaches DOSBox as a mouse click.
 *
 * **Modifiers** (Shift, Ctrl, Alt) work both ways a touch screen needs:
 *  - a *tap* arms the modifier; it stays pressed until the next ordinary key
 *    is typed (or until it is tapped again) — one-finger typing of `Ctrl`+`C`;
 *  - a *hold* keeps it pressed for exactly as long as the finger is down, like
 *    a real key — which is what a game wants from Ctrl (fire) or Alt (strafe)
 *    while the other thumb is on the arrows.
 */
class VirtualKeyboardView(context: Context) : FrameLayout(context) {

    /**
     * Told when the user taps the launcher button.
     *
     * The keyboard does not know about the bundle launcher; it only reports that
     * the button was pressed.
     */
    fun interface OnFilesRequestedListener {
        fun onFilesRequested()
    }

    /** Set by the activity; may be null, then the button does nothing. */
    private var launcherListener: OnFilesRequestedListener? = null

    /** Installs the listener that is told about a tap on the launcher button. */
    fun setOnFilesRequestedListener(listener: OnFilesRequestedListener?) {
        launcherListener = listener
    }

    /** key name -> numeric SDL scancode. */
    private val scancodes = HashMap<String, Int>()

    /** key name of a modifier -> is it currently pressed inside DOSBox. */
    private val armed = HashMap<String, Boolean>()

    /**
     * Modifier key name -> how many fingers are on it right now. A modifier can
     * appear twice (e.g. Shift in both a side column and the fold-up panel), so
     * a plain set could not tell "both fingers lifted" from "one of two lifted"
     * and would release the modifier while a finger was still down. The count
     * fixes that: the modifier arms on 0->1 and releases on 1->0.
     */
    private val heldCount = HashMap<String, Int>()

    /**
     * Every scancode currently pressed *down* in DOSBox. A layout rebuild
     * removes the key views mid-gesture without an ACTION_UP, so without this
     * an ordinary key held at that moment (an arrow, Space) would stay stuck
     * down in the emulator. The rebuild releases everything in here.
     */
    private val downScancodes = HashSet<Int>()

    /** Held modifiers that an ordinary key was typed with. */
    private val usedWhileHeld = HashSet<String>()

    /** Held modifiers whose touch *dis*armed them, so lifting must not re-arm. */
    private val disarmedByTouch = HashSet<String>()

    /** key name of a modifier -> when the finger came down (uptimeMillis). */
    private val heldSince = HashMap<String, Long>()

    /** key name -> every view showing that modifier, to show the armed state. */
    private val modifierViews = HashMap<String, ArrayList<TextView>>()

    /** The fold-up QWERTY; null in the pads layout, which does not need one. */
    private var panel: LinearLayout? = null
    private var symbolScroll: HorizontalScrollView? = null
    private var toggleButton: TextView? = null
    private var panelVisible = false

    /**
     * The key layers of the pads, indexed by `LAYER_*`: each has a left and a
     * right half. Empty outside the pads layout.
     */
    private val layerViews = ArrayList<ArrayList<View>>()
    private var layer = LAYER_LETTERS
    private var symbolsKey: TextView? = null
    private var functionsKey: TextView? = null

    /** `MODE_*` of the layout that is currently built, or -1 for none yet. */
    private var mode = -1

    /** Width of one letterbox bar the current layout was built for. */
    private var builtBarWidth = -1

    init {
        // The overlay itself must never swallow a touch over the DOS picture:
        // it is not clickable, so a tap on the empty part falls through to the
        // SDL surface below and becomes a DOS mouse click.
        isClickable = false
        // Two thumbs on two different keys are two independent touches.
        isMotionEventSplittingEnabled = true
        // The keys are laid out in onSizeChanged(), once the geometry (and thus
        // the width of the bars next to the DOS picture) is known.
    }

    /* ------------------------------------------------------------- layout */

    /**
     * Rebuilds the keys for the current geometry — see the class comment for
     * the three layouts.
     */
    private fun rebuildControls(w: Int, h: Int) {
        if (w <= 0 || h <= 0) {
            return
        }
        // The DOS picture is 4:3 and fills the height in landscape, so each side
        // bar is this wide. (Matches DOSBox's own letterbox clip.)
        val barWidth = if (w > h) maxOf(0, (w - h * 4 / 3) / 2) else 0

        val newMode = when {
            barWidth >= dp(DP_MIN_PAD_WIDTH) -> MODE_PADS
            barWidth >= dp(DP_MIN_COLUMN_WIDTH) -> MODE_COLUMNS
            else -> MODE_BAR
        }
        if (newMode == mode && barWidth == builtBarWidth) {
            return
        }

        // Start from nothing. Anything still pressed inside DOSBox has to be
        // released first — the key views are about to disappear, so their
        // ACTION_UP would never arrive and the key would stick down. This covers
        // the modifiers (armed/held) and any ordinary key a finger is on.
        releaseModifiers(force = true)
        for (scancode in downScancodes.toList()) {
            DosboxNative.nativePushKey(scancode, false)
        }
        downScancodes.clear()
        removeAllViews()
        modifierViews.clear()
        armed.clear()
        heldCount.clear()
        usedWhileHeld.clear()
        disarmedByTouch.clear()
        heldSince.clear()
        layerViews.clear()
        layer = LAYER_LETTERS
        symbolsKey = null
        functionsKey = null
        panel = null
        symbolScroll = null
        toggleButton = null
        panelVisible = false

        when (newMode) {
            MODE_PADS -> {
                // No wider than a thumb can cover: on a very wide screen the
                // pads hug the edges and the rest of the bar stays black.
                val padWidth = minOf(barWidth, dp(DP_MAX_PAD_WIDTH))
                addView(buildPad(left = true), LayoutParams(padWidth, LayoutParams.MATCH_PARENT).apply {
                    gravity = Gravity.START
                })
                addView(buildPad(left = false), LayoutParams(padWidth, LayoutParams.MATCH_PARENT).apply {
                    gravity = Gravity.END
                })
            }
            MODE_COLUMNS -> {
                buildPanel()
                val columnWidth = minOf(barWidth, dp(DP_MAX_COLUMN_WIDTH))
                addView(buildColumn(left = true), LayoutParams(columnWidth, LayoutParams.MATCH_PARENT).apply {
                    gravity = Gravity.START
                })
                addView(buildColumn(left = false), LayoutParams(columnWidth, LayoutParams.MATCH_PARENT).apply {
                    gravity = Gravity.END
                })
            }
            else -> {
                buildPanel()
                addView(buildBottomBar(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.BOTTOM
                })
            }
        }
        mode = newMode
        builtBarWidth = barWidth
    }

    /* --------------------------------------------------------------- pads */

    /**
     * One of the two pads beside the DOS picture.
     *
     * Top to bottom: a row of utility keys, four rows of the split QWERTY that
     * switch between three layers, then the thumb block — the arrow keys on
     * the left pad, the action keys on the right one. The rows share the full
     * height, and the thumb block gets the taller keys.
     */
    private fun buildPad(left: Boolean): LinearLayout {
        val pad = LinearLayout(context)
        pad.orientation = LinearLayout.VERTICAL
        pad.isMotionEventSplittingEnabled = true
        pad.setPadding(dp(2), dp(2), dp(2), dp(2))
        pad.setBackgroundColor(COLOR_TOOLBAR)

        val top = LinearLayout(context)
        top.orientation = LinearLayout.HORIZONTAL
        if (left) {
            top.addView(fill(actionKey("File", COLOR_KEY_ACCENT) { launcherListener?.onFilesRequested() }, 1f, false))
            top.addView(fill(makeKey("Esc", "esc", "c", "k", 0, COLOR_KEY_DARK), 1f, false))
            top.addView(fill(makeKey("Tab", "tab", "c", "k", 0, COLOR_KEY_DARK), 1f, false))
        } else {
            symbolsKey = actionKey("#+=", COLOR_KEY_DARK) {
                showLayer(if (layer == LAYER_SYMBOLS) LAYER_LETTERS else LAYER_SYMBOLS)
            }
            functionsKey = actionKey("Fn", COLOR_KEY_DARK) {
                showLayer(if (layer == LAYER_FUNCTIONS) LAYER_LETTERS else LAYER_FUNCTIONS)
            }
            top.addView(fill(symbolsKey!!, 1f, false))
            top.addView(fill(functionsKey!!, 1f, false))
            top.addView(fill(makeKey("⌫", "bspace", "c", "k", 0, COLOR_KEY_DARK), 1f, false, 16f))
        }
        pad.addView(fillRow(top))

        val layers = FrameLayout(context)
        layers.isMotionEventSplittingEnabled = true
        val halves = if (left) LEFT_LAYERS else RIGHT_LAYERS
        halves.forEachIndexed { index, rows ->
            val view = buildLayer(rows)
            view.visibility = if (index == LAYER_LETTERS) VISIBLE else GONE
            while (layerViews.size <= index) {
                layerViews.add(ArrayList())
            }
            layerViews[index].add(view)
            layers.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        pad.addView(layers, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 4f))

        val thumb = LinearLayout(context)
        thumb.orientation = LinearLayout.VERTICAL
        thumb.isMotionEventSplittingEnabled = true
        if (left) {
            // The D-pad: up on top, left and right side by side, down below.
            val middle = LinearLayout(context)
            middle.orientation = LinearLayout.HORIZONTAL
            middle.addView(fill(makeKey("←", "left", "c", "k", 0), 1f, false, 20f))
            middle.addView(fill(makeKey("→", "right", "c", "k", 0), 1f, false, 20f))
            thumb.addView(fill(makeKey("↑", "up", "c", "k", 0), 1f, true, 20f))
            thumb.addView(fillRow(middle))
            thumb.addView(fill(makeKey("↓", "down", "c", "k", 0), 1f, true, 20f))
        } else {
            // The action keys: what DOS games fire, jump, run and confirm with.
            val upper = LinearLayout(context)
            upper.orientation = LinearLayout.HORIZONTAL
            upper.addView(fill(makeKey("Alt", "lalt", "c", "m", 0), 1f, false, 13f))
            upper.addView(fill(makeKey("Ctrl", "lctrl", "c", "m", 0), 1f, false, 13f))
            val lower = LinearLayout(context)
            lower.orientation = LinearLayout.HORIZONTAL
            lower.addView(fill(makeKey("Shift", "lshift", "c", "m", 0), 1f, false, 13f))
            lower.addView(fill(makeKey("Space", "space", "c", "k", 0), 1f, false, 13f))
            thumb.addView(fillRow(upper))
            thumb.addView(fillRow(lower))
            thumb.addView(fill(makeKey("Enter", "enter", "c", "k", 0, COLOR_KEY_DARK), 1f, true, 13f))
        }
        pad.addView(thumb, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 3.6f))
        return pad
    }

    /**
     * One half of a layer. A row is a list of keys separated by blanks; a key
     * is `label`, `label:name` or `label:name:kind` (see [makeKey]) — the name
     * defaults to the label and the kind to an ordinary key. `_` is a gap as
     * wide as a key.
     */
    private fun buildLayer(rows: Array<String>): LinearLayout {
        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        column.isMotionEventSplittingEnabled = true
        for (spec in rows) {
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            row.isMotionEventSplittingEnabled = true
            for (item in spec.split(' ')) {
                if (item == "_") {
                    row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                    continue
                }
                // "::" would be ambiguous, so the colon key is spelled out below.
                val parts = item.split(':')
                val label = if (parts[0] == "colon") ":" else parts[0]
                val name = parts.getOrElse(1) { label }
                val kind = parts.getOrElse(2) { "k" }
                val size = if (label.length == 1) 16f else 11f
                row.addView(fill(makeKey(label, name, "c", kind, 0), 1f, false, size))
            }
            column.addView(fillRow(row))
        }
        return column
    }

    private fun showLayer(which: Int) {
        layer = which
        layerViews.forEachIndexed { index, halves ->
            halves.forEach { it.visibility = if (index == which) VISIBLE else GONE }
        }
        symbolsKey?.text = if (which == LAYER_SYMBOLS) "abc" else "#+="
        symbolsKey?.background =
            keyBackground(if (which == LAYER_SYMBOLS) COLOR_KEY_ARMED else COLOR_KEY_DARK)
        functionsKey?.background =
            keyBackground(if (which == LAYER_FUNCTIONS) COLOR_KEY_ARMED else COLOR_KEY_DARK)
    }

    /**
     * Makes [view] share its parent's space instead of having a fixed size:
     * the height of a column when [vertical], the width of a row otherwise.
     */
    private fun <T : View> fill(view: T, weight: Float, vertical: Boolean, textSp: Float = 0f): T {
        val lp = if (vertical) {
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, weight)
        } else {
            LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, weight)
        }
        val margin = dp(1)
        lp.setMargins(margin, margin, margin, margin)
        view.layoutParams = lp
        if (textSp > 0f && view is TextView) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
        }
        return view
    }

    /** Makes a row of keys take an equal share of its column's height. */
    private fun fillRow(row: LinearLayout): LinearLayout {
        row.isMotionEventSplittingEnabled = true
        row.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        return row
    }

    /** A button that is not a DOS key: it runs [action] when tapped. */
    private fun actionKey(label: String, color: Int, action: () -> Unit): TextView {
        val view = makeButton(label, "", "c", "k", 0)
        view.background = keyBackground(color)
        if (color == COLOR_KEY_ACCENT) {
            view.setTextColor(COLOR_TEXT_ON_ACCENT)
        }
        view.setOnClickListener { action() }
        return view
    }

    /* -------------------------------------------------------- fold-up panel */

    /**
     * Builds the foldable full-size keyboard (a bottom-centre overlay). Only the
     * layouts without the pads have it.
     */
    private fun buildPanel() {
        val panel = LinearLayout(context)
        this.panel = panel
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(4), dp(4), dp(4), dp(4))
        panel.setBackgroundColor(COLOR_PANEL)

        panel.addView(
            scrollableRow(
                arrayOf(
                    arrayOf("Esc", "esc", "f", "k"),
                    arrayOf("F1", "f1", "f", "k"),
                    arrayOf("F2", "f2", "f", "k"),
                    arrayOf("F3", "f3", "f", "k"),
                    arrayOf("F4", "f4", "f", "k"),
                    arrayOf("F5", "f5", "f", "k"),
                    arrayOf("F6", "f6", "f", "k"),
                    arrayOf("F7", "f7", "f", "k"),
                    arrayOf("F8", "f8", "f", "k"),
                    arrayOf("F9", "f9", "f", "k"),
                    arrayOf("F10", "f10", "f", "k"),
                    arrayOf("F11", "f11", "f", "k"),
                    arrayOf("F12", "f12", "f", "k"),
                    arrayOf("PrtSc", "printscreen", "f", "k"),
                    arrayOf("Pause", "pause", "f", "k"),
                    arrayOf("Ins", "insert", "f", "k"),
                    arrayOf("Del", "delete", "f", "k"),
                    arrayOf("Home", "home", "f", "k"),
                    arrayOf("End", "end", "f", "k"),
                    arrayOf("PgUp", "pageup", "f", "k"),
                    arrayOf("PgDn", "pagedown", "f", "k"),
                )
            )
        )

        panel.addView(
            weightedRow(
                arrayOf(
                    arrayOf("`", "grave", "1", "k"),
                    arrayOf("1", "1", "1", "k"),
                    arrayOf("2", "2", "1", "k"),
                    arrayOf("3", "3", "1", "k"),
                    arrayOf("4", "4", "1", "k"),
                    arrayOf("5", "5", "1", "k"),
                    arrayOf("6", "6", "1", "k"),
                    arrayOf("7", "7", "1", "k"),
                    arrayOf("8", "8", "1", "k"),
                    arrayOf("9", "9", "1", "k"),
                    arrayOf("0", "0", "1", "k"),
                    arrayOf("-", "minus", "1", "k"),
                    arrayOf("=", "equals", "1", "k"),
                    arrayOf("Bks", "bspace", "2", "k"),
                )
            )
        )

        panel.addView(
            weightedRow(
                arrayOf(
                    arrayOf("Tab", "tab", "1.5", "k"),
                    arrayOf("q", "q", "1", "k"),
                    arrayOf("w", "w", "1", "k"),
                    arrayOf("e", "e", "1", "k"),
                    arrayOf("r", "r", "1", "k"),
                    arrayOf("t", "t", "1", "k"),
                    arrayOf("y", "y", "1", "k"),
                    arrayOf("u", "u", "1", "k"),
                    arrayOf("i", "i", "1", "k"),
                    arrayOf("o", "o", "1", "k"),
                    arrayOf("p", "p", "1", "k"),
                    arrayOf("[", "lbracket", "1", "k"),
                    arrayOf("]", "rbracket", "1", "k"),
                    arrayOf("\\", "backslash", "1.5", "k"),
                )
            )
        )

        panel.addView(
            weightedRow(
                arrayOf(
                    arrayOf("Caps", "capslock", "1.8", "k"),
                    arrayOf("a", "a", "1", "k"),
                    arrayOf("s", "s", "1", "k"),
                    arrayOf("d", "d", "1", "k"),
                    arrayOf("f", "f", "1", "k"),
                    arrayOf("g", "g", "1", "k"),
                    arrayOf("h", "h", "1", "k"),
                    arrayOf("j", "j", "1", "k"),
                    arrayOf("k", "k", "1", "k"),
                    arrayOf("l", "l", "1", "k"),
                    arrayOf(";", "semicolon", "1", "k"),
                    arrayOf("'", "quote", "1", "k"),
                    arrayOf("Ent", "enter", "2", "k"),
                )
            )
        )

        panel.addView(
            weightedRow(
                arrayOf(
                    arrayOf("Shift", "lshift", "2.2", "m"),
                    arrayOf("z", "z", "1", "k"),
                    arrayOf("x", "x", "1", "k"),
                    arrayOf("c", "c", "1", "k"),
                    arrayOf("v", "v", "1", "k"),
                    arrayOf("b", "b", "1", "k"),
                    arrayOf("n", "n", "1", "k"),
                    arrayOf("m", "m", "1", "k"),
                    arrayOf(",", "comma", "1", "k"),
                    arrayOf(".", "period", "1", "k"),
                    arrayOf("/", "slash", "1", "k"),
                    arrayOf("Shift", "rshift", "2.2", "m"),
                    arrayOf("↑", "up", "1", "k"),
                )
            )
        )

        panel.addView(
            weightedRow(
                arrayOf(
                    arrayOf("Ctrl", "lctrl", "1.5", "m"),
                    arrayOf("Alt", "lalt", "1.3", "m"),
                    arrayOf("Space", "space", "6", "k"),
                    arrayOf("←", "left", "1", "k"),
                    arrayOf("↓", "down", "1", "k"),
                    arrayOf("→", "right", "1", "k"),
                )
            )
        )

        // The shifted characters of the US layout, for the keys that a phone
        // keyboard would otherwise hide.
        val symbolScroll = scrollableRow(
            arrayOf(
                arrayOf("~", "grave", "f", "s"),
                arrayOf("!", "1", "f", "s"),
                arrayOf("@", "2", "f", "s"),
                arrayOf("#", "3", "f", "s"),
                arrayOf("$", "4", "f", "s"),
                arrayOf("%", "5", "f", "s"),
                arrayOf("^", "6", "f", "s"),
                arrayOf("&", "7", "f", "s"),
                arrayOf("*", "8", "f", "s"),
                arrayOf("(", "9", "f", "s"),
                arrayOf(")", "0", "f", "s"),
                arrayOf("_", "minus", "f", "s"),
                arrayOf("+", "equals", "f", "s"),
                arrayOf("{", "lbracket", "f", "s"),
                arrayOf("}", "rbracket", "f", "s"),
                arrayOf("|", "backslash", "f", "s"),
                arrayOf(":", "semicolon", "f", "s"),
                arrayOf("\"", "quote", "f", "s"),
                arrayOf("<", "comma", "f", "s"),
                arrayOf(">", "period", "f", "s"),
                arrayOf("?", "slash", "f", "s"),
            )
        )
        this.symbolScroll = symbolScroll
        symbolScroll.visibility = GONE
        panel.addView(symbolScroll)

        panel.visibility = GONE
        // About as wide as the screen is short, so the keys do not become
        // absurdly wide in landscape.
        val metrics = resources.displayMetrics
        val panelWidth = minOf(metrics.widthPixels, metrics.heightPixels) * 92 / 100
        val lp = LayoutParams(panelWidth, LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        addView(panel, lp)
    }


    /* ------------------------------------------------- columns and the bar */

    /** Keys of the left column, above the arrow cluster. */
    private val leftColumnKeys = arrayOf(
        arrayOf("File", "", "file"),
        arrayOf("⌨", "", "toggle"),
        arrayOf("Esc", "esc", "k"),
        arrayOf("Tab", "tab", "k"),
    )

    /** Keys of the right column. */
    private val rightColumnKeys = arrayOf(
        arrayOf("«", "", "symbols"),
        arrayOf("Bks", "bspace", "k"),
        arrayOf("Alt", "lalt", "m"),
        arrayOf("Ctrl", "lctrl", "m"),
        arrayOf("Shift", "lshift", "m"),
        arrayOf("Space", "space", "k"),
        arrayOf("Ent", "enter", "k"),
    )

    /** All keys, for the bottom bar. */
    private val bottomBarKeys = arrayOf(
        arrayOf("⌨", "", "toggle"),
        arrayOf("File", "", "file"),
        arrayOf("Esc", "esc", "k"),
        arrayOf("Tab", "tab", "k"),
        arrayOf("Ctrl", "lctrl", "m"),
        arrayOf("Alt", "lalt", "m"),
        arrayOf("Shift", "lshift", "m"),
        arrayOf("Ent", "enter", "k"),
        arrayOf("Bks", "bspace", "k"),
        arrayOf("Space", "space", "k"),
        arrayOf("←", "left", "k"),
        arrayOf("↓", "down", "k"),
        arrayOf("→", "right", "k"),
        arrayOf("↑", "up", "k"),
        arrayOf("«", "", "symbols"),
    )

    /**
     * One column of the layout without pads, centred vertically for thumb
     * reach: the arrows on the left, the action keys on the right.
     */
    private fun buildColumn(left: Boolean): LinearLayout {
        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        column.gravity = Gravity.CENTER_VERTICAL
        column.setPadding(dp(3), dp(3), dp(3), dp(3))
        column.setBackgroundColor(COLOR_TOOLBAR)
        for (k in if (left) leftColumnKeys else rightColumnKeys) {
            column.addView(makeControlKey(k[0], k[1], "c", k[2], DP_KEY_HEIGHT))
        }
        if (left) {
            column.addView(buildArrowCluster())
        }
        return column
    }

    /**
     * The four arrow keys: the up arrow on its own on top, left and right side
     * by side in the middle and the down arrow on its own at the bottom,
     * instead of four keys in a line where the eye has to read each one.
     */
    private fun buildArrowCluster(): LinearLayout {
        val cluster = LinearLayout(context)
        cluster.orientation = LinearLayout.VERTICAL
        cluster.addView(makeControlKey("↑", "up", "c", "k", DP_KEY_HEIGHT))

        val middle = LinearLayout(context)
        middle.orientation = LinearLayout.HORIZONTAL
        middle.addView(makeControlKey("←", "left", "w1", "k", DP_KEY_HEIGHT))
        middle.addView(makeControlKey("→", "right", "w1", "k", DP_KEY_HEIGHT))
        cluster.addView(middle)

        cluster.addView(makeControlKey("↓", "down", "c", "k", DP_KEY_HEIGHT))
        return cluster
    }

    /** The portrait layout: all control keys weighted across a bottom strip. */
    private fun buildBottomBar(): LinearLayout {
        val bar = LinearLayout(context)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setPadding(dp(4), dp(4), dp(4), dp(4))
        bar.setBackgroundColor(COLOR_TOOLBAR)
        for (k in bottomBarKeys) {
            bar.addView(makeControlKey(k[0], k[1], "w1", k[2], DP_TOOL_KEY_HEIGHT))
        }
        return bar
    }

    /**
     * Builds a control key. [kind] may be an ordinary DOS key (`k`), a sticky
     * modifier (`m`), or one of the actions: `toggle` (fold the QWERTY panel),
     * `symbols` (the shifted-symbol row) or `file` (open the bundle launcher).
     */
    private fun makeControlKey(
        label: String,
        keyName: String,
        size: String,
        kind: String,
        heightDp: Int,
    ): TextView {
        return when (kind) {
            "toggle" -> makeButton(label, "", size, "k", heightDp).also {
                it.setOnClickListener { togglePanel() }
                toggleButton = it
            }
            "symbols" -> makeButton(label, "", size, "k", heightDp).also {
                it.setOnClickListener { toggleSymbols() }
            }
            "file" -> makeButton(label, "", size, "k", heightDp).also {
                it.setTextColor(COLOR_TEXT_ON_ACCENT)
                it.background = keyBackground(COLOR_KEY_ACCENT)
                it.setOnClickListener { launcherListener?.onFilesRequested() }
            }
            else -> makeKey(label, keyName, size, kind, heightDp)
        }
    }

    /* -------------------------------------------------------------- rows */

    /** A row whose keys share the available width. */
    private fun weightedRow(keys: Array<Array<String>>): LinearLayout {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        for (k in keys) {
            row.addView(makeKey(k[0], k[1], "w" + k[2], k[3], DP_KEY_HEIGHT))
        }
        return row
    }

    /**
     * A row of fixed-width keys inside a horizontal scroll view, for the function
     * keys and the symbols.
     */
    private fun scrollableRow(keys: Array<Array<String>>): HorizontalScrollView {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        for (k in keys) {
            row.addView(makeKey(k[0], k[1], k[2], k[3], DP_KEY_HEIGHT))
        }
        val scroll = HorizontalScrollView(context)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.addView(row)
        return scroll
    }

    /* -------------------------------------------------------------- keys */

    /**
     * Builds one key that reacts to touch.
     *
     * The DOS keys use a raw touch listener (not a click listener) so that a
     * press and a release map to a key-down and a key-up and modifiers can be
     * held — which a click cannot express. The trade-off is that these keys are
     * not exposed to TalkBack; the action buttons (File, the layer keys, the
     * panel toggle) use click listeners and stay accessible. For a DOS game pad
     * driven by thumbs this is the right balance, hence the lint suppression.
     *
     * @param size `"w<weight>"` weighted, `"c"` full column width, `"f"` fixed.
     * @param kind `"k"` ordinary, `"m"` modifier, `"s"` key that needs Shift.
     * @param color the key's resting colour.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeKey(
        label: String,
        keyName: String,
        size: String,
        kind: String,
        heightDp: Int,
        color: Int = COLOR_KEY,
    ): TextView {
        val kindCode = kindOf(kind)
        val view = makeButton(label, keyName, size, kind, heightDp)
        view.background = keyBackground(color)

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (kindCode != KIND_MODIFIER) {
                        view.background = keyBackground(COLOR_KEY_PRESSED)
                    }
                    keyDown(keyName, kindCode)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (kindCode != KIND_MODIFIER) {
                        view.background = keyBackground(color)
                    }
                    keyUp(keyName, kindCode)
                }
            }
            true
        }
        return view
    }

    private fun makeButton(
        label: String,
        keyName: String,
        size: String,
        kind: String,
        heightDp: Int,
    ): TextView {
        val view = TextView(context)
        view.text = label
        view.setTextColor(COLOR_TEXT)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        view.gravity = Gravity.CENTER
        view.includeFontPadding = false
        // A key label must never wrap — the keys are narrow.
        view.isSingleLine = true
        view.setPadding(dp(DP_KEY_PADDING), 0, dp(DP_KEY_PADDING), 0)
        view.background = keyBackground(COLOR_KEY)
        view.tag = keyName

        var weight = 0f
        val width = when {
            size.startsWith("w") -> {
                weight = size.substring(1).toFloat()
                0
            }
            size == "c" -> LinearLayout.LayoutParams.MATCH_PARENT
            else -> dp(DP_FIXED_KEY_WIDTH)
        }
        val lp = LinearLayout.LayoutParams(width, dp(heightDp))
        if (weight > 0) {
            lp.weight = weight
        }
        lp.rightMargin = dp(2)
        lp.leftMargin = dp(2)
        lp.topMargin = dp(2)
        lp.bottomMargin = dp(2)
        view.layoutParams = lp

        if ("m" == kind) {
            modifierViews.getOrPut(keyName) { ArrayList() }.add(view)
            armed[keyName] = false
        }
        return view
    }

    private fun keyBackground(color: Int): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.RECTANGLE
        drawable.setColor(color)
        drawable.cornerRadius = dp(6).toFloat()
        drawable.setStroke(maxOf(1, dp(1)), 0xFF000000.toInt())
        return drawable
    }

    /* ------------------------------------------------------------ events */

    /** A key went down. */
    private fun keyDown(keyName: String, kind: Int) {
        if (kind == KIND_MODIFIER) {
            val fingers = (heldCount[keyName] ?: 0) + 1
            heldCount[keyName] = fingers
            if (fingers > 1) {
                // The same modifier is shown twice (column and panel) and a
                // finger is already on the other one — keep the count, do nothing.
                return
            }
            heldSince[keyName] = SystemClock.uptimeMillis()
            if (isArmed(keyName)) {
                // A second tap takes an armed modifier back.
                setArmed(keyName, false)
                push(keyName, false)
                disarmedByTouch.add(keyName)
            } else {
                setArmed(keyName, true)
                push(keyName, true)
            }
            refreshModifier(keyName)
            return
        }
        if (kind == KIND_SHIFTED) {
            // Shift is wrapped around the key so that the character comes out
            // shifted — but not re-pushed if it is already down (armed or held).
            if (!isArmed(SHIFT)) {
                push(SHIFT, true)
            }
            push(keyName, true)
            return
        }
        push(keyName, true)
    }

    /** A key went up. */
    private fun keyUp(keyName: String, kind: Int) {
        if (kind == KIND_MODIFIER) {
            val fingers = heldCount[keyName] ?: 0
            if (fingers == 0) {
                return
            }
            if (fingers > 1) {
                // Another finger is still on this modifier; keep it pressed.
                heldCount[keyName] = fingers - 1
                return
            }
            heldCount.remove(keyName)
            val heldFor = SystemClock.uptimeMillis() - (heldSince.remove(keyName) ?: 0L)
            val used = usedWhileHeld.remove(keyName)
            if (disarmedByTouch.remove(keyName)) {
                return
            }
            // A short tap leaves the modifier armed for the next key. Held like
            // a real key — long, or with another key typed meanwhile — it is
            // released with the finger.
            if (isArmed(keyName) && (used || heldFor >= HOLD_MILLIS)) {
                setArmed(keyName, false)
                push(keyName, false)
                refreshModifier(keyName)
            }
            return
        }
        if (kind == KIND_SHIFTED) {
            push(keyName, false)
            // Leave Shift alone if the user is holding it down anyway.
            if (!isArmed(SHIFT)) {
                push(SHIFT, false)
            }
        } else {
            push(keyName, false)
        }
        // An armed modifier applies to exactly one key.
        releaseModifiers(force = false)
    }

    private fun isArmed(keyName: String): Boolean = armed[keyName] == true

    private fun setArmed(keyName: String, value: Boolean) {
        armed[keyName] = value
    }

    /**
     * Releases the armed modifiers after a key was typed. One that a finger is
     * still holding stays pressed (and is remembered as used) unless [force]d.
     */
    private fun releaseModifiers(force: Boolean) {
        for (key in armed.keys.toList()) {
            if (armed[key] != true) {
                continue
            }
            if (!force && (heldCount[key] ?: 0) > 0) {
                usedWhileHeld.add(key)
                continue
            }
            push(key, false)
            armed[key] = false
            refreshModifier(key)
        }
    }

    private fun refreshModifier(keyName: String) {
        val color = if (isArmed(keyName)) COLOR_KEY_ARMED else COLOR_KEY
        modifierViews[keyName]?.forEach { it.background = keyBackground(color) }
    }

    /** Pushes a key press or release into DOSBox. */
    private fun push(keyName: String, pressed: Boolean) {
        val scancode = scancodeOf(keyName)
        if (scancode == 0) {
            Log.w(TAG, "ignoring unknown key $keyName")
            return
        }
        if (!DosboxNative.nativePushKey(scancode, pressed)) {
            Log.w(TAG, "could not push " + keyName + (if (pressed) " down" else " up"))
            return
        }
        // Remember what is down, so a layout rebuild can release it (see
        // downScancodes). A release clears it even if the same scancode has two
        // keys — the set holds it once, which is exactly what DOSBox wants.
        if (pressed) {
            downScancodes.add(scancode)
        } else {
            downScancodes.remove(scancode)
        }
    }

    private fun scancodeOf(keyName: String): Int =
        scancodes.getOrPut(keyName) { DosboxNative.nativeScancodeFromName(keyName) }

    /* ------------------------------------------------------------ toggles */

    /** @return true when the full keyboard panel is unfolded. */
    fun isPanelVisible(): Boolean = panelVisible

    /** Folds the full keyboard panel away. */
    fun hidePanel() {
        if (!panelVisible) {
            return
        }
        togglePanel()
    }

    private fun togglePanel() {
        val panel = this.panel ?: return
        panelVisible = !panelVisible
        panel.visibility = if (panelVisible) VISIBLE else GONE
        toggleButton?.background = keyBackground(if (panelVisible) COLOR_KEY_ARMED else COLOR_KEY)
        toggleButton?.text = if (panelVisible) "⌨✕" else "⌨"
        if (panelVisible) {
            requestLayout()
        }
    }

    private fun toggleSymbols() {
        val symbols = symbolScroll ?: return
        symbols.visibility = if (symbols.visibility == VISIBLE) GONE else VISIBLE
    }

    /** Lays the keys out again for the new size. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Defer the hierarchy change: adding child views *during* onSizeChanged
        // (a layout pass) leaves them unmeasured and invisible. post() runs the
        // rebuild after this pass, so the new views are laid out normally.
        post { rebuildControls(width, height) }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    companion object {
        private const val TAG = "whereq-dosbox"

        /** Ordinary key: pressed and released again straight away. */
        private const val KIND_KEY = 0

        /** Modifier: a tap arms it for the next key, a hold works like a key. */
        private const val KIND_MODIFIER = 1

        /** Key that needs Shift, e.g. "!" — Shift is wrapped around it. */
        private const val KIND_SHIFTED = 2

        /** A modifier down for at least this long was held, not tapped. */
        private const val HOLD_MILLIS = 350L

        private const val MODE_PADS = 0
        private const val MODE_COLUMNS = 1
        private const val MODE_BAR = 2

        private const val LAYER_LETTERS = 0
        private const val LAYER_SYMBOLS = 1
        private const val LAYER_FUNCTIONS = 2

        /**
         * The left halves of the three layers, indexed by `LAYER_*`. The split
         * is where a touch typist's hands split a keyboard.
         *
         * The symbol mappings assume a US layout (`!` = Shift+1, `:` = Shift+`;`,
         * …), which is what DOS programs expect — DOSBox feeds scancodes to the
         * BIOS, and the US code page is the shipped default.
         */
        private val LEFT_LAYERS = arrayOf(
            arrayOf("1 2 3 4 5", "q w e r t", "a s d f g", "z x c v b"),
            arrayOf(
                "1 2 3 4 5",
                "!:1:s @:2:s #:3:s $:4:s %:5:s",
                "-:minus =:equals [:lbracket ]:rbracket `:grave",
                "_:minus:s +:equals:s {:lbracket:s }:rbracket:s ~:grave:s",
            ),
            arrayOf("F1:f1 F2:f2 F3:f3", "F4:f4 F5:f5 F6:f6", "F7:f7 F8:f8 F9:f9", "F10:f10 F11:f11 F12:f12"),
        )

        /**
         * The right halves. The letters carry `: . / \` along, so a DOS path
         * needs no layer switch; on the symbol layer every shifted character
         * of the last row sits right below its unshifted key.
         */
        private val RIGHT_LAYERS = arrayOf(
            arrayOf(
                "6 7 8 9 0",
                "y u i o p",
                "h j k l colon:semicolon:s",
                "n m .:period /:slash \\:backslash",
            ),
            arrayOf(
                "6 7 8 9 0",
                "^:6:s &:7:s *:8:s (:9:s ):0:s",
                ";:semicolon ':quote ,:comma \\:backslash |:backslash:s",
                "colon:semicolon:s \":quote:s <:comma:s >:period:s ?:slash:s",
            ),
            arrayOf(
                "Ins:insert Home:home PgUp:pageup",
                "Del:delete End:end PgDn:pagedown",
                "Caps:capslock PrtSc:printscreen Pause:pause",
                "_",
            ),
        )

        private const val COLOR_PANEL = 0xD0202024.toInt()
        private const val COLOR_TOOLBAR = 0xC0202024.toInt()
        private const val COLOR_KEY = 0xFF3C4043.toInt()

        /** Keys that are not characters (Enter, Backspace, the layer keys). */
        private const val COLOR_KEY_DARK = 0xFF2A2D30.toInt()
        private const val COLOR_KEY_PRESSED = 0xFF5F6368.toInt()
        private const val COLOR_KEY_ARMED = 0xFF1A73E8.toInt()

        /** The launcher button is an action, not a key — it gets its own colour. */
        private const val COLOR_KEY_ACCENT = 0xFF8A6D00.toInt()
        private const val COLOR_TEXT = 0xFFE8EAED.toInt()
        private const val COLOR_TEXT_ON_ACCENT = 0xFFFFF3D6.toInt()

        /** Below this a bar cannot hold five keys in a row. */
        private const val DP_MIN_PAD_WIDTH = 116

        /** A pad wider than this puts its inner keys out of a thumb's reach. */
        private const val DP_MAX_PAD_WIDTH = 210

        /** Below this a bar cannot hold even a column of keys. */
        private const val DP_MIN_COLUMN_WIDTH = 56
        private const val DP_MAX_COLUMN_WIDTH = 132

        private const val DP_KEY_HEIGHT = 34
        private const val DP_TOOL_KEY_HEIGHT = 30
        private const val DP_KEY_PADDING = 2
        private const val DP_FIXED_KEY_WIDTH = 52

        /** Key name of the shift key — used to type the shifted characters. */
        private const val SHIFT = "lshift"

        private fun kindOf(kind: String): Int = when (kind) {
            "m" -> KIND_MODIFIER
            "s" -> KIND_SHIFTED
            else -> KIND_KEY
        }
    }
}
