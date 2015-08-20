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

import com.squareup.haha.perflib.ArrayInstance;
import com.squareup.haha.perflib.ClassInstance;
import com.squareup.haha.perflib.ClassObj;
import com.squareup.haha.perflib.Field;
import com.squareup.haha.perflib.HahaSpy;
import com.squareup.haha.perflib.Instance;
import com.squareup.haha.perflib.RootObj;
import com.squareup.haha.perflib.RootType;
import com.squareup.haha.perflib.Snapshot;
import com.squareup.haha.perflib.Type;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

import static com.squareup.leakcanary.HahaHelper.isPrimitiveOrWrapperArray;
import static com.squareup.leakcanary.HahaHelper.isPrimitiveWrapper;
import static com.squareup.leakcanary.HahaHelper.threadName;
import static com.squareup.leakcanary.LeakTraceElement.Type.ARRAY_ENTRY;
import static com.squareup.leakcanary.LeakTraceElement.Type.INSTANCE_FIELD;
import static com.squareup.leakcanary.LeakTraceElement.Type.LOCAL;
import static com.squareup.leakcanary.LeakTraceElement.Type.STATIC_FIELD;

/**
 * Not thread safe.
 *
 * Finds the shortest path from a leaking reference to a gc root, ignoring excluded
 * refs first and then including the ones that are not "always ignorable" as needed if no path is
 * found.
 */
final class ShortestPathFinder {

  private final ExcludedRefs excludedRefs;
  private final Queue<LeakNode> toVisitQueue;
  private final Queue<LeakNode> toVisitIfNoPathQueue;
  private final LinkedHashSet<Instance> toVisitSet;
  private final LinkedHashSet<Instance> toVisitIfNoPathSet;
  private final LinkedHashSet<Instance> visitedSet;
  private boolean canIgnoreStrings;

  ShortestPathFinder(ExcludedRefs excludedRefs) {
    this.excludedRefs = excludedRefs;
    toVisitQueue = new LinkedList<>();
    toVisitIfNoPathQueue = new LinkedList<>();
    toVisitSet = new LinkedHashSet<>();
    toVisitIfNoPathSet = new LinkedHashSet<>();
    visitedSet = new LinkedHashSet<>();
  }

  static final class Result {
    final LeakNode leakingNode;
    final boolean excludingKnownLeaks;

    Result(LeakNode leakingNode, boolean excludingKnownLeaks) {
      this.leakingNode = leakingNode;
      this.excludingKnownLeaks = excludingKnownLeaks;
    }
  }

  Result findPath(Snapshot snapshot, Instance leakingRef) {
    clearState();
    canIgnoreStrings = !isString(leakingRef);

    enqueueGcRoots(snapshot);

    boolean excludingKnownLeaks = false;
    LeakNode leakingNode = null;
    while (!toVisitQueue.isEmpty() || !toVisitIfNoPathQueue.isEmpty()) {
      LeakNode node;
      if (!toVisitQueue.isEmpty()) {
        node = toVisitQueue.poll();
      } else {
        node = toVisitIfNoPathQueue.poll();
        excludingKnownLeaks = true;
      }

      // Termination
      if (node.instance == leakingRef) {
        leakingNode = node;
        break;
      }

      if (checkSeen(node)) {
        continue;
      }

      if (node.instance instanceof RootObj) {
        visitRootObj(node);
      } else if (node.instance instanceof ClassObj) {
        visitClassObj(node);
      } else if (node.instance instanceof ClassInstance) {
        visitClassInstance(node);
      } else if (node.instance instanceof ArrayInstance) {
        visitArrayInstance(node);
      } else {
        throw new IllegalStateException("Unexpected type for " + node.instance);
      }
    }
    return new Result(leakingNode, excludingKnownLeaks);
  }

  private void clearState() {
    toVisitQueue.clear();
    toVisitIfNoPathQueue.clear();
    toVisitSet.clear();
    toVisitIfNoPathSet.clear();
    visitedSet.clear();
  }

  private void enqueueGcRoots(Snapshot snapshot) {
    for (RootObj rootObj : snapshot.getGCRoots()) {
      switch (rootObj.getRootType()) {
        case JAVA_LOCAL:
          Instance thread = HahaSpy.allocatingThread(rootObj);
          String threadName = threadName(thread);
          Boolean alwaysIgnore = excludedRefs.threadNames.get(threadName);
          if (alwaysIgnore == null || !alwaysIgnore) {
            enqueue(alwaysIgnore == null, null, rootObj, null, null);
          }
          break;
        case INTERNED_STRING:
        case DEBUGGER:
        case INVALID_TYPE:
          // An object that is unreachable from any other root, but not a root itself.
        case UNREACHABLE:
        case UNKNOWN:
          // An object that is in a queue, waiting for a finalizer to run.
        case FINALIZING:
          break;
        case SYSTEM_CLASS:
        case VM_INTERNAL:
          // A local variable in native code.
        case NATIVE_LOCAL:
          // A global variable in native code.
        case NATIVE_STATIC:
          // An object that was referenced from an active thread block.
        case THREAD_BLOCK:
          // Everything that called the wait() or notify() methods, or that is synchronized.
        case BUSY_MONITOR:
        case NATIVE_MONITOR:
        case REFERENCE_CLEANUP:
          // Input or output parameters in native code.
        case NATIVE_STACK:
        case JAVA_STATIC:
          enqueue(true, null, rootObj, null, null);
          break;
        default:
          throw new UnsupportedOperationException("Unknown root type:" + rootObj.getRootType());
      }
    }
  }

