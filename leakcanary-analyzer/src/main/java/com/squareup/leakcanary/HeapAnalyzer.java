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

import android.util.Log;
import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.parser.internal.SnapshotFactory;
import org.eclipse.mat.snapshot.IPathsFromGCRootsComputer;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.PathsFromGCRootsTree;
import org.eclipse.mat.snapshot.model.Field;
import org.eclipse.mat.snapshot.model.IArray;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.snapshot.model.IInstance;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.IObjectArray;
import org.eclipse.mat.snapshot.model.NamedReference;
import org.eclipse.mat.snapshot.model.ObjectReference;
import org.eclipse.mat.snapshot.model.PrettyPrinter;
import org.eclipse.mat.snapshot.model.ThreadToLocalReference;
import org.eclipse.mat.util.VoidProgressListener;

import static com.squareup.leakcanary.AnalysisResult.failure;
import static com.squareup.leakcanary.AnalysisResult.leakDetected;
import static com.squareup.leakcanary.AnalysisResult.noLeak;
import static com.squareup.leakcanary.LeakTraceElement.Holder.ARRAY;
import static com.squareup.leakcanary.LeakTraceElement.Holder.CLASS;
import static com.squareup.leakcanary.LeakTraceElement.Holder.OBJECT;
import static com.squareup.leakcanary.LeakTraceElement.Holder.THREAD;
import static com.squareup.leakcanary.LeakTraceElement.Type.INSTANCE_FIELD;
import static com.squareup.leakcanary.LeakTraceElement.Type.LOCAL;
import static com.squareup.leakcanary.LeakTraceElement.Type.STATIC_FIELD;
import static java.lang.Integer.MAX_VALUE;
import static java.util.Collections.emptyMap;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * Analyzes heap dumps generated by a {@link RefWatcher} to verify if suspected leaks are real.
 */
public final class HeapAnalyzer {

  private static final String ANONYMOUS_CLASS_NAME_PATTERN = "^.+\\$\\d+$";
  private static final String TAG = "HeapAnalyzer";

  private final ExcludedRefs baseExcludedRefs;
  private final ExcludedRefs excludedRefs;

  public HeapAnalyzer(ExcludedRefs excludedRefs) {
    this(new ExcludedRefs.Builder().build(), excludedRefs);
  }

  public HeapAnalyzer(ExcludedRefs baseExcludedRefs, ExcludedRefs excludedRefs) {
    this.baseExcludedRefs = baseExcludedRefs;
    this.excludedRefs = excludedRefs;
  }

  /**
   * Searches the heap dump for a {@link KeyedWeakReference} instance with the corresponding key,
   * and then computes the shortest strong reference path from that instance to the GC roots.
   */
  public AnalysisResult checkForLeak(File heapDumpFile, String referenceKey) {
    long analysisStartNanoTime = System.nanoTime();

    if (!heapDumpFile.exists()) {
      Exception exception = new IllegalArgumentException("File does not exist: " + heapDumpFile);
      return failure(exception, since(analysisStartNanoTime));
    }

    ISnapshot snapshot = null;
    try {
      snapshot = openSnapshot(heapDumpFile);

      IObject leakingRef = findLeakingReference(referenceKey, snapshot);

      // False alarm, weak reference was cleared in between key check and heap dump.
      if (leakingRef == null) {
        return noLeak(since(analysisStartNanoTime));
      }

      long retainedHeapSize = snapshot.getRetainedHeapSize(leakingRef.getObjectId());

      String className = leakingRef.getClazz().getName();

      AnalysisResult result =
          findLeakTrace(analysisStartNanoTime, snapshot, leakingRef, className, retainedHeapSize,
              true);

      if (!result.leakFound) {
        result =
            findLeakTrace(analysisStartNanoTime, snapshot, leakingRef, className, retainedHeapSize,
                false);
      }

      return result;
    } catch (Exception e) {
      return failure(e, since(analysisStartNanoTime));
    } finally {
      cleanup(heapDumpFile, snapshot);
    }
  }

  private AnalysisResult findLeakTrace(long analysisStartNanoTime, ISnapshot snapshot,
      IObject leakingRef, String className, long retainedHeapSize, boolean excludingKnownLeaks)
      throws SnapshotException {

    ExcludedRefs excludedRefs = excludingKnownLeaks ? this.excludedRefs : baseExcludedRefs;

    PathsFromGCRootsTree gcRootsTree = shortestPathToGcRoots(snapshot, leakingRef, excludedRefs);

    // False alarm, no strong reference path to GC Roots.
    if (gcRootsTree == null) {
      return noLeak(since(analysisStartNanoTime));
    }

    LeakTrace leakTrace = buildLeakTrace(snapshot, gcRootsTree, excludedRefs);

    return leakDetected(!excludingKnownLeaks, className, leakTrace, retainedHeapSize,
        since(analysisStartNanoTime));
  }

