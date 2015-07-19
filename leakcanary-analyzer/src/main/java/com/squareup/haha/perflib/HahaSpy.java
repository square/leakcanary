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
package com.squareup.haha.perflib;

public final class HahaSpy {

  public static Instance allocatingThread(Instance instance) {
    Snapshot snapshot = instance.mHeap.mSnapshot;
    int threadSerialNumber;
    if (instance instanceof RootObj) {
      threadSerialNumber = ((RootObj) instance).mThread;
    } else {
      threadSerialNumber = instance.mStack.mThreadSerialNumber;
    }
    ThreadObj thread = snapshot.getThread(threadSerialNumber);
    return snapshot.findInstance(thread.mId);
  }

  private HahaSpy() {
    throw new AssertionError();
  }
}
