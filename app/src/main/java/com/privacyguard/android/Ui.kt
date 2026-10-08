package com.privacyguard.android

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.privacyguard.android.core.Outcome
import java.time.LocalDate

/** HIG-inspired hierarchy and grouped lists, implemented with native Android controls and fonts. */
class Ui(private val context: Context) {
    val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val ink = Color.parseColor(if (dark) "#F5F6FA" else "#182235")
    val muted = Color.parseColor(if (dark) "#A8B0C0" else "#647086")
    val accent = Color.parseColor(if (dark) "#85BAFF" else "#0062CE")
    val background = Color.parseColor(if (dark) "#10131B" else "#F2F4F8")
    val surface = Color.parseColor(if (dark) "#1D2230" else "#FFFFFF")
    val soft = Color.parseColor(if (dark) "#263951" else "#EAF2FF")
    val line = Color.parseColor(if (dark) "#343B4C" else "#E9EDF3")
    val red = Color.parseColor(if (dark) "#FF9B9D" else "#BD3045")
    val green = Color.parseColor(if (dark) "#82D7B5" else "#137654")
    val orange = Color.parseColor(if (dark) "#F4C679" else "#94620D")
    fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    fun column(padding: Int = 0) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun text(value: String, size: Float = 15f, bold: Boolean = false, color: Int = ink) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding = false
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f); setPadding(0, dp(3), 0, dp(3))
    }
    fun gap(parent: LinearLayout, height: Int = 12) { parent.addView(View(context), LinearLayout.LayoutParams(1, dp(height))) }
    fun heading(parent: LinearLayout, label: String, action: String? = null, tap: (() -> Unit)? = null) {
        val row = row().apply { setPadding(dp(4), dp(18), dp(4), dp(9)) }
        row.addView(text(label, 19f, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (action != null && tap != null) row.addView(text(action, 13f, true, accent).apply {
            minHeight = dp(48); gravity = Gravity.CENTER; setPadding(dp(8), 0, 0, 0)
            background = ripple(Color.TRANSPARENT, 10); setOnClickListener { tap() }
        })
        parent.addView(row)
    }
    fun field(hint: String, multiline: Boolean = false) = EditText(context).apply {
        this.hint = hint; textSize = 16f; setTextColor(ink); setHintTextColor(muted)
        setSingleLine(!multiline); minHeight = dp(52)
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            if (multiline) android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE else android.text.InputType.TYPE_TEXT_VARIATION_URI
        setPadding(dp(15), dp(14), dp(15), dp(14)); background = rounded(backgroundColor(), radius = 14)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        isSaveEnabled = false
        imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        filters = arrayOf(android.text.InputFilter.LengthFilter(8192))
    }
    private fun backgroundColor() = if (dark) Color.parseColor("#141A26") else Color.parseColor("#F0F3F8")
    fun button(value: String, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = value; isAllCaps = false; textSize = 15f; minHeight = dp(50); minimumHeight = dp(50)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val fill = if (primary) accent else soft
        val foreground = if (primary) { if (dark) Color.parseColor("#0B2444") else Color.WHITE } else accent
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(muted, foreground)))
        background = ripple(fill, 14); setPadding(dp(14), dp(10), dp(14), dp(10))
        setOnClickListener { action() }
    }
    fun card(parent: LinearLayout, title: String, body: String? = null): LinearLayout = group(parent, 18).apply {
        if (title.isNotEmpty()) addView(text(title, 19f, true))
        body?.let { addView(text(it, 13f, color = muted)); gap(this, 12) }
    }
    fun group(parent: LinearLayout, padding: Int = 0): LinearLayout = column(padding).apply {
        background = rounded(surface, radius = 22)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }
    fun separator(parent: LinearLayout, inset: Int = 16) {
        parent.addView(View(context).apply { setBackgroundColor(line); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(inset); marginEnd = dp(16) })
    }
    fun listRow(parent: LinearLayout, title: String, subtitle: String? = null, icon: String? = null,
                tint: Int = accent, trailing: String? = null, oneLineTitle: Boolean = false, tap: (() -> Unit)? = null): LinearLayout = row().apply {
        setPadding(dp(16), dp(14), dp(16), dp(14)); minimumHeight = dp(70)
        if (icon != null) addView(glyph(icon, tint).apply { background = rounded(tinted(tint), radius = 12); setPadding(dp(8), dp(8), dp(8), dp(8)) },
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        val labels = column()
        labels.addView(text(title, 15f, true).apply { maxLines = if (oneLineTitle) 1 else 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        if (!subtitle.isNullOrBlank()) labels.addView(text(subtitle, 12f, color = muted).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END })
        addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        trailing?.let { addView(text(it, 14f, true, tint).apply { setPadding(dp(10), 0, 0, 0) }) }
        if (tap != null) {
            addView(glyph("chevron", muted), LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(6) })
            background = ripple(Color.TRANSPARENT, 18); setOnClickListener { tap() }
            isFocusable = true; isScreenReaderFocusable = true
        }
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    fun tinted(color: Int): Int = Color.argb(if (dark) 34 else 18, Color.red(color), Color.green(color), Color.blue(color))
    fun glyph(name: String, color: Int = accent): View = GlyphView(context, name, color).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    fun segmented(parent: LinearLayout, labels: List<String>, selected: Int, changed: (Int) -> Unit) {
        val bar = row().apply { setPadding(dp(4), dp(4), dp(4), dp(4)); background = rounded(line, radius = 14) }
        labels.forEachIndexed { index, label ->
            bar.addView(text(label, 13f, index == selected, if (index == selected) ink else muted).apply {
                minHeight = dp(44); gravity = Gravity.CENTER; setPadding(dp(4), dp(8), dp(4), dp(8))
                background = ripple(if (index == selected) surface else Color.TRANSPARENT, 11)
                isSelected = index == selected; isFocusable = true; setOnClickListener { if (index != selected) changed(index) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        parent.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
    }
    fun rounded(color: Int, border: Int = color, radius: Int = 18) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (border != color) setStroke(dp(1), border)
    }
    fun ripple(color: Int, radius: Int) = RippleDrawable(ColorStateList.valueOf(tinted(accent)), rounded(color, radius = radius), rounded(Color.WHITE, radius = radius))
    @Suppress("DEPRECATION")
    fun edgeToEdge(activity: Activity, root: View) {
        if (Build.VERSION.SDK_INT < 35) { activity.window.statusBarColor = background; activity.window.navigationBarColor = surface }
        if (Build.VERSION.SDK_INT >= 30) activity.window.insetsController?.setSystemBarsAppearance(
            if (dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        else @Suppress("DEPRECATION") run { activity.window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            } else @Suppress("DEPRECATION") run { view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom) }
            insets
        }
        root.requestApplyInsets()
    }
}

/** Original small line icons. No Apple fonts or SF Symbols are bundled. */
private class GlyphView @JvmOverloads constructor(context: Context, private val name: String = "shield", private val color: Int = Color.BLUE) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pathBuffer = Path()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val available = minOf(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom).toFloat()
        canvas.save(); canvas.translate(paddingLeft + (width - paddingLeft - paddingRight - available) / 2f, paddingTop + (height - paddingTop - paddingBottom - available) / 2f)
        canvas.scale(available / 24f, available / 24f)
        paint.color = color; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.7f; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        fun line(x: Float, y: Float, a: Float, b: Float) = canvas.drawLine(x, y, a, b, paint)
        fun box(x: Float, y: Float, a: Float, b: Float, r: Float = 2f) = canvas.drawRoundRect(x, y, a, b, r, r, paint)
        fun path(vararg points: Float) { val p = pathBuffer; p.reset(); p.moveTo(points[0], points[1]); for (i in 2 until points.size step 2) p.lineTo(points[i], points[i + 1]); canvas.drawPath(p, paint) }
        when (name) {
            "overview" -> { box(3f, 3f, 10f, 10f); box(14f, 3f, 21f, 10f); box(3f, 14f, 10f, 21f); box(14f, 14f, 21f, 21f) }
            "apps" -> { box(4f, 2f, 20f, 22f, 4f); line(9f, 5f, 15f, 5f); line(10f, 19f, 14f, 19f) }
            "rules" -> { line(4f, 6f, 20f, 6f); line(4f, 12f, 20f, 12f); line(4f, 18f, 20f, 18f); canvas.drawCircle(8f, 6f, 2f, paint); canvas.drawCircle(16f, 12f, 2f, paint); canvas.drawCircle(10f, 18f, 2f, paint) }
            "link" -> { canvas.save(); canvas.rotate(-38f, 12f, 12f); box(8f, 2f, 16f, 13f, 4f); box(8f, 11f, 16f, 22f, 4f); line(12f, 8f, 12f, 16f); canvas.restore() }
            "settings" -> { canvas.drawCircle(12f, 12f, 7f, paint); canvas.drawCircle(12f, 12f, 2.6f, paint); for (i in 0..7) { canvas.save(); canvas.rotate(i * 45f, 12f, 12f); line(12f, 2f, 12f, 5f); canvas.restore() } }
            "shield" -> { val p = pathBuffer; p.reset(); p.moveTo(12f, 2f); p.lineTo(21f, 6f); p.lineTo(21f, 12f); p.cubicTo(21f, 17f, 16f, 21f, 12f, 23f); p.cubicTo(8f, 21f, 3f, 17f, 3f, 12f); p.lineTo(3f, 6f); p.close(); canvas.drawPath(p, paint); path(8f, 12f, 11f, 15f, 16f, 9f) }
            "globe", "browser" -> { canvas.drawCircle(12f, 12f, 9f, paint); canvas.drawOval(8f, 3f, 16f, 21f, paint); line(3f, 12f, 21f, 12f); if (name == "browser") line(6f, 6f, 18f, 6f) }
            "back" -> { path(14f, 5f, 7f, 12f, 14f, 19f); line(7f, 12f, 21f, 12f) }
            "chevron" -> path(9f, 6f, 15f, 12f, 9f, 18f)
            "refresh" -> { canvas.drawArc(4f, 4f, 20f, 20f, 35f, 295f, false, paint); path(21f, 5f, 20f, 11f, 14f, 10f) }
            "export" -> { box(4f, 11f, 20f, 22f); line(12f, 16f, 12f, 2f); path(7f, 7f, 12f, 2f, 17f, 7f) }
            "clock" -> { canvas.drawCircle(12f, 12f, 9f, paint); path(12f, 6f, 12f, 12f, 16f, 14f) }
            "block" -> { canvas.drawCircle(12f, 12f, 9f, paint); line(6f, 6f, 18f, 18f) }
            "check" -> path(4f, 12f, 10f, 18f, 20f, 6f)
            "info" -> { canvas.drawCircle(12f, 12f, 9f, paint); line(12f, 11f, 12f, 17f); canvas.drawCircle(12f, 7f, .5f, paint) }
            "search" -> { canvas.drawCircle(10f, 10f, 6f, paint); line(15f, 15f, 21f, 21f) }
            "delete" -> { box(6f, 6f, 18f, 22f); line(3f, 6f, 21f, 6f); box(9f, 2f, 15f, 6f); line(10f, 10f, 10f, 18f); line(14f, 10f, 14f, 18f) }
            else -> { canvas.drawCircle(12f, 12f, 9f, paint); line(12f, 7f, 12f, 13f); canvas.drawCircle(12f, 17f, .5f, paint) }
        }
        canvas.restore()
    }
}

class TrafficChart @JvmOverloads constructor(context: Context, private val ui: Ui = Ui(context)) : View(context) {
    var counts: List<DailyCount> = emptyList(); set(value) { field = value; updateCells(); invalidate() }
    var days: Int = 7; set(value) { field = value; updateCells(); invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cells = emptyList<Pair<LocalDate, LongArray>>()
    private var maximum = 1L
    private fun updateCells() {
        cells = (days - 1 downTo 0).map { offset ->
            val day = LocalDate.now().minusDays(offset.toLong())
            day to LongArray(Outcome.entries.size) { index -> counts.filter { it.day == day && it.outcome == Outcome.entries[index] }.sumOf { it.count } }
        }
        maximum = cells.maxOfOrNull { it.second.sum() }?.coerceAtLeast(1) ?: 1
        updateDescription()
    }
    init { updateCells() }
    private fun updateDescription() {
        contentDescription = "Biểu đồ $days ngày: " + Outcome.entries.joinToString { outcome -> "${outcome.label} ${counts.filter { it.outcome == outcome }.sumOf { it.count }}" }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val top = ui.dp(20).toFloat(); val baseline = height - ui.dp(26).toFloat(); val graphHeight = baseline - top
        val cell = width.toFloat() / days; val barWidth = minOf(ui.dp(32).toFloat(), cell * .4f)
        paint.strokeWidth = ui.dp(1).toFloat(); paint.color = ui.line
        for (i in 0..2) { val y = top + graphHeight * i / 2; canvas.drawLine(0f, y, width.toFloat(), y, paint) }
        cells.forEachIndexed { index, (day, values) ->
            val x = cell * (index + .5f); var y = baseline
            Outcome.entries.forEach { outcome ->
                val count = values[outcome.ordinal]
                val barHeight = count.toFloat() / maximum * graphHeight
                paint.color = when (outcome) { Outcome.BLOCKED -> ui.accent; Outcome.FORWARDED -> ui.green; Outcome.FAILED -> ui.orange }
                canvas.drawRoundRect(x - barWidth / 2, y - barHeight, x + barWidth / 2, y, ui.dp(4).toFloat(), ui.dp(4).toFloat(), paint)
                y -= barHeight
            }
            paint.color = ui.muted; paint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics); paint.textAlign = Paint.Align.CENTER
            canvas.drawText(if (days == 1) "Hôm nay" else if (day == LocalDate.now()) "Nay" else "${day.dayOfMonth}/${day.monthValue}", x, height - ui.dp(4).toFloat(), paint)
        }
    }
}
