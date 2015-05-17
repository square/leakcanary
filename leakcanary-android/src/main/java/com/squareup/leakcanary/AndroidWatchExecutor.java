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
import java.util.concurrent.Executor;

/**
 * {@link Executor} suitable for watching Android reference leaks. This executor waits for the main
 * thread to be idle then posts to a serial background thread with a delay of {@link
 * #DELAY_MILLIS} milliseconds.
 */
public final class AndroidWatchExecutor implements Executor {

  static final String LEAK_CANARY_THREAD_NAME = "LeakCanary-Heap-Dump";
  private static final int DELAY_MILLIS = 5000;

  private final Handler mainHandler;
  private final Handler backgroundHandler;

  public AndroidWatchExecutor() {
    mainHandler = new Handler(Looper.getMainLooper());
    HandlerThread handlerThread = new HandlerThread(LEAK_CANARY_THREAD_NAME);
    handlerThread.start();
    backgroundHandler = new Handler(handlerThread.getLooper());
  }

  @Override public void execute(final Runnable command) {
    if (isOnMainThread()) {
      executeDelayedAfterIdleUnsafe(command);
    } else {
      mainHandler.post(new Runnable() {
        @Override public void run() {
          executeDelayedAfterIdleUnsafe(command);
        }
      });
    }
  }

  private boolean isOnMainThread() {
    return Looper.getMainLooper().getThread() == Thread.currentThread();
  }

  private void executeDelayedAfterIdleUnsafe(final Runnable runnable) {
    // This needs to be called from the main thread.
    Looper.myQueue().addIdleHandler(new MessageQueue.IdleHandler() {
      @Override public boolean queueIdle() {
        backgroundHandler.postDelayed(runnable, DELAY_MILLIS);
        return false;
      }
    });
  }
}
