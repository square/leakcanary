/*
 * Copyright (C) 2018 Square, Inc.
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

/** Result returned by {@link Inspector#expectedReachability(LeakTraceElement)}. */
public enum Reachability {
  /** The instance was needed and therefore expected to be reachable. */
  REACHABLE,

  /** The instance was no longer needed and therefore expected to be unreachable. */
  UNREACHABLE,

  /** No decision can be made about the provided instance. */
  UNKNOWN;

  /**
   * Evaluates whether a {@link LeakTraceElement} should be reachable or not.
   *
   * Implementations should have a public zero argument constructor as instances will be created
   * via reflection in the LeakCanary analysis process.
   */
  public interface Inspector {

    Reachability expectedReachability(LeakTraceElement element);
  }
}
