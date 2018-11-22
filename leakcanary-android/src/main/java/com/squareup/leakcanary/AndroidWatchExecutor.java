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

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.MessageQueue;
import android.support.annotation.NonNull;
import java.util.concurrent.TimeUnit;

import static com.squareup.leakcanary.Retryable.Result.RETRY;

/**
 * {@link WatchExecutor} suitable for watching Android reference leaks. This executor waits for the
 * main thread to be idle then posts to a serial background thread with the delay specified by
 * {@link AndroidRefWatcherBuilder#watchDelay(long, TimeUnit)}.
 */
public final class AndroidWatchExecutor implements WatchExecutor {

  static final String LEAK_CANARY_THREAD_NAME = "LeakCanary-Heap-Dump";
  private final Handler mainHandler;
  private final Handler backgroundHandler;
  private final long initialDelayMillis;
  private final long maxBackoffFactor;

  public AndroidWatchExecutor(long initialDelayMillis) {
    mainHandler = new Handler(Looper.getMainLooper());
    HandlerThread handlerThread = new HandlerThread(LEAK_CANARY_THREAD_NAME);
    handlerThread.start();
    backgroundHandler = new Handler(handlerThread.getLooper());
    this.initialDelayMillis = initialDelayMillis;
    maxBackoffFactor = Long.MAX_VALUE / initialDelayMillis;
  }

  @Override public void execute(@NonNull Retryable retryable) {
    if (Looper.getMainLooper().getThread() == Thread.currentThread()) {
      waitForIdle(retryable, 0);
    } else {
      postWaitForIdle(retryable, 0);
    }
  }

  private void postWaitForIdle(final Retryable retryable, final int failedAttempts) {
    mainHandler.post(new Runnable() {
      @Override public void run() {
        waitForIdle(retryable, failedAttempts);
      }
    });
  }

  private void waitForIdle(final Retryable retryable, final int failedAttempts) {
    // This needs to be called from the main thread.
    Looper.myQueue().addIdleHandler(new MessageQueue.IdleHandler() {
      @Override public boolean queueIdle() {
        postToBackgroundWithDelay(retryable, failedAttempts);
        return false;
      }
    });
  }

  private void postToBackgroundWithDelay(final Retryable retryable, final int failedAttempts) {
    long exponentialBackoffFactor = (long) Math.min(Math.pow(2, failedAttempts), maxBackoffFactor);
    long delayMillis = initialDelayMillis * exponentialBackoffFactor;
    backgroundHandler.postDelayed(new Runnable() {
      @Override public void run() {
        Retryable.Result result = retryable.run();
        if (result == RETRY) {
          postWaitForIdle(retryable, failedAttempts + 1);
        }
      }
    }, delayMillis);
  }
}
