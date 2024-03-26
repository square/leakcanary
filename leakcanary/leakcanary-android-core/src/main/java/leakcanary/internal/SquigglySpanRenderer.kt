package leakcanary.internal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.text.Layout
import com.squareup.leakcanary.core.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import leakcanary.internal.navigation.getColorCompat

/**
 * The idea with a multiline span from https://github.com/googlearchive/android-text/tree/master/RoundedBackground-Kotlin
 */
internal abstract class SquigglySpanRenderer(context: Context) {
  private val squigglyPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val path: Path
  private val halfStrokeWidth: Float
  private val amplitude: Float
  private val halfWaveHeight: Float
  private val periodDegrees: Float

  init {
    val resources = context.resources
    squigglyPaint.style = Paint.Style.STROKE
    squigglyPaint.color = context.getColorCompat(R.color.leak_canary_leak)
    val strokeWidth =
      resources.getDimensionPixelSize(R.dimen.leak_canary_squiggly_span_stroke_width)
        .toFloat()
    squigglyPaint.strokeWidth = strokeWidth

    halfStrokeWidth = strokeWidth / 2
    amplitude = resources.getDimensionPixelSize(R.dimen.leak_canary_squiggly_span_amplitude)
      .toFloat()
    periodDegrees =
      resources.getDimensionPixelSize(R.dimen.leak_canary_squiggly_span_period_degrees)
        .toFloat()
    path = Path()
    val waveHeight = 2 * amplitude + strokeWidth
    halfWaveHeight = waveHeight / 2
  }

  abstract fun draw(
    canvas: Canvas,
    layout: Layout,
    startLine: Int,
    endLine: Int,
    startOffset: Int,
    endOffset: Int
  )

  protected fun Canvas.drawSquigglyHorizontalPath(left: Float, right: Float, bottom: Float) {
    squigglyHorizontalPath(
      path = path,
      left = left + halfStrokeWidth,
      right = right - halfStrokeWidth,
      centerY = bottom - halfWaveHeight,
      amplitude = amplitude,
      periodDegrees = periodDegrees
    )
    drawPath(path, squigglyPaint)
  }

  protected fun getLineBottom(layout: Layout, line: Int): Int {
    var lineBottom = layout.getLineBottomWithoutSpacing(line)
    if (line == layout.lineCount - 1) {
      lineBottom -= layout.bottomPadding
    }
    return lineBottom
  }

  companion object {
    /**
     * Android system default line spacing extra
     */
    private const val DEFAULT_LINESPACING_EXTRA = 0f

    /**
     * Android system default line spacing multiplier
     */
    private const val DEFAULT_LINESPACING_MULTIPLIER = 1f

    private fun squigglyHorizontalPath(
      path: Path,
      left: Float,
      right: Float,
      centerY: Float,
      amplitude: Float,
      periodDegrees: Float
    ) {
      path.reset()

      var y: Float
      path.moveTo(left, centerY)
      val period = (2 * Math.PI / periodDegrees).toFloat()

      var x = 0f
      while (x <= right - left) {
        y = (amplitude * sin((40 + period * x).toDouble()) + centerY).toFloat()
        path.lineTo(left + x, y)
        x += 1f
      }
    }

    private fun Layout.getLineBottomWithoutSpacing(line: Int): Int {
      val lineBottom = getLineBottom(line)
      val lastLineSpacingNotAdded = Build.VERSION.SDK_INT >= 19
      val isLastLine = line == lineCount - 1

      val lineBottomWithoutSpacing: Int
      val lineSpacingExtra = spacingAdd
      val lineSpacingMultiplier = spacingMultiplier
      val hasLineSpacing = lineSpacingExtra != DEFAULT_LINESPACING_EXTRA
        || lineSpacingMultiplier != DEFAULT_LINESPACING_MULTIPLIER

      lineBottomWithoutSpacing = if (!hasLineSpacing || isLastLine && lastLineSpacingNotAdded) {
        lineBottom
      } else {
        val extra = if (lineSpacingMultiplier.compareTo(DEFAULT_LINESPACING_MULTIPLIER) != 0) {
          val lineHeight = getLineTop(line + 1) - getLineTop(line)
          lineHeight - (lineHeight - lineSpacingExtra) / lineSpacingMultiplier
        } else {
          lineSpacingExtra
        }

        (lineBottom - extra).toInt()
      }

      return lineBottomWithoutSpacing
    }
  }
}

/**
 * Draws the background for text that starts and ends on the same line.
 */
internal class SingleLineRenderer(context: Context) : SquigglySpanRenderer(context) {
  override fun draw(
    canvas: Canvas,
    layout: Layout,
    startLine: Int,
    endLine: Int,
    startOffset: Int,
    endOffset: Int
  ) {
    canvas.drawSquigglyHorizontalPath(
      left = min(startOffset, endOffset).toFloat(),
      right = max(startOffset, endOffset).toFloat(),
      bottom = getLineBottom(layout, startLine).toFloat(),
    )
  }
}

/**
 * Draws the background for text that starts and ends on different lines.
 */
internal class MultiLineRenderer(context: Context) : SquigglySpanRenderer(context) {
  override fun draw(
    canvas: Canvas,
    layout: Layout,
    startLine: Int,
    endLine: Int,
    startOffset: Int,
    endOffset: Int
  ) {
    val isRtl = layout.getParagraphDirection(startLine) == Layout.DIR_RIGHT_TO_LEFT
    val lineEndOffset = if (isRtl) {
      layout.getLineLeft(startLine)
    } else {
      layout.getLineRight(startLine)
    }

    canvas.drawSquigglyHorizontalPath(
      left = startOffset.toFloat(),
      right = lineEndOffset,
      bottom = getLineBottom(layout, startLine).toFloat(),
    )

    for (line in startLine + 1 until endLine) {
      canvas.drawSquigglyHorizontalPath(
        left = layout.getLineLeft(line),
        right = layout.getLineRight(line),
        bottom = getLineBottom(layout, line).toFloat(),
      )
    }

    val lineStartOffset = if (isRtl) {
      layout.getLineRight(startLine)
    } else {
      layout.getLineLeft(startLine)
    }

    canvas.drawSquigglyHorizontalPath(
      left = lineStartOffset,
      right = endOffset.toFloat(),
      bottom = getLineBottom(layout, endLine).toFloat(),
    )
  }
}
