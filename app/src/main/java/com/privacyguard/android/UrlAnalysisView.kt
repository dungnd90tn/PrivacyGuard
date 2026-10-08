package com.privacyguard.android

import android.widget.LinearLayout
import com.privacyguard.android.core.UrlAnalysis
import com.privacyguard.android.core.UrlAnalyzer
import com.privacyguard.android.core.UrlKind

/** Shared native presentation. Values/path stay hidden until an explicit action in this view. */
fun showUrlAnalysis(ui: Ui, parent: LinearLayout, analysis: UrlAnalysis) {
    val color = when (analysis.kind) {
        UrlKind.AD_REQUEST, UrlKind.AD_RELATED, UrlKind.POSSIBLE_ADS -> ui.orange
        UrlKind.TRACKING -> ui.accent
        UrlKind.INSUFFICIENT -> ui.muted
    }
    parent.addView(ui.text(analysis.kind.title, 20f, true, color))
    parent.addView(ui.text(UrlAnalyzer.safe(analysis.host), 15f, true)); ui.gap(parent, 10)
    parent.addView(ui.text(analysis.kind.explanation, 14f))
    parent.addView(ui.text("Chỉ nhận định · không tự chặn theo tham số", 12f, color = ui.muted)); ui.gap(parent, 12)
    parent.addView(ui.text("Vì sao?", 16f, true))
    analysis.reasons.forEach { parent.addView(ui.text(it, 14f, color = ui.muted)) }
    ui.gap(parent, 12)
    val values = ui.column()
    var revealed = false
    fun render() {
        values.removeAllViews()
        values.addView(ui.text("Đường dẫn: ${if (revealed) UrlAnalyzer.safe(analysis.path.ifEmpty { "/" }) else "đã ẩn"}", 13f, color = ui.muted))
        if (analysis.parameters.isEmpty()) values.addView(ui.text("URL không có query param.", 14f, color = ui.muted))
        analysis.parameters.forEachIndexed { index, parameter ->
            ui.gap(values, 8)
            values.addView(ui.text("${index + 1}. ${UrlAnalyzer.safe(parameter.name.ifEmpty { "(không có tên)" })}", 14f, true))
            values.addView(ui.text(parameter.explanation, 13f, color = ui.muted))
            values.addView(ui.text(if (revealed) "Giá trị: ${parameter.value?.let { UrlAnalyzer.safe(it).ifEmpty { "(trống)" } } ?: "(không có dấu =)"}"
                else "Giá trị: đã ẩn", 13f, color = ui.muted).apply { setTextIsSelectable(revealed) })
        }
    }
    parent.addView(ui.text("Giá trị có thể chứa mã định danh hoặc thông tin riêng tư. Chỉ giữ trong bộ nhớ.", 12f, color = ui.muted))
    val reveal = ui.button("Hiện đường dẫn và giá trị") {}
    reveal.setOnClickListener {
        revealed = !revealed
        reveal.text = if (revealed) "Ẩn đường dẫn và giá trị" else "Hiện đường dẫn và giá trị"
        render()
    }
    parent.addView(reveal); parent.addView(values); render()
    if (analysis.omittedParameters) parent.addView(ui.text("Chỉ phân tích 50 tham số đầu. Nhận định có thể thiếu dấu hiệu ở phần còn lại.", 12f, color = ui.orange))
    if (analysis.hasFragment) parent.addView(ui.text("Phần sau dấu # không được gửi trong URL của yêu cầu mạng và không dùng để nhận định.", 12f, color = ui.muted))
}
