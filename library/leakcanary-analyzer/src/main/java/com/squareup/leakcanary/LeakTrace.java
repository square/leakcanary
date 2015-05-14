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

import static java.util.Collections.unmodifiableList;

/**
 * A chain of references that constitute the shortest strong reference path from a leaking instance
 * to the GC roots. Fixing the leak usually means breaking one of the references in that chain.
 */
public final class LeakTrace implements Serializable {

  public final List<LeakTraceElement> elements;

  LeakTrace(List<LeakTraceElement> elements) {
    this.elements = unmodifiableList(new ArrayList<>(elements));
  }

  @Override public String toString() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < elements.size(); i++) {
      LeakTraceElement element = elements.get(i);
      sb.append("* ");
      if (i == 0) {
        sb.append("GC ROOT ");
      } else if (i == elements.size() - 1) {
        sb.append("leaks ");
      } else {
        sb.append("references ");
      }
      sb.append(element).append("\n");
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
}
