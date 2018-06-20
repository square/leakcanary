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
package com.squareup.leakcanary.internal;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import com.squareup.leakcanary.R;

public final class MoreDetailsView extends View {

  private final Paint iconPaint;

  public MoreDetailsView(Context context, AttributeSet attrs) {
    super(context, attrs);
    Resources resources = getResources();
    iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    float strokeSize = resources.getDimensionPixelSize(R.dimen.leak_canary_more_stroke_width);
    iconPaint.setStrokeWidth(strokeSize);

    // This lint check doesn't work for libraries which have a common prefix.
    @SuppressLint("CustomViewStyleable") //
        TypedArray a =
        context.obtainStyledAttributes(attrs, R.styleable.leak_canary_MoreDetailsView);
    int plusColor =
        a.getColor(R.styleable.leak_canary_MoreDetailsView_leak_canary_plus_color, Color.BLACK);
    a.recycle();

    iconPaint.setColor(plusColor);
  }

  private boolean opened;

  @Override protected void onDraw(Canvas canvas) {
    int width = getWidth();
    int height = getHeight();
    int halfHeight = height / 2;
    int halfWidth = width / 2;

    if (opened) {
      canvas.drawLine(0, halfHeight, width, halfHeight, iconPaint);
    } else {
      canvas.drawLine(0, halfHeight, width, halfHeight, iconPaint);
      canvas.drawLine(halfWidth, 0, halfWidth, height, iconPaint);
    }
  }

  public void setOpened(boolean opened) {
    if (opened != this.opened) {
      this.opened = opened;
      invalidate();
    }
  }
}
