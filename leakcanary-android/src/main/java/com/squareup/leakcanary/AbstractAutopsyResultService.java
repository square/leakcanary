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

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;

public abstract class AbstractAutopsyResultService extends IntentService {

  private static final String HEAP_DUMP_EXTRA = "heap_dump_extra";
  private static final String RESULT_EXTRA = "result_extra";

  public static void sendResultToListener(Context context, String listenerServiceClassName,
      Bag heapDump, Autopsy result) {
    Class<?> listenerServiceClass;
    try {
      listenerServiceClass = Class.forName(listenerServiceClassName);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
    Intent intent = new Intent(context, listenerServiceClass);
    intent.putExtra(HEAP_DUMP_EXTRA, heapDump);
    intent.putExtra(RESULT_EXTRA, result);
    context.startService(intent);
  }

  public AbstractAutopsyResultService() {
    super(AbstractAutopsyResultService.class.getName());
  }

  @Override protected final void onHandleIntent(Intent intent) {
    Bag heapDump = (Bag) intent.getSerializableExtra(HEAP_DUMP_EXTRA);
    Autopsy result = (Autopsy) intent.getSerializableExtra(RESULT_EXTRA);
    try {
      onAutopsyPerformed(heapDump, result);
    } finally {
      //noinspection ResultOfMethodCallIgnored
      heapDump.heapDumpFile.delete();
    }
  }

  protected abstract void onAutopsyPerformed(Bag heapDump, Autopsy result);
}
