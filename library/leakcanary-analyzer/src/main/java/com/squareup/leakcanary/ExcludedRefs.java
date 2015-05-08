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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static com.squareup.leakcanary.Preconditions.checkNotNull;

/**
 * Prevents specific references from being taken into account when computing the shortest strong
 * reference path from a suspected leaking instance to the GC roots.
 *
 * This class lets you ignore known memory leaks that you known about. If the shortest path
 * matches {@link ExcludedRefs}, than the {@link HeapAnalyzer} looks for a longer path with nothing
 * matching in {@link ExcludedRefs}.
 */
public final class ExcludedRefs {

  final Map<String, Set<String>> excludeFieldMap = new LinkedHashMap<>();
  final Map<String, Set<String>> excludeStaticFieldMap = new LinkedHashMap<>();
  final Set<String> excludedThreads = new LinkedHashSet<>();

  public void instanceField(String className, String fieldName) {
    checkNotNull(className, "className");
    checkNotNull(fieldName, "fieldName");
    Set<String> excludedFields = excludeFieldMap.get(className);
    if (excludedFields == null) {
      excludedFields = new LinkedHashSet<>();
      excludeFieldMap.put(className, excludedFields);
    }
    excludedFields.add(fieldName);
  }

  public void staticField(String className, String fieldName) {
    checkNotNull(className, "className");
    checkNotNull(fieldName, "fieldName");
    Set<String> excludedFields = excludeStaticFieldMap.get(className);
    if (excludedFields == null) {
      excludedFields = new LinkedHashSet<>();
      excludeStaticFieldMap.put(className, excludedFields);
    }
    excludedFields.add(fieldName);
  }

  public void thread(String threadName) {
    checkNotNull(threadName, "threadName");
    excludedThreads.add(threadName);
  }
}
