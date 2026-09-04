package io.github.judegibatron.phoneagent.session

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** The floating session card: status line, what the user said, what the assistant said, tool activity. */
class SessionOverlay(context: Context, private val onClose: () -> Unit) {

    enum class Tone(val color: Int) {
        IDLE(0xFF90A4AE.toInt()),
        LISTENING(0xFF4CAF50.toInt()),
        THINKING(0xFF64B5F6.toInt()),
        SPEAKING(0xFFFFB74D.toInt()),
        ERROR(0xFFEF5350.toInt()),
    }

    val view: View
    private val statusDot: View
    private val statusText: TextView
    private val userText: TextView
    private val assistantText: TextView
    private val activityText: TextView

    init {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0xF0141A22.toInt())
                setStroke(dp(1), 0x33FFFFFF)
            }
            elevation = dp(8).toFloat()
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Tone.IDLE.color)
            }
        }
        header.addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(10) })
        statusText = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            text = "Phone Agent"
        }
        header.addView(statusText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val close = TextView(context).apply {
            text = "✕"
            setTextColor(0xFFB0BEC5.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(12), dp(4), dp(4), dp(4))
            contentDescription = "Close"
            setOnClickListener { onClose() }
        }
        header.addView(close)
        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        userText = TextView(context).apply {
            setTextColor(0xFFB0BEC5.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(Typeface.DEFAULT, Typeface.ITALIC)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        root.addView(userText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        assistantText = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 8
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        root.addView(assistantText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        activityText = TextView(context).apply {
            setTextColor(0xFF80CBC4.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        root.addView(activityText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        view = root
    }

    fun setStatus(text: String, tone: Tone) {
        statusText.text = text
        (statusDot.background as? GradientDrawable)?.setColor(tone.color)
    }

    fun setUserText(text: String) = show(userText, text)

    fun setAssistantText(text: String) = show(assistantText, text)

    fun setActivity(text: String) = show(activityText, text)

    private fun show(target: TextView, text: String) {
        target.text = text
        target.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }
}
