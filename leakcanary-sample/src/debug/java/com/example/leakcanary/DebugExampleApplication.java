/*
 * Copyright (C) 2016 Square, Inc.
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
package com.example.leakcanary;

import com.squareup.leakcanary.LeakCanary;

public class DebugExampleApplication extends ExampleApplication {

  @Override public void onCreate() {
    // Often times this method is overridden so app dependencies are initialized
    // and set up. For single-process applications where some work is being done in this
    // method, allowing it to continue as normal can lead to subtle bugs. One would expect
    // that that work would be done only once in the lifecycle of the application - which
    // is technically still true since the app is created in a different process, and might
    // not realize that LeakCanary starts a new process which leads to this method being
    // called while there is already an instance of the application in a different process.
    // LeakCanary does not need any setup while it analyzes a heap dump in its analyzer process,
    // no one benefits from any work that would be done in 'ExampleApplication.onCreate' since
    // no app code would be executed after this method exits, so it is safe to not call super.
    if (!LeakCanary.isInAnalyzerProcess(this)) {
      super.onCreate();
    }
  }
}
