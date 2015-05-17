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
import java.util.ArrayList;
import java.util.List;

import static com.squareup.leakcanary.LeakTraceElement.Holder.ARRAY;
import static com.squareup.leakcanary.LeakTraceElement.Holder.CLASS;
import static com.squareup.leakcanary.LeakTraceElement.Holder.THREAD;
import static com.squareup.leakcanary.LeakTraceElement.Type.STATIC_FIELD;
import static java.util.Collections.unmodifiableList;
import static java.util.Locale.US;

/** Represents one reference in the chain of references that holds a leaking object in memory. */
public final class LeakTraceElement implements Serializable {

  public enum Type {
    INSTANCE_FIELD, STATIC_FIELD, LOCAL
  }

  public enum Holder {
    OBJECT, CLASS, THREAD, ARRAY
  }

  /** Null if this is the last element in the leak trace, ie the leaking object. */
  public final String referenceName;

  /** Null if this is the last element in the leak trace, ie the leaking object. */
  public final Type type;
  public final Holder holder;
  public final String className;

  /** Additional information, may be null. */
  public final String extra;

  /** List of all fields (member and static) for that object. */
  public final List<String> fields;

  LeakTraceElement(String referenceName, Type type, Holder holder, String className, String extra,
      List<String> fields) {
    this.referenceName = referenceName;
    this.type = type;
    this.holder = holder;
    this.className = className;
    this.extra = extra;
    this.fields = unmodifiableList(new ArrayList<>(fields));
  }

  @Override public String toString() {
    String string = "";

    if (type == STATIC_FIELD) {
      string += "static ";
    }

    if (holder == ARRAY || holder == THREAD) {
      string += holder.name().toLowerCase(US) + " ";
    }

    string += className;

    if (referenceName != null) {
      string += "." + referenceName;
    } else {
      string += " instance";
    }

    if (extra != null) {
      string += " " + extra;
    }
    return string;
  }

  public String toDetailedString() {
    String string = "* ";
    if (holder == ARRAY) {
      string += "Array of";
    } else if (holder == CLASS) {
      string += "Class";
    } else {
      string += "Instance of";
    }
    string += " " + className + "\n";
    for (String field : fields) {
      string += "|   " + field + "\n";
    }
    return string;
  }
}
