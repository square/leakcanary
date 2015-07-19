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
import java.util.Map;

import static com.squareup.leakcanary.Preconditions.checkNotNull;
import static java.util.Collections.unmodifiableMap;

/**
 * Prevents specific references from being taken into account when computing the shortest strong
 * reference path from a suspected leaking instance to the GC roots.
 *
 * This class lets you ignore known memory leaks that you known about. If the shortest path
 * matches {@link ExcludedRefs}, than the heap analyzer should look for a longer path with nothing
 * matching in {@link ExcludedRefs}.
 */
public final class ExcludedRefs implements Serializable {

  public final Map<String, Map<String, Boolean>> fieldNameByClassName;
  public final Map<String, Map<String, Boolean>> staticFieldNameByClassName;
  public final Map<String, Boolean> threadNames;
  public final Map<String, Boolean> classNames;

  private ExcludedRefs(Map<String, Map<String, Boolean>> fieldNameByClassName,
      Map<String, Map<String, Boolean>> staticFieldNameByClassName,
      Map<String, Boolean> threadNames, Map<String, Boolean> classNames) {
    // Copy + unmodifiable.
    this.fieldNameByClassName = unmodifiableMap(new LinkedHashMap<>(fieldNameByClassName));
    this.staticFieldNameByClassName =
        unmodifiableMap(new LinkedHashMap<>(staticFieldNameByClassName));
    this.threadNames = unmodifiableMap(new LinkedHashMap<>(threadNames));
    this.classNames = unmodifiableMap(new LinkedHashMap<>(classNames));
  }

  @Override public String toString() {
    String string = "";
    for (Map.Entry<String, Map<String, Boolean>> classes : fieldNameByClassName.entrySet()) {
      String clazz = classes.getKey();
      for (Map.Entry<String, Boolean> field : classes.getValue().entrySet()) {
        String always = field.getValue() ? " (always)" : "";
        string += "| Field: " + clazz + "." + field.getKey() + always + "\n";
      }
    }
    for (Map.Entry<String, Map<String, Boolean>> classes : staticFieldNameByClassName.entrySet()) {
      String clazz = classes.getKey();
      for (Map.Entry<String, Boolean> field : classes.getValue().entrySet()) {
        String always = field.getValue() ? " (always)" : "";
        string += "| Static field: " + clazz + "." + field.getKey() + always + "\n";
      }
    }
    for (Map.Entry<String, Boolean> thread : threadNames.entrySet()) {
      String always = thread.getValue() ? " (always)" : "";
      string += "| Thread:" + thread.getKey() + always + "\n";
    }
    for (Map.Entry<String, Boolean> clazz : classNames.entrySet()) {
      String always = clazz.getValue() ? " (always)" : "";
      string += "| Class:" + clazz.getKey() + always + "\n";
    }
    return string;
  }

  public static final class Builder {
    private final Map<String, Map<String, Boolean>> fieldNameByClassName = new LinkedHashMap<>();
    private final Map<String, Map<String, Boolean>> staticFieldNameByClassName =
        new LinkedHashMap<>();
    private final Map<String, Boolean> threadNames = new LinkedHashMap<>();
    private final Map<String, Boolean> classNames = new LinkedHashMap<>();

    public Builder instanceField(String className, String fieldName) {
      return instanceField(className, fieldName, false);
    }

    public Builder instanceField(String className, String fieldName, boolean always) {
      checkNotNull(className, "className");
      checkNotNull(fieldName, "fieldName");
      Map<String, Boolean> excludedFields = fieldNameByClassName.get(className);
      if (excludedFields == null) {
        excludedFields = new LinkedHashMap<>();
        fieldNameByClassName.put(className, excludedFields);
      }
      excludedFields.put(fieldName, always);
      return this;
    }

    public Builder staticField(String className, String fieldName) {
      return staticField(className, fieldName, false);
    }

    public Builder staticField(String className, String fieldName, boolean always) {
      checkNotNull(className, "className");
      checkNotNull(fieldName, "fieldName");
      Map<String, Boolean> excludedFields = staticFieldNameByClassName.get(className);
      if (excludedFields == null) {
        excludedFields = new LinkedHashMap<>();
        staticFieldNameByClassName.put(className, excludedFields);
      }
      excludedFields.put(fieldName, always);
      return this;
    }

    public Builder thread(String threadName) {
      return thread(threadName, false);
    }

    public Builder thread(String threadName, boolean always) {
      checkNotNull(threadName, "threadName");
      threadNames.put(threadName, always);
      return this;
    }

    public Builder clazz(String className) {
      return thread(className, false);
    }

    public Builder clazz(String className, boolean always) {
      checkNotNull(className, "className");
      classNames.put(className, always);
      return this;
    }

    public ExcludedRefs build() {
      return new ExcludedRefs(fieldNameByClassName, staticFieldNameByClassName, threadNames,
          classNames);
    }
  }
}
