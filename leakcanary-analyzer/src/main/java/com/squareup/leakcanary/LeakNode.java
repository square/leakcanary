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

import com.squareup.haha.perflib.Instance;

final class LeakNode {
  /** May be null. */
  final Exclusion exclusion;
  final Instance instance;
  final LeakNode parent;
  final String referenceName;
  final LeakTraceElement.Type referenceType;

  LeakNode(Exclusion exclusion, Instance instance, LeakNode parent,
      String referenceName, LeakTraceElement.Type referenceType) {
    this.exclusion = exclusion;
    this.instance = instance;
    this.parent = parent;
    this.referenceName = referenceName;
    this.referenceType = referenceType;
  }
}