  private boolean checkSeen(LeakNode node) {
    return !visitedSet.add(node.instance);
  }

  private void visitRootObj(LeakNode node) {
    RootObj rootObj = (RootObj) node.instance;
    Instance child = rootObj.getReferredInstance();

    if (rootObj.getRootType() == RootType.JAVA_LOCAL) {
      Instance holder = HahaSpy.allocatingThread(rootObj);
      // We switch the parent node with the thread instance that holds
      // the local reference.
      LeakNode parent = new LeakNode(holder, null, null, null);
      enqueue(true, parent, child, "<Java Local>", LOCAL);
    } else {
      enqueue(true, node, child, null, null);
    }
  }

  private void visitClassObj(LeakNode node) {
    ClassObj classObj = (ClassObj) node.instance;
    Map<String, Boolean> ignoredStaticFields =
        excludedRefs.staticFieldNameByClassName.get(classObj.getClassName());
    for (Map.Entry<Field, Object> entry : classObj.getStaticFieldValues().entrySet()) {
      Field field = entry.getKey();
      if (field.getType() != Type.OBJECT) {
        continue;
      }
      String fieldName = field.getName();
      if (fieldName.equals("$staticOverhead")) {
        continue;
      }
      Instance child = (Instance) entry.getValue();
      boolean visit = true;
      if (ignoredStaticFields != null) {
        Boolean alwaysIgnore = ignoredStaticFields.get(fieldName);
        if (alwaysIgnore != null) {
          visit = false;
          if (!alwaysIgnore) {
            enqueue(false, node, child, fieldName, STATIC_FIELD);
          }
        }
      }
      if (visit) {
        enqueue(true, node, child, fieldName, STATIC_FIELD);
      }
    }
  }

  private void visitClassInstance(LeakNode node) {
    ClassInstance classInstance = (ClassInstance) node.instance;
    Map<String, Boolean> ignoredFields = null;
    ClassObj superClassObj = classInstance.getClassObj();
    Boolean alwaysIgnoreClassHierarchy = null;
    while (superClassObj != null) {
      Boolean alwaysIgnoreClass = excludedRefs.classNames.get(superClassObj.getClassName());
      if (alwaysIgnoreClass != null) {
        // true overrides null or false.
        if (alwaysIgnoreClassHierarchy == null || !alwaysIgnoreClassHierarchy) {
          alwaysIgnoreClassHierarchy = alwaysIgnoreClass;
        }
      }
      Map<String, Boolean> classIgnoredFields =
          excludedRefs.fieldNameByClassName.get(superClassObj.getClassName());
      if (classIgnoredFields != null) {
        if (ignoredFields == null) {
          ignoredFields = new LinkedHashMap<>();
        }
        ignoredFields.putAll(classIgnoredFields);
      }
      superClassObj = superClassObj.getSuperClassObj();
    }

    if (alwaysIgnoreClassHierarchy != null && alwaysIgnoreClassHierarchy) {
      return;
    }

    for (ClassInstance.FieldValue fieldValue : classInstance.getValues()) {
      Field field = fieldValue.getField();
      if (field.getType() != Type.OBJECT) {
        continue;
      }
      Instance child = (Instance) fieldValue.getValue();
      boolean visit = true;
      boolean visitIfNoPath = false;
      // We don't even get here if alwaysIgnoreClassHierarchy is false.
      if (alwaysIgnoreClassHierarchy != null) {
        visit = false;
        visitIfNoPath = true;
      }
      String fieldName = field.getName();
      if (ignoredFields != null) {
        Boolean alwaysIgnore = ignoredFields.get(fieldName);
        if (alwaysIgnore != null) {
          visit = false;
          visitIfNoPath = !alwaysIgnore;
        }
      }
      if (visit || visitIfNoPath) {
        enqueue(visit, node, child, fieldName, INSTANCE_FIELD);
      }
    }
  }

  private void visitArrayInstance(LeakNode node) {
    ArrayInstance arrayInstance = (ArrayInstance) node.instance;
    Type arrayType = arrayInstance.getArrayType();
    if (arrayType == Type.OBJECT) {
      Object[] values = arrayInstance.getValues();
      for (int i = 0; i < values.length; i++) {
        Instance child = (Instance) values[i];
        enqueue(true, node, child, "[" + i + "]", ARRAY_ENTRY);
      }
    }
  }

  private void enqueue(boolean visitNow, LeakNode parent, Instance child, String referenceName,
      LeakTraceElement.Type referenceType) {
    if (child == null) {
      return;
    }
    if (isPrimitiveOrWrapperArray(child) || isPrimitiveWrapper(child)) {
      return;
    }
    // Whether we want to visit now or later, we should skip if this is already to visit.
    if (toVisitSet.contains(child)) {
      return;
    }
    if (!visitNow && toVisitIfNoPathSet.contains(child)) {
      return;
    }
    if (canIgnoreStrings && isString(child)) {
      return;
    }
    if (visitedSet.contains(child)) {
      return;
    }
    LeakNode childNode = new LeakNode(child, parent, referenceName, referenceType);
    if (visitNow) {
      toVisitSet.add(child);
      toVisitQueue.add(childNode);
    } else {
      toVisitIfNoPathSet.add(child);
      toVisitIfNoPathQueue.add(childNode);
    }
  }

  private boolean isString(Instance instance) {
    return instance.getClassObj() != null && instance.getClassObj()
        .getClassName()
        .equals(String.class.getName());
  }
}
