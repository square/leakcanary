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

import android.os.Debug;
import android.util.Log;
import com.squareup.leakcanary.internal.LeakCanaryInternals;
import java.io.File;
import java.io.IOException;

import static com.squareup.leakcanary.internal.LeakCanaryInternals.isExternalStorageWritable;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.storageDirectory;

public final class AndroidHeapDumper implements HeapDumper {

  private static final String TAG = "AndroidHeapDumper";

  @Override public File dumpHeap() {
    if (!isExternalStorageWritable()) {
      Log.d(TAG, "Could not dump heap, external storage not mounted.");
    }
    File heapDumpFile = getHeapDumpFile();
    if (heapDumpFile.exists()) {
      Log.d(TAG, "Could not dump heap, previous analysis still is in progress.");
      // Heap analysis in progress, let's not put too much pressure on the device.
      return null;
    }
    try {
      Debug.dumpHprofData(heapDumpFile.getAbsolutePath());
      return heapDumpFile;
    } catch (IOException e) {
      cleanup();
      Log.e(TAG, "Could not perform heap dump", e);
      // Abort heap dump
      return null;
    }
  }

  private File getHeapDumpFile() {
    return new File(storageDirectory(), "suspected_leak_heapdump.hprof");
  }

  /**
   * Call this on app startup to clean up all heap dump files that had not been handled yet when
   * the app process was killed.
   */
  public void cleanup() {
    LeakCanaryInternals.executeOnFileIoThread(new Runnable() {
      @Override public void run() {
        if (isExternalStorageWritable()) {
          Log.d(TAG, "Could not attempt cleanup, external storage not mounted.");
        }
        File heapDumpFile = getHeapDumpFile();
        if (heapDumpFile.exists()) {
          Log.d(TAG, "Previous analysis did not complete correctly, cleaning: " + heapDumpFile);
          heapDumpFile.delete();
        }
      }
    });
  }
}
