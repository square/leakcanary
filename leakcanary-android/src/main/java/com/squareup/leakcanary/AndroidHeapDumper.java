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
import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.widget.Toast;
import com.squareup.leakcanary.internal.FutureResult;
import com.squareup.leakcanary.internal.LeakCanaryInternals;
import java.io.File;
import java.io.IOException;

import static com.squareup.leakcanary.internal.LeakCanaryInternals.isExternalStorageWritable;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.storageDirectory;
import static java.util.concurrent.TimeUnit.SECONDS;

public final class AndroidHeapDumper implements HeapDumper {

  private static final String TAG = "AndroidHeapDumper";

  private final Context context;
  private final Handler mainHandler;

  public AndroidHeapDumper(Context context) {
    this.context = context.getApplicationContext();
    mainHandler = new Handler(Looper.getMainLooper());
  }

  @Override public File dumpHeap() {
    if (!isExternalStorageWritable()) {
      Log.d(TAG, "Could not dump heap, external storage not mounted.");
    }
    File heapDumpFile = getHeapDumpFile();
    if (heapDumpFile.exists()) {
      Log.d(TAG, "Could not dump heap, previous analysis still is in progress.");
      // Heap analysis in progress, let's not put too much pressure on the device.
      return NO_DUMP;
    }

    FutureResult<Toast> waitingForToast = new FutureResult<>();
    showToast(waitingForToast);

    if (!waitingForToast.wait(5, SECONDS)) {
      Log.d(TAG, "Did not dump heap, too much time waiting for Toast.");
      return NO_DUMP;
    }

    Toast toast = waitingForToast.get();
    try {
      Debug.dumpHprofData(heapDumpFile.getAbsolutePath());
      cancelToast(toast);
      return heapDumpFile;
    } catch (IOException e) {
      cleanup();
      Log.e(TAG, "Could not perform heap dump", e);
      // Abort heap dump
      return NO_DUMP;
    }
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

  private File getHeapDumpFile() {
    return new File(storageDirectory(), "suspected_leak_heapdump.hprof");
  }

  private void showToast(final FutureResult<Toast> waitingForToast) {
    mainHandler.post(new Runnable() {
      @Override public void run() {
        final Toast toast = new Toast(context);
        toast.setGravity(Gravity.CENTER_VERTICAL, 0, 0);
        toast.setDuration(Toast.LENGTH_LONG);
        LayoutInflater inflater = LayoutInflater.from(context);
        toast.setView(inflater.inflate(R.layout.__leak_canary_heap_dump_toast, null));
        toast.show();
        // Waiting for Idle to make sure Toast gets rendered.
        Looper.myQueue().addIdleHandler(new MessageQueue.IdleHandler() {
          @Override public boolean queueIdle() {
            waitingForToast.set(toast);
            return false;
          }
        });
      }
    });
  }

  private void cancelToast(final Toast toast) {
    mainHandler.post(new Runnable() {
      @Override public void run() {
        toast.cancel();
      }
    });
  }
}
