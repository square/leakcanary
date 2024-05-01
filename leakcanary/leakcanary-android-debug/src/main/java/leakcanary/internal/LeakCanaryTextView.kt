package leakcanary.internal

import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.text.Spanned
import android.util.AttributeSet
import android.widget.TextView

/**
 * Modified TextView to fully support SquigglySpan.
 */
internal class LeakCanaryTextView(
  context: Context,
  attrs: AttributeSet,
) : TextView(context, attrs) {
  private val singleLineRenderer: SquigglySpanRenderer by lazy { SingleLineRenderer(context) }
  private val multiLineRenderer: SquigglySpanRenderer by lazy { MultiLineRenderer(context) }

  override fun onDraw(canvas: Canvas) {
    if (text is Spanned && layout != null) {
      val checkpoint = canvas.save()
      canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
      try {
        drawSquigglyLine(canvas, text as Spanned, layout)
      } finally {
        canvas.restoreToCount(checkpoint)
      }
    }
    super.onDraw(canvas)
  }

  private fun drawSquigglyLine(canvas: Canvas, text: Spanned, layout: Layout) {
    // ideally the calculations here should be cached since they are not cheap. However, proper
    // invalidation of the cache is required whenever anything related to text has changed.
    val squigglySpans = text.getSpans(0, text.length, SquigglySpan::class.java)
    for (span in squigglySpans) {
      val spanStart = text.getSpanStart(span)
      val spanEnd = text.getSpanEnd(span)
      val startLine = layout.getLineForOffset(spanStart)
      val endLine = layout.getLineForOffset(spanEnd)

      // start can be on the left or on the right depending on the language direction.
      val startOffset = (layout.getPrimaryHorizontal(spanStart)
        + -1 * layout.getParagraphDirection(startLine)).toInt()

      // end can be on the left or on the right depending on the language direction.
      val endOffset = (layout.getPrimaryHorizontal(spanEnd)
        + layout.getParagraphDirection(endLine)).toInt()

      val renderer = if (startLine == endLine) singleLineRenderer else multiLineRenderer
      renderer.draw(canvas, layout, startLine, endLine, startOffset, endOffset)
    }
  }
}