  private ISnapshot openSnapshot(File heapDumpFile) throws SnapshotException {
    SnapshotFactory factory = new SnapshotFactory();
    Map<String, String> args = emptyMap();
    VoidProgressListener listener = new VoidProgressListener();
    return factory.openSnapshot(heapDumpFile, args, listener);
  }

  private IObject findLeakingReference(String key, ISnapshot snapshot) throws SnapshotException {
    Collection<IClass> refClasses =
        snapshot.getClassesByName(KeyedWeakReference.class.getName(), false);

    if (refClasses.size() != 1) {
      throw new IllegalStateException(
          "Expecting one class for " + KeyedWeakReference.class.getName() + " in " + refClasses);
    }

    IClass refClass = refClasses.iterator().next();

    int[] weakRefInstanceIds = refClass.getObjectIds();

    for (int weakRefInstanceId : weakRefInstanceIds) {
      IObject weakRef = snapshot.getObject(weakRefInstanceId);
      String keyCandidate =
          PrettyPrinter.objectAsString((IObject) weakRef.resolveValue("key"), 100);
      if (keyCandidate.equals(key)) {
        return (IObject) weakRef.resolveValue("referent");
      }
    }
    throw new IllegalStateException("Could not find weak reference with key " + key);
  }

  private PathsFromGCRootsTree shortestPathToGcRoots(ISnapshot snapshot, IObject leakingRef,
      ExcludedRefs excludedRefs) throws SnapshotException {
    Map<IClass, Set<String>> excludeMap =
        buildClassExcludeMap(snapshot, excludedRefs.excludeFieldMap);

    IPathsFromGCRootsComputer pathComputer =
        snapshot.getPathsFromGCRoots(leakingRef.getObjectId(), excludeMap);

    return shortestValidPath(snapshot, pathComputer, excludedRefs);
  }

  private Map<IClass, Set<String>> buildClassExcludeMap(ISnapshot snapshot,
      Map<String, Set<String>> excludeMap) throws SnapshotException {
    Map<IClass, Set<String>> classExcludeMap = new LinkedHashMap<>();
    for (Map.Entry<String, Set<String>> entry : excludeMap.entrySet()) {
      Collection<IClass> refClasses = snapshot.getClassesByName(entry.getKey(), false);
      if (refClasses != null && refClasses.size() == 1) {
        IClass refClass = refClasses.iterator().next();
        classExcludeMap.put(refClass, entry.getValue());
      }
    }
    return classExcludeMap;
  }

  private PathsFromGCRootsTree shortestValidPath(ISnapshot snapshot,
      IPathsFromGCRootsComputer pathComputer, ExcludedRefs excludedRefs) throws SnapshotException {

    Map<IClass, Set<String>> excludedStaticFields =
        buildClassExcludeMap(snapshot, excludedRefs.excludeStaticFieldMap);

    int[] shortestPath;

    while ((shortestPath = pathComputer.getNextShortestPath()) != null) {
      PathsFromGCRootsTree tree = pathComputer.getTree(Collections.singletonList(shortestPath));
      if (validPath(snapshot, tree, excludedStaticFields, excludedRefs)) {
        return tree;
      }
    }
    // No more strong reference path.
    return null;
  }

  private boolean validPath(ISnapshot snapshot, PathsFromGCRootsTree tree,
      Map<IClass, Set<String>> excludedStaticFields, ExcludedRefs excludedRefs)
      throws SnapshotException {
    if (excludedStaticFields.isEmpty() && excludedRefs.excludedThreads.isEmpty()) {
      return true;
    }
    // Note: the first child is the leaking object, the last child is the GC root.
    IObject held = null;
    while (tree != null) {
      IObject holder = snapshot.getObject(tree.getOwnId());
      // Static field reference
      if (holder instanceof IClass) {
        IClass childClass = (IClass) holder;
        Set<String> childClassExcludedFields = excludedStaticFields.get(childClass);
        if (childClassExcludedFields != null) {
          NamedReference ref = findHeldInHolder(held, holder, excludedRefs);
          if (ref != null && childClassExcludedFields.contains(ref.getName())) {
            return false;
          }
        }
      } else if (holder.getClazz().doesExtend(Thread.class.getName())) {
        if (excludedRefs.excludedThreads.contains(getThreadName(holder))) {
          return false;
        }
      }
      held = holder;
      int[] branchIds = tree.getObjectIds();
      tree = branchIds.length > 0 ? tree.getBranch(branchIds[0]) : null;
    }
    return true;
  }

  private String getThreadName(IObject thread) throws SnapshotException {
    return PrettyPrinter.objectAsString((IObject) thread.resolveValue("name"), MAX_VALUE);
  }

  private NamedReference findHeldInHolder(IObject held, IObject holder, ExcludedRefs excludedRefs)
      throws SnapshotException {
    if (held == null) {
      return null;
    }
    Set<String> excludedFields = excludedRefs.excludeFieldMap.get(holder.getClazz().getName());
    for (NamedReference holdingRef : holder.getOutboundReferences()) {
      if (holdingRef.getObjectId() == held.getObjectId() && (excludedFields == null
          || !excludedFields.contains(holdingRef.getName()))) {
        return holdingRef;
      }
    }
    return null;
  }

