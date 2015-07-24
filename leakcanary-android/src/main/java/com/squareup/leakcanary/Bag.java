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

import java.io.File;
import java.io.Serializable;
import java.util.List;

import static com.squareup.leakcanary.Preconditions.checkNotNull;

public final class Bag implements Serializable {

  /** The heap dump file. */
  public final File heapDumpFile;

  /** References that should be ignored when analyzing this heap dump. */
  public final ExcludedRefs excludedRefs;

  public final List<OOMAutopsy.ZombieMatcher> zombieMatchers;

  public final long gcDurationMs;
  public final long heapDumpDurationMs;

  public Bag(File heapDumpFile, ExcludedRefs excludedRefs,
      List<OOMAutopsy.ZombieMatcher> zombieMatchers, long gcDurationMs, long heapDumpDurationMs) {
    this.zombieMatchers = zombieMatchers;
    this.heapDumpFile = checkNotNull(heapDumpFile, "heapDumpFile");
    this.excludedRefs = checkNotNull(excludedRefs, "excludedRefs");
    this.gcDurationMs = gcDurationMs;
    this.heapDumpDurationMs = heapDumpDurationMs;
  }
}
