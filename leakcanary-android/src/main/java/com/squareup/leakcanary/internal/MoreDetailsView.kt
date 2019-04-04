/*
 * Copyright (C) 2015 Square, Inc.
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
package com.squareup.leakcanary.internal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.squareup.leakcanary.R

class MoreDetailsView(
  context: Context,
  attrs: AttributeSet
) : View(context, attrs) {

  private val iconPaint: Paint

  private var opened: Boolean = false

  init {
    val resources = resources
    iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val strokeSize = resources.getDimensionPixelSize(R.dimen.leak_canary_more_stroke_width)
        .toFloat()
    iconPaint.strokeWidth = strokeSize

    // This lint check doesn't work for libraries which have a common prefix.
    @SuppressLint("CustomViewStyleable") //
    val a = context.obtainStyledAttributes(attrs, R.styleable.leak_canary_MoreDetailsView)
    val plusColor =
      a.getColor(R.styleable.leak_canary_MoreDetailsView_leak_canary_plus_color, Color.BLACK)
    a.recycle()

    iconPaint.color = plusColor
  }

  override fun onDraw(canvas: Canvas) {
    val width = width
    val height = height
    val halfHeight = height / 2
    val halfWidth = width / 2

    if (opened) {
      canvas.drawLine(0f, halfHeight.toFloat(), width.toFloat(), halfHeight.toFloat(), iconPaint)
    } else {
      canvas.drawLine(0f, halfHeight.toFloat(), width.toFloat(), halfHeight.toFloat(), iconPaint)
      canvas.drawLine(halfWidth.toFloat(), 0f, halfWidth.toFloat(), height.toFloat(), iconPaint)
    }
  }

  fun setOpened(opened: Boolean) {
    if (opened != this.opened) {
      this.opened = opened
      invalidate()
    }
  }
}
