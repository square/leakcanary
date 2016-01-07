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
import android.view.Gravity;
import android.view.LayoutInflater;
import android.widget.Toast;
import com.squareup.leakcanary.internal.FutureResult;
import com.squareup.leakcanary.internal.LeakCanaryInternals;
import java.io.File;
import java.io.IOException;

import static java.util.concurrent.TimeUnit.SECONDS;

public final class AndroidHeapDumper implements HeapDumper {

  private static final String HEAPDUMP_FILE = "suspected_leak_heapdump.hprof";

  final Context context;
  final LeakDirectoryProvider leakDirectoryProvider;
  private final Handler mainHandler;

  public AndroidHeapDumper(Context context, LeakDirectoryProvider leakDirectoryProvider) {
    this.leakDirectoryProvider = leakDirectoryProvider;
    this.context = context.getApplicationContext();
    mainHandler = new Handler(Looper.getMainLooper());
  }

  @Override public File dumpHeap() {
    if (!leakDirectoryProvider.isLeakStorageWritable()) {
      CanaryLog.d("Could not write to leak storage to dump heap.");
      leakDirectoryProvider.requestWritePermission();
      return NO_DUMP;
    }
    File heapDumpFile = getHeapDumpFile();
    // Atomic way to check for existence & create the file if it doesn't exist.
    // Prevents several processes in the same app to attempt a heapdump at the same time.
    boolean fileCreated;
    try {
      fileCreated = heapDumpFile.createNewFile();
    } catch (IOException e) {
      cleanup();
      CanaryLog.d(e, "Could not check if heap dump file exists");
      return NO_DUMP;
    }

    if (!fileCreated) {
      CanaryLog.d("Could not dump heap, previous analysis still is in progress.");
      // Heap analysis in progress, let's not put too much pressure on the device.
      return NO_DUMP;
    }

    FutureResult<Toast> waitingForToast = new FutureResult<>();
    showToast(waitingForToast);

    if (!waitingForToast.wait(5, SECONDS)) {
      CanaryLog.d("Did not dump heap, too much time waiting for Toast.");
      return NO_DUMP;
    }

    Toast toast = waitingForToast.get();
    try {
      Debug.dumpHprofData(heapDumpFile.getAbsolutePath());
      cancelToast(toast);
      return heapDumpFile;
    } catch (Exception e) {
      cleanup();
      CanaryLog.d(e, "Could not perform heap dump");
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
        if (!leakDirectoryProvider.isLeakStorageWritable()) {
          CanaryLog.d("Could not attempt cleanup, leak storage not writable.");
          return;
        }
        File heapDumpFile = getHeapDumpFile();
        if (heapDumpFile.exists()) {
          CanaryLog.d("Previous analysis did not complete correctly, cleaning: %s", heapDumpFile);
          boolean success = heapDumpFile.delete();
          if (!success) {
            CanaryLog.d("Could not delete file %s", heapDumpFile.getPath());
          }
        }
      }
    });
  }

  File getHeapDumpFile() {
    return new File(leakDirectoryProvider.leakDirectory(), HEAPDUMP_FILE);
  }

  private void showToast(final FutureResult<Toast> waitingForToast) {
    mainHandler.post(new Runnable() {
      @Override public void run() {
        final Toast toast = new Toast(context);
        toast.setGravity(Gravity.CENTER_VERTICAL, 0, 0);
        toast.setDuration(Toast.LENGTH_LONG);
        LayoutInflater inflater = LayoutInflater.from(context);
        toast.setView(inflater.inflate(R.layout.leak_canary_heap_dump_toast, null));
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
