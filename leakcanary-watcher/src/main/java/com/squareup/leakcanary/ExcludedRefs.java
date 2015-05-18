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

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static com.squareup.leakcanary.Preconditions.checkNotNull;
import static java.util.Collections.unmodifiableMap;
import static java.util.Collections.unmodifiableSet;

/**
 * Prevents specific references from being taken into account when computing the shortest strong
 * reference path from a suspected leaking instance to the GC roots.
 *
 * This class lets you ignore known memory leaks that you known about. If the shortest path
 * matches {@link ExcludedRefs}, than the heap analyzer should look for a longer path with nothing
 * matching in {@link ExcludedRefs}.
 */
public final class ExcludedRefs implements Serializable {

  public final Map<String, Set<String>> excludeFieldMap;
  public final Map<String, Set<String>> excludeStaticFieldMap;
  public final Set<String> excludedThreads;

  private ExcludedRefs(Map<String, Set<String>> excludeFieldMap,
      Map<String, Set<String>> excludeStaticFieldMap, Set<String> excludedThreads) {
    // Copy + unmodifiable.
    this.excludeFieldMap = unmodifiableMap(new LinkedHashMap<>(excludeFieldMap));
    this.excludeStaticFieldMap = unmodifiableMap(new LinkedHashMap<>(excludeStaticFieldMap));
    this.excludedThreads = unmodifiableSet(new LinkedHashSet<>(excludedThreads));
  }

  @Override public String toString() {
    String string = "";
    for (Map.Entry<String, Set<String>> classes : excludeFieldMap.entrySet()) {
      String clazz = classes.getKey();
      for (String field : classes.getValue()) {
        string += "| Field: " + clazz + "." + field + "\n";
      }
    }
    for (Map.Entry<String, Set<String>> classes : excludeStaticFieldMap.entrySet()) {
      String clazz = classes.getKey();
      for (String field : classes.getValue()) {
        string += "| Static field: " + clazz + "." + field + "\n";
      }
    }
    for (String thread : excludedThreads) {
      string += "| Thread:" + thread;
    }
    return string;
  }

  public static final class Builder {
    private final Map<String, Set<String>> excludeFieldMap = new LinkedHashMap<>();
    private final Map<String, Set<String>> excludeStaticFieldMap = new LinkedHashMap<>();
    private final Set<String> excludedThreads = new LinkedHashSet<>();

    public Builder instanceField(String className, String fieldName) {
      checkNotNull(className, "className");
      checkNotNull(fieldName, "fieldName");
      Set<String> excludedFields = excludeFieldMap.get(className);
      if (excludedFields == null) {
        excludedFields = new LinkedHashSet<>();
        excludeFieldMap.put(className, excludedFields);
      }
      excludedFields.add(fieldName);
      return this;
    }

    public Builder staticField(String className, String fieldName) {
      checkNotNull(className, "className");
      checkNotNull(fieldName, "fieldName");
      Set<String> excludedFields = excludeStaticFieldMap.get(className);
      if (excludedFields == null) {
        excludedFields = new LinkedHashSet<>();
        excludeStaticFieldMap.put(className, excludedFields);
      }
      excludedFields.add(fieldName);
      return this;
    }

    public Builder thread(String threadName) {
      checkNotNull(threadName, "threadName");
      excludedThreads.add(threadName);
      return this;
    }

    public ExcludedRefs build() {
      return new ExcludedRefs(excludeFieldMap, excludeStaticFieldMap, excludedThreads);
    }
  }
}
