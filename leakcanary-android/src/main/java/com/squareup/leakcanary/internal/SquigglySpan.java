/*
 * Copyright (C) 2018 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.leakcanary.internal;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.SpannableStringBuilder;
import android.text.style.ReplacementSpan;
import android.text.style.UnderlineSpan;
import com.squareup.leakcanary.R;

/**
 * Inspired from https://github.com/flavienlaurent/spans and
 * https://github.com/andyxialm/WavyLineView
 */
class SquigglySpan extends ReplacementSpan {


  public static void replaceUnderlineSpans(SpannableStringBuilder builder, Resources resources) {
    UnderlineSpan[] underlineSpans = builder.getSpans(0, builder.length(), UnderlineSpan.class);
    for (UnderlineSpan span : underlineSpans) {
      int start = builder.getSpanStart(span);
      int end = builder.getSpanEnd(span);
      builder.removeSpan(span);
      builder.setSpan(new SquigglySpan(resources), start, end, 0);
    }
  }

  private final Paint squigglyPaint;
  private final Path path;
  private final int referenceColor;
  private final float halfStrokeWidth;
  private final float amplitude;
  private final float halfWaveHeight;
  private final float periodDegrees;

  private int width;

  SquigglySpan(Resources resources) {
    squigglyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    squigglyPaint.setStyle(Paint.Style.STROKE);
    squigglyPaint.setColor(resources.getColor(R.color.leak_canary_leak));
    float strokeWidth =
        resources.getDimensionPixelSize(R.dimen.leak_canary_squiggly_span_stroke_width);
    squigglyPaint.setStrokeWidth(strokeWidth);

    halfStrokeWidth = strokeWidth / 2;
    amplitude = resources.getDimensionPixelSize(R.dimen.leak_canary_squiggly_span_amplitude);
    periodDegrees =
        resources.getDimensionPixelSize(R.dimen.leak_canary_squiggly_span_period_degrees);
    path = new Path();
    float waveHeight = 2 * amplitude + strokeWidth;
    halfWaveHeight = waveHeight / 2;
    referenceColor = resources.getColor(R.color.leak_canary_reference);
  }

  @Override public int getSize(@NonNull Paint paint, CharSequence text, int start, int end,
      @Nullable Paint.FontMetricsInt fm) {
    width = (int) paint.measureText(text, start, end);
    return width;
  }

  @Override
  public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x, int top,
      int y, int bottom, @NonNull Paint paint) {
    squigglyHorizontalPath(path,
        x + halfStrokeWidth,
        x + width - halfStrokeWidth,
        bottom - halfWaveHeight,
        amplitude, periodDegrees);
    canvas.drawPath(path, squigglyPaint);

    paint.setColor(referenceColor);
    canvas.drawText(text, start, end, x, y, paint);
  }

  private static void squigglyHorizontalPath(Path path, float left, float right, float centerY,
      float amplitude,
      float periodDegrees) {
    path.reset();

    float y;
    path.moveTo(left, centerY);
    float period = (float) (2 * Math.PI / periodDegrees);

    for (float x = 0; x <= right - left; x += 1) {
      y = (float) (amplitude * Math.sin(40 + period * x) + centerY);
      path.lineTo(left + x, y);
    }
  }
}