package com.whereq.dosbox

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Stardom-specific controls. Only ESC, ENTER and the horizontal arrows are
 * visible; the empty centre and the game picture remain touch-through.
 */
class StardomControlsView(context: Context) : FrameLayout(context) {

    private val scans = mapOf(
        "esc" to DosboxNative.nativeScancodeFromName("esc"),
        "enter" to DosboxNative.nativeScancodeFromName("enter"),
        "left" to DosboxNative.nativeScancodeFromName("left"),
        "right" to DosboxNative.nativeScancodeFromName("right"),
    )
    private val pressed = LinkedHashSet<Int>()

    init {
        isClickable = false
        isFocusable = false
        isMotionEventSplittingEnabled = true

        val leftGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        leftGroup.addView(makeKey("←", "left", 58, 25f))
        leftGroup.addView(makeKey("→", "right", 58, 25f))

        val rightGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        rightGroup.addView(makeKey("ESC", "esc", 62, 16f))
        rightGroup.addView(makeKey("ENTER", "enter", 76, 14f))

        // The two groups sit on the bottom edge like the iPad reference. They
        // have no backing strip, so the centre of the screen stays unobstructed.
        addView(leftGroup, LayoutParams(LayoutParams.WRAP_CONTENT, dp(58), Gravity.BOTTOM or Gravity.START).apply {
            marginStart = dp(12)
            bottomMargin = dp(7)
        })
        addView(rightGroup, LayoutParams(LayoutParams.WRAP_CONTENT, dp(58), Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = dp(12)
            bottomMargin = dp(7)
        })
    }

    private fun gapParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(dp(58), dp(58)).apply { leftMargin = dp(8) }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeKey(label: String, keyName: String, widthDp: Int, textSp: Float): TextView {
        val view = TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            isSingleLine = true
            includeFontPadding = false
            setTextColor(0xFF3C2B16.toInt())
            setTextSize(textSp)
            typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD)
            setPadding(dp(3), 0, dp(3), 0)
            background = keyBackground(false)
            elevation = dp(3).toFloat()
            contentDescription = when (keyName) {
                "esc" -> "Escape"
                "enter" -> "Enter"
                "left" -> "Left"
                else -> "Right"
            }
            layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(58)).apply {
                leftMargin = dp(2)
                rightMargin = dp(2)
                topMargin = dp(2)
                bottomMargin = dp(2)
            }
        }

        val scan = scans[keyName] ?: 0
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    view.background = keyBackground(true)
                    if (scan != 0 && DosboxNative.nativePushKey(scan, true)) pressed.add(scan)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    release(scan)
                    view.background = keyBackground(false)
                    true
                }
                else -> true
            }
        }
        return view
    }

    private fun release(scan: Int) {
        if (scan != 0 && pressed.remove(scan)) DosboxNative.nativePushKey(scan, false)
    }

    /** Release any key whose ACTION_UP was interrupted by a focus change. */
    fun releaseAll() {
        pressed.toList().forEach { DosboxNative.nativePushKey(it, false) }
        pressed.clear()
    }

    override fun onDetachedFromWindow() {
        releaseAll()
        super.onDetachedFromWindow()
    }

    private fun keyBackground(isPressed: Boolean): LayerDrawable {
        val outer = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(4).toFloat()
            setColor(if (isPressed) 0xFF74552C.toInt() else 0xFF80643B.toInt())
            setStroke(dp(1), 0xFF35230D.toInt())
        }
        val inner = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            if (isPressed) intArrayOf(0xFFBE9A59.toInt(), 0xFFE0C58D.toInt())
            else intArrayOf(0xFFF4E2B8.toInt(), 0xFFC9A66A.toInt())
        ).apply {
            cornerRadius = dp(2).toFloat()
            setStroke(dp(1), if (isPressed) 0xFF8A6A39.toInt() else 0xFFFFEBC4.toInt())
        }
        return LayerDrawable(arrayOf(outer, inner)).apply {
            setLayerInset(1, dp(3), dp(3), dp(3), dp(3))
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
