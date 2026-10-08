package com.privacyguard.android

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Small view helpers shared by the two native activities. */
class Ui(private val context: Context) {
    val ink = Color.rgb(25, 43, 35)
    val muted = Color.rgb(84, 100, 89)
    val accent = Color.rgb(50, 96, 60)
    val background = Color.rgb(246, 247, 242)
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun column(padding: Int = 0) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
    fun text(value: String, size: Float = 15f, bold: Boolean = false, color: Int = ink) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(6))
    }
    fun field(hint: String, multiline: Boolean = false) = EditText(context).apply {
        this.hint = hint; textSize = 15f; setTextColor(ink); setHintTextColor(muted)
        setSingleLine(!multiline); minHeight = dp(52)
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            if (multiline) android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE else android.text.InputType.TYPE_TEXT_VARIATION_URI
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = rounded(Color.WHITE, Color.rgb(194, 203, 193))
        // Links and rule inputs must not be copied into autofill or saved activity state.
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        isSaveEnabled = false
        imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        filters = arrayOf(android.text.InputFilter.LengthFilter(8192))
    }
    fun button(value: String, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = value; isAllCaps = false; textSize = 14f; minHeight = dp(48)
        setTextColor(if (primary) Color.WHITE else ink)
        backgroundTintList = android.content.res.ColorStateList.valueOf(if (primary) ink else Color.rgb(229, 235, 223))
        setOnClickListener { action() }
    }
    fun card(parent: LinearLayout, title: String, body: String? = null): LinearLayout = column(16).apply {
        background = rounded(Color.WHITE)
        addView(text(title, 19f, true))
        body?.let { addView(text(it, 14f, color = muted)) }
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
    }
    fun rounded(color: Int, border: Int = color) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(16).toFloat(); setStroke(dp(1), border)
    }
}
