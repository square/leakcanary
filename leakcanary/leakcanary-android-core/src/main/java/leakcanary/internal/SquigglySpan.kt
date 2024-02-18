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
package leakcanary.internal

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UnderlineSpan
import com.squareup.leakcanary.core.R
import leakcanary.internal.navigation.getColorCompat

/**
 * Inspired from https://github.com/flavienlaurent/spans and
 * https://github.com/andyxialm/WavyLineView
 */
internal class SquigglySpan(context: Context) : CharacterStyle() {
  private val referenceColor: Int = context.getColorCompat(R.color.leak_canary_reference)

  override fun updateDrawState(textPaint: TextPaint) {
    textPaint.color = referenceColor
  }

  companion object {
    fun replaceUnderlineSpans(
      builder: SpannableStringBuilder,
      context: Context
    ) {
      val underlineSpans = builder.getSpans(0, builder.length, UnderlineSpan::class.java)
      for (span in underlineSpans) {
        val start = builder.getSpanStart(span)
        val end = builder.getSpanEnd(span)
        builder.removeSpan(span)
        builder.setSpan(SquigglySpan(context), start, end, 0)
      }
    }
  }
}
