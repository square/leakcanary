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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class FutureResult<T> {

  private final AtomicReference<T> resultHolder;
  private final CountDownLatch latch;

  public FutureResult() {
    resultHolder = new AtomicReference<>();
    latch = new CountDownLatch(1);
  }

  public boolean wait(long timeout, TimeUnit unit) {
    try {
      return latch.await(timeout, unit);
    } catch (InterruptedException e) {
      throw new RuntimeException("Did not expect thread to be interrupted", e);
    }
  }

  public T get() {
    if (latch.getCount() > 0) {
      throw new IllegalStateException("Call wait() and check its result");
    }
    return resultHolder.get();
  }

  public void set(T result) {
    resultHolder.set(result);
    latch.countDown();
  }
}
