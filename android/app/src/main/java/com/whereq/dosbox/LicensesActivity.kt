package com.whereq.dosbox

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The licence screen.
 *
 * WhereQ-DOSBox embeds the DOSBox core, which is GPLv2. GPLv2 section 2 asks for
 * the licence to travel with the program: the copy of the licence and the
 * copyright notices have to be there, not only in the source tree. This
 * screen is that copy - the full GNU General Public License version 2 and the
 * attribution notices - and it links to the complete source of the build.
 *
 * The text is bundled as assets (`COPYING` and `NOTICE`), so the screen works
 * without a network connection, exactly like the rest of the app.
 */
class LicensesActivity : Activity() {

    companion object {
        private const val TAG = "whereq-dosbox"

        /** The background of the app: the colour of the launcher icon. */
        private const val BACKGROUND = 0xFF111319.toInt()

        private const val COLOR_TEXT = 0xFFE8EAED.toInt()
        private const val COLOR_DIM = 0xFF9AA0AA.toInt()
        private const val COLOR_ACCENT = 0xFFF5A623.toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(BACKGROUND))

        val scroll = ScrollView(this)
        scroll.addView(
            buildContent(),
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        setContentView(scroll)
    }

    /**
     * Builds the page: what WhereQ-DOSBox is, where its source is, and then the
     * licence text and the notices.
     */
    private fun buildContent(): LinearLayout {
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(20), dp(24), dp(20), dp(40))

        column.addView(
            heading("WhereQ-DOSBox is free software"),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        column.addView(
            body(
                "WhereQ-DOSBox is the Android port of DOSBox. The emulator core is " +
                    "the DOSBox r4305 core, imported with its copyright and licence " +
                    "notices intact, and the porting work on top of it is " +
                    "distributed under the same licence."
            )
        )

        column.addView(
            heading("GNU General Public License, version 2"),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(28) }
        )
        column.addView(
            body(
                "This program is distributed under the terms of the GNU General " +
                    "Public License as published by the Free Software Foundation; " +
                    "either version 2 of the License, or (at your option) any later " +
                    "version. This program is distributed in the hope that it will be " +
                    "useful, but WITHOUT ANY WARRANTY; without even the implied " +
                    "warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. " +
                    "See the GNU General Public License for more details."
            )
        )

        // Where the source of this build is.
        column.addView(
            heading("The complete source code"),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(28) }
        )
        column.addView(
            body(
                "The GPL requires that everyone who receives this program also " +
                    "receives its source. The complete corresponding source of this " +
                    "build - the emulator core, the Android application and the " +
                    "build scripts - is published here:"
            )
        )
        column.addView(
            link(getString(R.string.source_code_url)),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // The licence text itself.
        column.addView(
            heading("Licence text"),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(28) }
        )
        column.addView(
            verbatim(readAsset("COPYING")),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // And the notices.
        column.addView(
            heading("Notices"),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(28) }
        )
        column.addView(
            verbatim(readAsset("NOTICE")),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        return column
    }

    /** A section heading. */
    private fun heading(text: String): TextView {
        val view = TextView(this)
        view.text = text
        view.setTextColor(COLOR_ACCENT)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        view.setTypeface(null, Typeface.BOLD)
        return view
    }

    /** A paragraph of running text. */
    private fun body(text: String): TextView {
        val view = TextView(this)
        view.text = text
        view.setTextColor(COLOR_TEXT)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        view.setLineSpacing(dp(4).toFloat(), 1f)
        return view
    }

    /** A URL the reader can tap. */
    private fun link(url: String): TextView {
        val view = TextView(this)
        view.text = url
        view.setTextColor(COLOR_ACCENT)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        view.setPadding(0, dp(6), 0, dp(6))
        view.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                Log.w(TAG, "could not open $url", e)
            }
        }
        return view
    }

    /**
     * A block of licence text.
     *
     * The licence is stored as an 80-column text file, which is unreadable on
     * a phone: the lines would wrap at the screen edge and the result is
     * ragged. The paragraphs are therefore reflowed here - the words are
     * unchanged, only the line breaks are. The verbatim file travels with the
     * app as the asset `COPYING` and with the source.
     */
    private fun verbatim(text: String): TextView {
        val view = TextView(this)
        view.text = reflow(text)
        view.setTextColor(COLOR_DIM)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        view.setLineSpacing(dp(4).toFloat(), 1f)
        return view
    }

    /**
     * Joins the lines of every paragraph into one line, so the text follows
     * the width of the screen instead of the width of the original file.
     */
    private fun reflow(text: String): String =
        text.split("\n\n")
            .map { paragraph ->
                paragraph.trim()
                    .lines()
                    .joinToString(" ") { it.trim() }
                    .trim()
            }
            .joinToString("\n\n")

    /**
     * Reads one of the bundled licence files.
     *
     * @param name file name under `assets/`.
     * @return the text, or a note that it could not be read.
     */
    private fun readAsset(name: String): String = try {
        assets.open(name).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        Log.w(TAG, "could not read the asset $name", e)
        "($name could not be read)"
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
