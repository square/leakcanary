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

  public static Builder builder() {
    return new BuilderWithParams();
  }

  public final Map<String, Map<String, Exclusion>> fieldNameByClassName;
  public final Map<String, Map<String, Exclusion>> staticFieldNameByClassName;
  public final Map<String, Exclusion> threadNames;
  public final Map<String, Exclusion> classNames;

  ExcludedRefs(BuilderWithParams builder) {
    this.fieldNameByClassName = unmodifiableRefStringMap(builder.fieldNameByClassName);
    this.staticFieldNameByClassName = unmodifiableRefStringMap(builder.staticFieldNameByClassName);
    this.threadNames = unmodifiableRefMap(builder.threadNames);
    this.classNames = unmodifiableRefMap(builder.classNames);
  }

  private Map<String, Map<String, Exclusion>> unmodifiableRefStringMap(
      Map<String, Map<String, ParamsBuilder>> mapmap) {
    LinkedHashMap<String, Map<String, Exclusion>> fieldNameByClassName = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, ParamsBuilder>> entry : mapmap.entrySet()) {
      fieldNameByClassName.put(entry.getKey(), unmodifiableRefMap(entry.getValue()));
    }
    return unmodifiableMap(fieldNameByClassName);
  }

  private Map<String, Exclusion> unmodifiableRefMap(Map<String, ParamsBuilder> fieldBuilderMap) {
    Map<String, Exclusion> fieldMap = new LinkedHashMap<>();
    for (Map.Entry<String, ParamsBuilder> fieldEntry : fieldBuilderMap.entrySet()) {
      fieldMap.put(fieldEntry.getKey(), new Exclusion(fieldEntry.getValue()));
    }
    return unmodifiableMap(fieldMap);
  }

  @Override public String toString() {
    String string = "";
    for (Map.Entry<String, Map<String, Exclusion>> classes : fieldNameByClassName.entrySet()) {
      String clazz = classes.getKey();
      for (Map.Entry<String, Exclusion> field : classes.getValue().entrySet()) {
        String always = field.getValue().alwaysExclude ? " (always)" : "";
        string += "| Field: " + clazz + "." + field.getKey() + always + "\n";
      }
    }
    for (Map.Entry<String, Map<String, Exclusion>> classes : staticFieldNameByClassName.entrySet()) {
      String clazz = classes.getKey();
      for (Map.Entry<String, Exclusion> field : classes.getValue().entrySet()) {
        String always = field.getValue().alwaysExclude ? " (always)" : "";
        string += "| Static field: " + clazz + "." + field.getKey() + always + "\n";
      }
    }
    for (Map.Entry<String, Exclusion> thread : threadNames.entrySet()) {
      String always = thread.getValue().alwaysExclude ? " (always)" : "";
      string += "| Thread:" + thread.getKey() + always + "\n";
    }
    for (Map.Entry<String, Exclusion> clazz : classNames.entrySet()) {
      String always = clazz.getValue().alwaysExclude ? " (always)" : "";
      string += "| Class:" + clazz.getKey() + always + "\n";
    }
    return string;
  }

  static final class ParamsBuilder {
    String name;
    String reason;
    boolean alwaysExclude;
    final String matching;

    ParamsBuilder(String matching) {
      this.matching = matching;
    }
  }

  public interface Builder {
    BuilderWithParams instanceField(String className, String fieldName);

    BuilderWithParams staticField(String className, String fieldName);

    BuilderWithParams thread(String threadName);

    BuilderWithParams clazz(String className);

    ExcludedRefs build();
  }

  public static final class BuilderWithParams implements Builder {

    private final Map<String, Map<String, ParamsBuilder>> fieldNameByClassName =
        new LinkedHashMap<>();
    private final Map<String, Map<String, ParamsBuilder>> staticFieldNameByClassName =
        new LinkedHashMap<>();
    private final Map<String, ParamsBuilder> threadNames = new LinkedHashMap<>();
    private final Map<String, ParamsBuilder> classNames = new LinkedHashMap<>();

    private ParamsBuilder lastParams;

    BuilderWithParams() {
    }

    @Override public BuilderWithParams instanceField(String className, String fieldName) {
      checkNotNull(className, "className");
      checkNotNull(fieldName, "fieldName");
      Map<String, ParamsBuilder> excludedFields = fieldNameByClassName.get(className);
      if (excludedFields == null) {
        excludedFields = new LinkedHashMap<>();
        fieldNameByClassName.put(className, excludedFields);
      }
      lastParams = new ParamsBuilder("field " + className + "#" + fieldName);
      excludedFields.put(fieldName, lastParams);
      return this;
    }

    @Override public BuilderWithParams staticField(String className, String fieldName) {
      checkNotNull(className, "className");
      checkNotNull(fieldName, "fieldName");
      Map<String, ParamsBuilder> excludedFields = staticFieldNameByClassName.get(className);
      if (excludedFields == null) {
        excludedFields = new LinkedHashMap<>();
        staticFieldNameByClassName.put(className, excludedFields);
      }
      lastParams = new ParamsBuilder("static field " + className + "#" + fieldName);
      excludedFields.put(fieldName, lastParams);
      return this;
    }

    @Override public BuilderWithParams thread(String threadName) {
      checkNotNull(threadName, "threadName");
      lastParams = new ParamsBuilder("any threads named " + threadName);
      threadNames.put(threadName, lastParams);
      return this;
    }

    /** Ignores all fields and static fields of all subclasses of the provided class name. */
    @Override public BuilderWithParams clazz(String className) {
      checkNotNull(className, "className");
      lastParams = new ParamsBuilder("any subclass of " + className);
      classNames.put(className, lastParams);
      return this;
    }

    public BuilderWithParams named(String name) {
      lastParams.name = name;
      return this;
    }

    public BuilderWithParams reason(String reason) {
      lastParams.reason = reason;
      return this;
    }

    public BuilderWithParams alwaysExclude() {
      lastParams.alwaysExclude = true;
      return this;
    }

    @Override public ExcludedRefs build() {
      return new ExcludedRefs(this);
    }
  }
}
