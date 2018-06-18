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
import java.util.Collections;
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
    INSTANCE_FIELD, STATIC_FIELD, LOCAL, ARRAY_ENTRY
  }

  public enum Holder {
    OBJECT, CLASS, THREAD, ARRAY
  }

  /** Null if this is the last element in the leak trace, ie the leaking object. */
  public final LeakReference reference;

  /**
   * @deprecated Use {@link #reference} and {@link LeakReference#getDisplayName()} instead.
   * Null if this is the last element in the leak trace, ie the leaking object.
   */
  @Deprecated
  public final String referenceName;

  /**
   * @deprecated Use {@link #reference} and {@link LeakReference#type} instead.
   * Null if this is the last element in the leak trace, ie the leaking object.
   */
  @Deprecated
  public final Type type;

  public final Holder holder;

  /**
   * Class hierarchy for that object. The first element is {@link #className}. {@link Object}
   * is excluded. There is always at least one element.
   */
  public final List<String> classHierarchy;

  public final String className;

  /** Additional information, may be null. */
  public final String extra;

  /** If not null, there was no path that could exclude this element. */
  public final Exclusion exclusion;

  /** List of all fields (member and static) for that object. */
  public final List<LeakReference> fieldReferences;

  /**
   * @deprecated Use {@link #fieldReferences} instead.
   */
  @Deprecated
  public final List<String> fields;

  LeakTraceElement(LeakReference reference, Holder holder, List<String> classHierarchy,
      String extra, Exclusion exclusion, List<LeakReference> leakReferences) {
    this.reference = reference;
    this.referenceName = reference == null ? null : reference.getDisplayName();
    this.type = reference == null ? null : reference.type;
    this.holder = holder;
    this.classHierarchy = Collections.unmodifiableList(new ArrayList<>(classHierarchy));
    this.className = classHierarchy.get(0);
    this.extra = extra;
    this.exclusion = exclusion;
    this.fieldReferences = unmodifiableList(new ArrayList<>(leakReferences));
    List<String> stringFields = new ArrayList<>();
    for (LeakReference leakReference : leakReferences) {
      stringFields.add(leakReference.toString());
    }
    fields = Collections.unmodifiableList(stringFields);
  }

  @Override public String toString() {
    String string = "";

    if (reference != null && reference.type == STATIC_FIELD) {
      string += "static ";
    }

    if (holder == ARRAY || holder == THREAD) {
      string += holder.name().toLowerCase(US) + " ";
    }

    string += classHierarchy.get(0);

    if (reference != null) {
      string += "." + reference.getDisplayName();
    } else {
      string += " instance";
    }

    if (extra != null) {
      string += " " + extra;
    }

    if (exclusion != null) {
      string += " , matching exclusion " + exclusion.matching;
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
    for (LeakReference leakReference : fieldReferences) {
      string += "|   " + leakReference + "\n";
    }
    return string;
  }
}
