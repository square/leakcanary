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

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.squareup.leakcanary.AbstractAutopsyResultService;
import com.squareup.leakcanary.Autopsy;
import com.squareup.leakcanary.Bag;
import com.squareup.leakcanary.OOMAutopsy;

/**
 * This service runs in a separate process to avoid slowing down the app process or making it run
 * out of memory.
 */
public final class AutopsyService extends IntentService {

  private static final String LISTENER_CLASS_EXTRA = "listener_class_extra";
  private static final String HEAPDUMP_EXTRA = "heapdump_extra";
  public static final String TAG = "HeapAnalyzerService";

  public static void runAnalysis(Context context, Bag bag,
      Class<? extends AbstractAutopsyResultService> listenerServiceClass) {
    Intent intent = new Intent(context, AutopsyService.class);
    intent.putExtra(LISTENER_CLASS_EXTRA, listenerServiceClass.getName());
    intent.putExtra(HEAPDUMP_EXTRA, bag);
    context.startService(intent);
  }

  public AutopsyService() {
    super(AutopsyService.class.getSimpleName());
  }

  @Override protected void onHandleIntent(Intent intent) {
    if (intent == null) {
      Log.d(TAG, "HeapAnalyzerService received a null intent, ignoring.");
      return;
    }
    String listenerClassName = intent.getStringExtra(LISTENER_CLASS_EXTRA);
    Bag heapDump = (Bag) intent.getSerializableExtra(HEAPDUMP_EXTRA);

    OOMAutopsy oomAutopsy = new OOMAutopsy(heapDump.excludedRefs, heapDump.zombieMatchers);

    Autopsy autopsy = oomAutopsy.performAutopsy(heapDump.heapDumpFile);

    AbstractAutopsyResultService.sendResultToListener(this, listenerClassName, heapDump, autopsy);
  }
}
