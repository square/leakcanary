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

/**
 * Gives the opportunity to skip checking if a reference is gone when the debugger is connected.
 * An attached debugger might retain references and create false positives.
 */
public interface DebuggerControl {
  DebuggerControl NONE = new DebuggerControl() {
    @Override public boolean isDebuggerAttached() {
      return false;
    }
  };

  boolean isDebuggerAttached();
}