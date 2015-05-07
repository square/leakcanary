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

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuffXfermode;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.View;

import static android.graphics.Bitmap.Config.ARGB_8888;
import static android.graphics.PorterDuff.Mode.CLEAR;
import static com.squareup.leakcanary.internal.DisplayLeakConnectorView.Type.NODE;
import static com.squareup.leakcanary.internal.DisplayLeakConnectorView.Type.START;

public final class DisplayLeakConnectorView extends View {

  static final int LIGHT_GREY = 0xFFbababa;
  static final int ROOT_COLOR = 0xFF84a6c5;
  static final int LEAK_COLOR = 0xFFb1554e;

  public enum Type {
    START, NODE, END
  }

  private final Paint iconPaint;
  private final Paint clearPaint;
  private final Paint rootPaint;
  private final Paint leakPaint;
  private final float strokeSize;

  private Type type;
  private Bitmap cache;

  public DisplayLeakConnectorView(Context context, AttributeSet attrs) {
    super(context, attrs);

    iconPaint = new Paint();
    iconPaint.setColor(LIGHT_GREY);
    strokeSize = dpToPixel(4, getResources());
    iconPaint.setStrokeWidth(strokeSize);
    iconPaint.setAntiAlias(true);

    clearPaint = new Paint();
    clearPaint.setColor(0);
    clearPaint.setXfermode(new PorterDuffXfermode(CLEAR));
    clearPaint.setAntiAlias(true);

    rootPaint = new Paint();
    rootPaint.setColor(ROOT_COLOR);
    rootPaint.setAntiAlias(true);
    rootPaint.setStrokeWidth(strokeSize);

    leakPaint = new Paint();
    leakPaint.setColor(LEAK_COLOR);
    leakPaint.setAntiAlias(true);
    type = NODE;
  }

  @SuppressWarnings("SuspiciousNameCombination") @Override protected void onDraw(Canvas canvas) {
    int width = getWidth();
    int height = getHeight();

    if (cache != null && (cache.getWidth() != width || cache.getHeight() != height)) {
      cache.recycle();
      cache = null;
    }

    if (cache == null) {
      cache = Bitmap.createBitmap(width, height, ARGB_8888);

      Canvas cacheCanvas = new Canvas(cache);

      float halfWidth = width / 2f;
      float halfHeight = height / 2f;
      float thirdWidth = width / 3;

      if (type == NODE) {
        cacheCanvas.drawLine(halfWidth, 0, halfWidth, height, iconPaint);
        cacheCanvas.drawCircle(halfWidth, halfHeight, halfWidth, iconPaint);
        cacheCanvas.drawCircle(halfWidth, halfHeight, thirdWidth, clearPaint);
      } else if (type == START) {
        float radiusClear = halfWidth - strokeSize / 2;
        cacheCanvas.drawRect(0, 0, width, radiusClear, rootPaint);
        cacheCanvas.drawCircle(0, radiusClear, radiusClear, clearPaint);
        cacheCanvas.drawCircle(width, radiusClear, radiusClear, clearPaint);
        cacheCanvas.drawLine(halfWidth, 0, halfWidth, halfHeight, rootPaint);
        cacheCanvas.drawLine(halfWidth, halfHeight, halfWidth, height, iconPaint);
        cacheCanvas.drawCircle(halfWidth, halfHeight, halfWidth, iconPaint);
        cacheCanvas.drawCircle(halfWidth, halfHeight, thirdWidth, clearPaint);
      } else {
        cacheCanvas.drawLine(halfWidth, 0, halfWidth, halfHeight, iconPaint);
        cacheCanvas.drawCircle(halfWidth, halfHeight, thirdWidth, leakPaint);
      }
    }
    canvas.drawBitmap(cache, 0, 0, null);
  }

  public void setType(Type type) {
    if (type != this.type) {
      this.type = type;
      if (cache != null) {
        cache.recycle();
        cache = null;
      }
      invalidate();
    }
  }

  static float dpToPixel(float dp, Resources resources) {
    DisplayMetrics metrics = resources.getDisplayMetrics();
    float px = dp * (metrics.densityDpi / 160f);
    return px;
  }
}