  private LeakTrace buildLeakTrace(ISnapshot snapshot, PathsFromGCRootsTree tree,
      ExcludedRefs excludedRefs) throws SnapshotException {
    List<LeakTraceElement> elements = new ArrayList<>();
    // We iterate from the leak to the GC root
    IObject held = null;
    while (tree != null) {
      IObject holder = snapshot.getObject(tree.getOwnId());
      elements.add(0, buildLeakElement(held, holder, excludedRefs));
      held = holder;
      int[] branchIds = tree.getObjectIds();
      tree = branchIds.length > 0 ? tree.getBranch(branchIds[0]) : null;
    }
    return new LeakTrace(elements);
  }

  private LeakTraceElement buildLeakElement(IObject held, IObject holder, ExcludedRefs excludedRefs)
      throws SnapshotException {
    LeakTraceElement.Type type = null;
    String referenceName = null;
    NamedReference holdingRef = findHeldInHolder(held, holder, excludedRefs);
    if (holdingRef != null) {
      referenceName = holdingRef.getName();
      if (holder instanceof IClass) {
        type = STATIC_FIELD;
      } else if (holdingRef instanceof ThreadToLocalReference) {
        type = LOCAL;
      } else {
        type = INSTANCE_FIELD;
      }
    }

    LeakTraceElement.Holder holderType;
    String className;
    String extra = null;
    List<String> fields = new ArrayList<>();
    if (holder instanceof IClass) {
      IClass clazz = (IClass) holder;
      holderType = CLASS;
      className = clazz.getName();
      for (Field staticField : clazz.getStaticFields()) {
        fields.add("static " + fieldToString(staticField));
      }
    } else if (holder instanceof IArray) {
      holderType = ARRAY;
      IClass clazz = holder.getClazz();
      className = clazz.getName();
      if (holder instanceof IObjectArray) {
        IObjectArray array = (IObjectArray) holder;
        int i = 0;
        ISnapshot snapshot = holder.getSnapshot();
        for (long address : array.getReferenceArray()) {
          if (address == 0) {
            fields.add("[" + i + "] = null");
          } else {
            int objectId = snapshot.mapAddressToId(address);
            IObject object = snapshot.getObject(objectId);
            fields.add("[" + i + "] = " + object);
          }
          i++;
        }
      }
    } else {
      IInstance instance = (IInstance) holder;
      IClass clazz = holder.getClazz();
      for (Field staticField : clazz.getStaticFields()) {
        fields.add("static " + fieldToString(staticField));
      }
      for (Field field : instance.getFields()) {
        fields.add(fieldToString(field));
      }
      className = clazz.getName();
      if (clazz.doesExtend(Thread.class.getName())) {
        holderType = THREAD;
        String threadName = getThreadName(holder);
        extra = "(named '" + threadName + "')";
      } else if (className.matches(ANONYMOUS_CLASS_NAME_PATTERN)) {
        String parentClassName = clazz.getSuperClass().getName();
        if (Object.class.getName().equals(parentClassName)) {
          holderType = OBJECT;
          // This is an anonymous class implementing an interface. The API does not give access
          // to the interfaces implemented by the class. Let's see if it's in the class path and
          // use that instead.
          try {
            Class<?> actualClass = Class.forName(clazz.getName());
            Class<?> implementedInterface = actualClass.getInterfaces()[0];
            extra = "(anonymous class implements " + implementedInterface.getName() + ")";
          } catch (ClassNotFoundException ignored) {
          }
        } else {
          holderType = OBJECT;
          // Makes it easier to figure out which anonymous class we're looking at.
          extra = "(anonymous class extends " + parentClassName + ")";
        }
      } else {
        holderType = OBJECT;
      }
    }
    return new LeakTraceElement(referenceName, type, holderType, className, extra, fields);
  }

  private String fieldToString(Field field) throws SnapshotException {
    Object value = field.getValue();
    if (value instanceof ObjectReference) {
      value = ((ObjectReference) value).getObject();
    }
    return field.getName() + " = " + value;
  }

  private void cleanup(File heapDumpFile, ISnapshot snapshot) {
    if (snapshot != null) {
      snapshot.dispose();
    }
    final String heapDumpFileName = heapDumpFile.getName();
    final String prefix =
        heapDumpFileName.substring(0, heapDumpFile.getName().length() - ".hprof".length());
    File[] toRemove = heapDumpFile.getParentFile().listFiles(new FileFilter() {
      @Override public boolean accept(File file) {
        return !file.isDirectory() && file.getName().startsWith(prefix) && !file.getName()
            .equals(heapDumpFileName);
      }
    });
    if (toRemove != null) {
      for (File file : toRemove) {
        file.delete();
      }
    } else {
      Log.d(TAG, "Could not find HAHA files to cleanup.");
    }
  }

  private long since(long analysisStartNanoTime) {
    return NANOSECONDS.toMillis(System.nanoTime() - analysisStartNanoTime);
  }
}
