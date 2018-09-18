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

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.Toast;
import com.squareup.leakcanary.internal.FutureResult;
import com.squareup.leakcanary.internal.LeakCanaryInternals;
import java.io.File;

import static android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED;
import static java.util.concurrent.TimeUnit.SECONDS;

public final class AndroidHeapDumper implements HeapDumper {

  private final Context context;
  private final LeakDirectoryProvider leakDirectoryProvider;
  private final Handler mainHandler;

  public AndroidHeapDumper(@NonNull Context context,
                           @NonNull LeakDirectoryProvider leakDirectoryProvider) {
    this.leakDirectoryProvider = leakDirectoryProvider;
    this.context = context.getApplicationContext();
    mainHandler = new Handler(Looper.getMainLooper());
  }

  @SuppressWarnings("ReferenceEquality") // Explicitly checking for named null.
  @Override @Nullable
  public File dumpHeap() {
    File heapDumpFile = leakDirectoryProvider.newHeapDumpFile();

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
    try {
      Debug.dumpHprofData(heapDumpFile.getAbsolutePath());
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
        final Toast toast = new Toast(context);
        toast.setGravity(Gravity.CENTER_VERTICAL, 0, 0);
        toast.setDuration(Toast.LENGTH_LONG);
        LayoutInflater inflater = LayoutInflater.from(context);
        toast.setView(inflater.inflate(R.layout.leak_canary_heap_dump_toast, null));
        show(toast);
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
        hide(toast);
      }
    });
  }

  private void show(Toast toast) {
    View view = toast.getView();
    Context context = toast.getView().getContext();

    WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

    // We can resolve the Gravity here by using the Locale for getting
    // the layout direction
    Configuration config = view.getContext().getResources().getConfiguration();
    int gravity = toast.getGravity();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
      gravity = Gravity.getAbsoluteGravity(gravity, config.getLayoutDirection());
    }

    WindowManager.LayoutParams params = buildLayoutParams();
    params.gravity = gravity;
    if ((gravity & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.FILL_HORIZONTAL) {
      params.horizontalWeight = 1.0f;
    }
    if ((gravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.FILL_VERTICAL) {
      params.verticalWeight = 1.0f;
    }
    params.x = toast.getXOffset();
    params.y = toast.getYOffset();
    params.verticalMargin = toast.getVerticalMargin();
    params.horizontalMargin = toast.getHorizontalMargin();
    params.packageName = context.getPackageName();
    try {
      windowManager.addView(view, params);
    } catch (WindowManager.BadTokenException ignored) {
      CanaryLog.d("Could not show leak toast, the window token has been canceled");
      return;
    }
    trySendAccessibilityEvent(view);
  }

  private void hide(Toast toast) {
    View view = toast.getView();
    if (view.getParent() != null) {
      Context context = toast.getView().getContext();
      WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
      windowManager.removeView(view);
    }
  }

  private WindowManager.LayoutParams buildLayoutParams() {
    WindowManager.LayoutParams params = new WindowManager.LayoutParams();
    params.height = WindowManager.LayoutParams.WRAP_CONTENT;
    params.width = WindowManager.LayoutParams.WRAP_CONTENT;
    params.format = PixelFormat.TRANSLUCENT;
    params.windowAnimations = android.R.style.Animation_Toast;
    params.type = WindowManager.LayoutParams.TYPE_TOAST;
    params.setTitle("Toast");
    params.flags = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
    return params;
  }

  private void trySendAccessibilityEvent(View view) {
    Context context = view.getContext();
    AccessibilityManager accessibilityManager =
        (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
    if (!accessibilityManager.isEnabled()) {
      return;
    }
    // treat toasts as notifications since they are used to
    // announce a transient piece of information to the user
    AccessibilityEvent event = AccessibilityEvent.obtain(TYPE_NOTIFICATION_STATE_CHANGED);
    event.setClassName(getClass().getName());
    event.setPackageName(context.getPackageName());
    view.dispatchPopulateAccessibilityEvent(event);
    accessibilityManager.sendAccessibilityEvent(event);
  }


}
