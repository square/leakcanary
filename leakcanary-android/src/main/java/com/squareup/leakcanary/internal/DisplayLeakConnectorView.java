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
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PorterDuffXfermode;
import android.util.AttributeSet;
import android.view.View;
import com.squareup.leakcanary.R;

import static android.graphics.Bitmap.Config.ARGB_8888;
import static android.graphics.PorterDuff.Mode.CLEAR;

public final class DisplayLeakConnectorView extends View {

  private static final float SQRT_TWO = (float) Math.sqrt(2);
  private static final PorterDuffXfermode CLEAR_XFER_MODE = new PorterDuffXfermode(CLEAR);

  public enum Type {
    HELP,
    START,
    START_LAST_REACHABLE,
    NODE_UNKNOWN,
    NODE_FIRST_UNREACHABLE,
    NODE_UNREACHABLE,
    NODE_REACHABLE,
    NODE_LAST_REACHABLE,
    END,
    END_FIRST_UNREACHABLE,
  }

  private final Paint classNamePaint;
  private final Paint leakPaint;
  private final Paint clearPaint;
  private final Paint referencePaint;
  private final float strokeSize;
  private final float circleY;

  private Type type;
  private Bitmap cache;

  public DisplayLeakConnectorView(Context context, AttributeSet attrs) {
    super(context, attrs);

    Resources resources = getResources();

    type = Type.NODE_UNKNOWN;
    circleY = resources.getDimensionPixelSize(R.dimen.leak_canary_connector_center_y);
    strokeSize = resources.getDimensionPixelSize(R.dimen.leak_canary_connector_stroke_size);

    classNamePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    classNamePaint.setColor(resources.getColor(R.color.leak_canary_class_name));
    classNamePaint.setStrokeWidth(strokeSize);

    leakPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    leakPaint.setColor(resources.getColor(R.color.leak_canary_leak));
    leakPaint.setStyle(Paint.Style.STROKE);
    leakPaint.setStrokeWidth(strokeSize);
    float pathLines = resources.getDimensionPixelSize(R.dimen.leak_canary_connector_leak_dash_line);
    float pathGaps = resources.getDimensionPixelSize(R.dimen.leak_canary_connector_leak_dash_gap);
    leakPaint.setPathEffect(new DashPathEffect(new float[] { pathLines, pathGaps }, 0));

    clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    clearPaint.setColor(Color.TRANSPARENT);
    clearPaint.setXfermode(CLEAR_XFER_MODE);

    referencePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    referencePaint.setColor(resources.getColor(R.color.leak_canary_reference));
    referencePaint.setStrokeWidth(strokeSize);
  }

  @SuppressWarnings("SuspiciousNameCombination") @Override protected void onDraw(Canvas canvas) {
    int width = getMeasuredWidth();
    int height = getMeasuredHeight();

    if (cache != null && (cache.getWidth() != width || cache.getHeight() != height)) {
      cache.recycle();
      cache = null;
    }

    if (cache == null) {
      cache = Bitmap.createBitmap(width, height, ARGB_8888);

      Canvas cacheCanvas = new Canvas(cache);

      switch (type) {
        case NODE_UNKNOWN:
          drawItems(cacheCanvas, leakPaint, leakPaint);
          break;
        case NODE_UNREACHABLE:
        case NODE_REACHABLE:
          drawItems(cacheCanvas, referencePaint, referencePaint);
          break;
        case NODE_FIRST_UNREACHABLE:
          drawItems(cacheCanvas, leakPaint, referencePaint);
          break;
        case NODE_LAST_REACHABLE:
          drawItems(cacheCanvas, referencePaint, leakPaint);
          break;
        case START: {
          drawStartLine(cacheCanvas);
          drawItems(cacheCanvas, null, referencePaint);
          break;
        }
        case START_LAST_REACHABLE:
          drawStartLine(cacheCanvas);
          drawItems(cacheCanvas, null, leakPaint);
          break;
        case END:
          drawItems(cacheCanvas, referencePaint, null);
          break;
        case END_FIRST_UNREACHABLE:
          drawItems(cacheCanvas, leakPaint, null);
          break;
        case HELP:
          drawRoot(cacheCanvas);
          break;
        default:
          throw new UnsupportedOperationException("Unknown type " + type);
      }
    }
    canvas.drawBitmap(cache, 0, 0, null);
  }

  private void drawStartLine(Canvas cacheCanvas) {
    int width = getMeasuredWidth();
    float halfWidth = width / 2f;
    cacheCanvas.drawLine(halfWidth, 0, halfWidth, circleY, classNamePaint);
  }

  private void drawRoot(Canvas cacheCanvas) {
    int width = getMeasuredWidth();
    int height = getMeasuredHeight();
    float halfWidth = width / 2f;
    float radiusClear = halfWidth - strokeSize / 2f;
    cacheCanvas.drawRect(0, 0, width, radiusClear, classNamePaint);
    cacheCanvas.drawCircle(0, radiusClear, radiusClear, clearPaint);
    cacheCanvas.drawCircle(width, radiusClear, radiusClear, clearPaint);
    cacheCanvas.drawLine(halfWidth, 0, halfWidth, height, classNamePaint);
  }

  private void drawItems(Canvas cacheCanvas, Paint arrowHeadPaint, Paint nextArrowPaint) {
    if (arrowHeadPaint != null) {
      drawArrowHead(cacheCanvas, arrowHeadPaint);
    }
    if (nextArrowPaint != null) {
      drawNextArrowLine(cacheCanvas, nextArrowPaint);
    }
    drawInstanceCircle(cacheCanvas);
  }

  private void drawArrowHead(Canvas cacheCanvas, Paint paint) {
    // Circle center is at half height
    int width = getMeasuredWidth();
    float halfWidth = width / 2f;
    float centerX = halfWidth;
    float circleRadius = width / 3f;
    float arrowSideLength = halfWidth;
    // Splitting the arrow head in two makes an isosceles right triangle.
    // It's hypotenuse is side * sqrt(2)
    float arrowHeight = (arrowSideLength / 2) * SQRT_TWO;
    float halfStrokeSize = strokeSize / 2;
    float translateY = circleY - arrowHeight - (circleRadius * 2) - strokeSize;

    float lineYEnd = circleY - circleRadius - (strokeSize / 2);
    cacheCanvas.drawLine(centerX, 0, centerX, lineYEnd, paint);
    cacheCanvas.translate(centerX, translateY);
    cacheCanvas.rotate(45);
    cacheCanvas.drawLine(0, arrowSideLength, arrowSideLength + halfStrokeSize, arrowSideLength,
        paint);
    cacheCanvas.drawLine(arrowSideLength, 0, arrowSideLength, arrowSideLength, paint);
    cacheCanvas.rotate(-45);
    cacheCanvas.translate(-centerX, -translateY);
  }

  private void drawNextArrowLine(Canvas cacheCanvas, Paint paint) {
    int height = getMeasuredHeight();
    int width = getMeasuredWidth();
    float centerX = width / 2f;
    cacheCanvas.drawLine(centerX, circleY, centerX, height, paint);
  }

  private void drawInstanceCircle(Canvas cacheCanvas) {
    int width = getMeasuredWidth();
    float circleX = width / 2f;
    float circleRadius = width / 3f;
    cacheCanvas.drawCircle(circleX, circleY, circleRadius, classNamePaint);
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
}
