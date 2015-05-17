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

import android.content.res.Resources;
import android.graphics.PorterDuffXfermode;
import android.util.DisplayMetrics;

import static android.graphics.PorterDuff.Mode.CLEAR;

final class LeakCanaryUi {
  static final int LIGHT_GREY = 0xFFbababa;
  static final int ROOT_COLOR = 0xFF84a6c5;
  static final int LEAK_COLOR = 0xFFb1554e;

  static final PorterDuffXfermode CLEAR_XFER_MODE = new PorterDuffXfermode(CLEAR);

  /**
   * Converts from device independent pixels (dp or dip) to
   * device dependent pixels. This method returns the input
   * multiplied by the display's density. The result is not
   * rounded nor clamped.
   *
   * The value returned by this method is well suited for
   * drawing with the Canvas API but should not be used to
   * set layout dimensions.
   *
   * @param dp The value in dp to convert to pixels
   * @param resources An instances of Resources
   */
  static float dpToPixel(float dp, Resources resources) {
    DisplayMetrics metrics = resources.getDisplayMetrics();
    return metrics.density * dp;
  }

  private LeakCanaryUi() {
    throw new AssertionError();
  }
}
