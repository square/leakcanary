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

import java.io.Serializable;

/** Result returned by {@link Inspector#expectedReachability(LeakTraceElement)}. */
public final class Reachability implements Serializable {

  private static final Reachability UNKNOWN_REACHABILITY = new Reachability(Status.UNKNOWN, "");

  /** The instance was needed and therefore expected to be reachable. */
  public static Reachability reachable(String reason) {
    return new Reachability(Status.REACHABLE, reason);
  }

  /** The instance was no longer needed and therefore expected to be unreachable. */
  public static Reachability unreachable(String reason) {
    return new Reachability(Status.UNREACHABLE, reason);
  }

  /** No decision can be made about the provided instance. */
  public static Reachability unknown() {
    return UNKNOWN_REACHABILITY;
  }

  public enum Status {
    REACHABLE,
    UNREACHABLE,
    UNKNOWN
  }

  public final String reason;
  public final Status status;

  private Reachability(Status status, String reason) {
    this.reason = reason;
    this.status = status;
  }

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
