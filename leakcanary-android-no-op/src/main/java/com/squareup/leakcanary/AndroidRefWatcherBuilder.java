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
package com.squareup.leakcanary;

import android.content.Context;
import java.util.concurrent.TimeUnit;

public final class AndroidRefWatcherBuilder {

  AndroidRefWatcherBuilder(Context ignored) {
  }

  public AndroidRefWatcherBuilder listenerServiceClass(Class<? extends AbstractAnalysisResultService> ignored) {
    return this;
  }

  public AndroidRefWatcherBuilder watchDelay(long ignored1, TimeUnit ignored2) {
    return this;
  }

  public AndroidRefWatcherBuilder maxStoredHeapDumps(int ignored) {
    return this;
  }

  public RefWatcher buildAndInstall() {
    return RefWatcher.DISABLED;
  }
}