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

/**
 * A chain of references that constitute the shortest strong reference path from a leaking instance
 * to the GC roots. Fixing the leak usually means breaking one of the references in that chain.
 */
public final class LeakTrace implements Serializable {

  public final List<LeakTraceElement> elements;
  public final List<Reachability> expectedReachability;

  LeakTrace(List<LeakTraceElement> elements, List<Reachability> expectedReachability) {
    this.elements = elements;
    this.expectedReachability = expectedReachability;
  }

  @Override public String toString() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < elements.size(); i++) {
      LeakTraceElement element = elements.get(i);
      sb.append("* ");
      if (i != 0) {
        sb.append("↳ ");
      }
      boolean maybeLeakCause = false;
      Reachability currentReachability = expectedReachability.get(i);
      if (currentReachability == Reachability.UNKNOWN) {
        maybeLeakCause = true;
      } else if (currentReachability == Reachability.REACHABLE) {
        Reachability nextReachability = expectedReachability.get(i + 1);
        if (nextReachability != Reachability.REACHABLE) {
          maybeLeakCause = true;
        }
      }
      sb.append(element.toString(maybeLeakCause)).append("\n");
    }
    return sb.toString();
  }

  public String toDetailedString() {
    String string = "";
    for (LeakTraceElement element : elements) {
      string += element.toDetailedString();
    }
    return string;
  }

  public interface Mapper {
    String mapFieldReferenceValue(LeakTraceElement element, LeakReference ref);
  }

  public LeakTrace map(Mapper mapper) {
    boolean traceChanged = false;
    List<LeakTraceElement> mappedElements = new ArrayList<>();
    for (LeakTraceElement element : this.elements) {
      LeakTraceElement mappedElement = mapLeakTraceElement(mapper, element);
      traceChanged |= mappedElement != element;
      mappedElements.add(mappedElement);
    }
    if (traceChanged) {
      return new LeakTrace(mappedElements, expectedReachability);
    }
    return this;
  }

  private static LeakTraceElement mapLeakTraceElement(Mapper mapper, LeakTraceElement element) {
    List<LeakReference> mappedLeakRefs = new ArrayList<>();
    boolean elementChanged = false;
    for (LeakReference leakReference : element.fieldReferences) {
      LeakReference mappedLeakRef = mapFieldReference(mapper, element, leakReference);
      elementChanged |= mappedLeakRef != leakReference;
      mappedLeakRefs.add(mappedLeakRef);
    }
    if (elementChanged) {
      return new LeakTraceElement(element.reference, element.holder, element.classHierarchy,
          element.extra, element.exclusion, mappedLeakRefs);
    }
    return element;
  }

  private static LeakReference mapFieldReference(Mapper mapper, LeakTraceElement element,
      LeakReference leakReference) {
    String mappedValue = mapper.mapFieldReferenceValue(element, leakReference);
    if (!mappedValue.equals(leakReference.value)) {
      return new LeakReference(leakReference.type, leakReference.name, mappedValue);
    }
    return leakReference;
  }
}
