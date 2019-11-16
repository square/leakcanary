package leakcanary.internal.activity.ui

import android.text.SpannableStringBuilder
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.View

internal object UiUtils {

  internal fun replaceUrlSpanWithAction(
    title: SpannableStringBuilder,
    urlAction: (String) -> (() -> Unit)?
  ) {
    val urlSpans = title.getSpans(0, title.length, URLSpan::class.java)
    for (span in urlSpans) {
      val action: (() -> Unit)? = urlAction(span.url)
      if (action != null) {
        val start = title.getSpanStart(span)
        val end = title.getSpanEnd(span)
        val flags = title.getSpanFlags(span)
        title.removeSpan(span)
        val newSpan = object : ClickableSpan() {
          override fun onClick(widget: View) {
            action()
          }
        }
        title.setSpan(newSpan, start, end, flags)
      }
    }
  }

}