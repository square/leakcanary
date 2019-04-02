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

import android.app.Activity;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.squareup.leakcanary.internal.ActivityLifecycleCallbacksAdapter;
import com.squareup.leakcanary.internal.FutureResult;
import com.squareup.leakcanary.internal.LeakCanaryInternals;
import java.io.File;
import java.util.Set;

import static java.util.concurrent.TimeUnit.SECONDS;

public final class AndroidHeapDumper implements HeapDumper {

  private final Context context;
  private final RefWatcher refWatcher;
  private final LeakDirectoryProvider leakDirectoryProvider;
  private final Handler mainHandler;

  private Activity resumedActivity;

  public AndroidHeapDumper(@NonNull Context context,
      @NonNull LeakDirectoryProvider leakDirectoryProvider, RefWatcher refWatcher) {
    this.leakDirectoryProvider = leakDirectoryProvider;
    this.context = context.getApplicationContext();
    this.refWatcher = refWatcher;
    mainHandler = new Handler(Looper.getMainLooper());

    Application application = (Application) context.getApplicationContext();
    application.registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacksAdapter() {
      @Override public void onActivityResumed(Activity activity) {
        resumedActivity = activity;
      }

      @Override public void onActivityPaused(Activity activity) {
        if (resumedActivity == activity) {
          resumedActivity = null;
        }
      }
    });
  }

  @SuppressWarnings("ReferenceEquality") // Explicitly checking for named null.
  @Override @Nullable
  public File dumpHeap() {
    final File heapDumpFile = leakDirectoryProvider.newHeapDumpFile();

    if (heapDumpFile == RETRY_LATER) {
      return RETRY_LATER;
    }

    FutureResult<Toast> waitingForToast = new FutureResult<>();
    showToast(waitingForToast);

    if (!waitingForToast.wait(5, SECONDS)) {
      CanaryLog.d("Did not dump heap, too much time waiting for Toast.");
      return RETRY_LATER;
    }

    Notification.Builder builder = new Notification.Builder(context)
        .setContentTitle(context.getString(R.string.leak_canary_notification_dumping));
    Notification notification = LeakCanaryInternals.buildNotification(context, builder);
    NotificationManager notificationManager =
        (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    int notificationId = (int) SystemClock.uptimeMillis();
    notificationManager.notify(notificationId, notification);

    Toast toast = waitingForToast.get();
    Set<String> retainedKeys = refWatcher.getRetainedKeysOlderThan(AndroidRefWatcherBuilder.DEFAULT_WATCH_DELAY_MILLIS);

    if (retainedKeys.isEmpty()) {
      // TODO We shouldn't retry at all.
      return RETRY_LATER;
    }

    HeapDumpMemoryStore.setRetainedKeysForHeapDump(retainedKeys.toArray(new String[0]));
    HeapDumpMemoryStore.setHeapDumpUptimeMillis(SystemClock.uptimeMillis());

    try {
      Debug.dumpHprofData(heapDumpFile.getAbsolutePath());
      refWatcher.removeRetainedKeys(retainedKeys);
      cancelToast(toast);
      notificationManager.cancel(notificationId);
      return heapDumpFile;
    } catch (Exception e) {
      CanaryLog.d(e, "Could not dump heap");
      // Abort heap dump
      return RETRY_LATER;
    }
  }

  private void showToast(final FutureResult<Toast> waitingForToast) {
    mainHandler.post(new Runnable() {
      @Override public void run() {
        if (resumedActivity == null) {
          waitingForToast.set(null);
          return;
        }
        final Toast toast = new Toast(resumedActivity);
        toast.setGravity(Gravity.CENTER_VERTICAL, 0, 0);
        toast.setDuration(Toast.LENGTH_LONG);
        LayoutInflater inflater = LayoutInflater.from(resumedActivity);
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
    if (toast == null) {
      return;
    }
    mainHandler.post(new Runnable() {
      @Override public void run() {
        toast.cancel();
      }
    });
  }
}
