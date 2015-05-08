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
import android.os.Debug;
import android.util.Log;
import java.io.File;
import java.io.IOException;

public final class AndroidHeapDumper implements HeapDumper {

  private final File heapDumpFile;

  public AndroidHeapDumper(Context context) {
    heapDumpFile = new File(context.getFilesDir(), "suspected_leak_heapdump.hprof");
  }

  @Override public File dumpHeap() {
    if (heapDumpFile.exists()) {
      Log.d("AndroidHeapDumper", "Could not dump heap, previous analysis still is in progress.");
      // Heap analysis in progress, let's not put to much pressure on the device.
      return null;
    }
    try {
      Debug.dumpHprofData(heapDumpFile.getAbsolutePath());
      return heapDumpFile;
    } catch (IOException e) {
      cleanup();
      Log.e("AndroidHeapDumper", "Could not perform heap dump", e);
      // Abort heap dump
      return null;
    }
  }

  /**
   * Call this on app startup to clean up all heap dump files that had not been handled yet when
   * the app process was killed.
   */
  public void cleanup() {
    if (heapDumpFile.exists()) {
      Log.d("AndroidHeapDumper",
          "Previous analysis did not complete correctly, cleaning: " + heapDumpFile);
      heapDumpFile.delete();
    }
  }
}
