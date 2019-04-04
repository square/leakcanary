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

import androidx.annotation.NonNull;
import com.android.tools.perflib.captures.DataBuffer;
import com.android.tools.perflib.captures.MemoryMappedFileBuffer;
import com.squareup.haha.perflib.ArrayInstance;
import com.squareup.haha.perflib.ClassInstance;
import com.squareup.haha.perflib.ClassObj;
import com.squareup.haha.perflib.Field;
import com.squareup.haha.perflib.Instance;
import com.squareup.haha.perflib.RootObj;
import com.squareup.haha.perflib.Snapshot;
import com.squareup.haha.perflib.Type;
import gnu.trove.THashMap;
import gnu.trove.TObjectProcedure;
import java.io.File;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import leaksentry.KeyedWeakReference;
import org.jetbrains.annotations.TestOnly;

import static com.squareup.leakcanary.AnalyzerProgressListener.Step.BUILDING_LEAK_TRACE;
import static com.squareup.leakcanary.AnalyzerProgressListener.Step.COMPUTING_DOMINATORS;
import static com.squareup.leakcanary.AnalyzerProgressListener.Step.DEDUPLICATING_GC_ROOTS;
import static com.squareup.leakcanary.AnalyzerProgressListener.Step.FINDING_LEAKING_REF;
import static com.squareup.leakcanary.AnalyzerProgressListener.Step.FINDING_LEAKING_REFS;
import static com.squareup.leakcanary.AnalyzerProgressListener.Step.FINDING_SHORTEST_PATH;
import static com.squareup.leakcanary.AnalyzerProgressListener.Step.PARSING_HEAP_DUMP;
import static com.squareup.leakcanary.AnalyzerProgressListener.Step.READING_HEAP_DUMP_FILE;
import static com.squareup.leakcanary.HahaHelper.asString;
import static com.squareup.leakcanary.HahaHelper.asStringArray;
import static com.squareup.leakcanary.HahaHelper.classInstanceValues;
import static com.squareup.leakcanary.HahaHelper.extendsThread;
import static com.squareup.leakcanary.HahaHelper.fieldValue;
import static com.squareup.leakcanary.HahaHelper.staticFieldValue;
import static com.squareup.leakcanary.HahaHelper.threadName;
import static com.squareup.leakcanary.HahaHelper.valueAsString;
import static com.squareup.leakcanary.LeakTraceElement.Holder.ARRAY;
import static com.squareup.leakcanary.LeakTraceElement.Holder.CLASS;
import static com.squareup.leakcanary.LeakTraceElement.Holder.OBJECT;
import static com.squareup.leakcanary.LeakTraceElement.Holder.THREAD;
import static com.squareup.leakcanary.LeakTraceElement.Type.ARRAY_ENTRY;
import static com.squareup.leakcanary.LeakTraceElement.Type.INSTANCE_FIELD;
import static com.squareup.leakcanary.LeakTraceElement.Type.STATIC_FIELD;
import static com.squareup.leakcanary.Reachability.Status.REACHABLE;
import static com.squareup.leakcanary.Reachability.Status.UNREACHABLE;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * Analyzes heap dumps to look for leaks.
 */
public final class HeapAnalyzer {

  private static final String ANONYMOUS_CLASS_NAME_PATTERN = "^.+\\$\\d+$";

  private final ExcludedRefs excludedRefs;
  private final AnalyzerProgressListener listener;
  private final List<Reachability.Inspector> reachabilityInspectors;

  /**
   * @deprecated Use {@link #HeapAnalyzer(ExcludedRefs, AnalyzerProgressListener, List)}.
   */
  @Deprecated
  public HeapAnalyzer(@NonNull ExcludedRefs excludedRefs) {
    this(excludedRefs, AnalyzerProgressListener.Companion.getNONE(),
        Collections.<Class<? extends Reachability.Inspector>>emptyList());
  }

  public HeapAnalyzer(@NonNull ExcludedRefs excludedRefs,
      @NonNull AnalyzerProgressListener listener,
      @NonNull List<Class<? extends Reachability.Inspector>> reachabilityInspectorClasses) {
    this.excludedRefs = excludedRefs;
    this.listener = listener;

    this.reachabilityInspectors = new ArrayList<>();
    for (Class<? extends Reachability.Inspector> reachabilityInspectorClass
        : reachabilityInspectorClasses) {
      try {
        Constructor<? extends Reachability.Inspector> defaultConstructor =
            reachabilityInspectorClass.getDeclaredConstructor();
        reachabilityInspectors.add(defaultConstructor.newInstance());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  /**
   * Searches the heap dump for a {@link KeyedWeakReference} instance with the corresponding key,
   * and then computes the shortest strong reference path from that instance to the GC roots.
   *
   * @deprecated Use {@link #checkForLeaks(File, boolean)} instead. We're keeping this only because
   * our tests currently run with older heapdumps.
   */
  @Deprecated @TestOnly
  public @NonNull AnalysisResult checkForLeak(@NonNull File heapDumpFile,
      @NonNull String referenceKey,
      boolean computeRetainedSize) {
    long analysisStartNanoTime = System.nanoTime();

    if (!heapDumpFile.exists()) {
      Exception exception = new IllegalArgumentException("File does not exist: " + heapDumpFile);
      return AnalysisResult.Companion.failure(exception, since(analysisStartNanoTime));
    }

    try {
      listener.onProgressUpdate(READING_HEAP_DUMP_FILE);
      DataBuffer buffer = new MemoryMappedFileBuffer(heapDumpFile);
      listener.onProgressUpdate(PARSING_HEAP_DUMP);
      Snapshot snapshot = Snapshot.createSnapshot(buffer);
      listener.onProgressUpdate(DEDUPLICATING_GC_ROOTS);
      deduplicateGcRoots(snapshot);
      listener.onProgressUpdate(FINDING_LEAKING_REF);
      Instance leakingRef = findLeakingReference(referenceKey, snapshot);

      // False alarm, weak reference was cleared in between key check and heap dump.
      if (leakingRef == null) {
        return AnalysisResult.Companion.noLeak("UnknownNoKeyedWeakReference",
            since(analysisStartNanoTime));
      }
      return findLeakTrace(referenceKey, "NAME_NOT_SUPPORTED", analysisStartNanoTime, snapshot,
          leakingRef, computeRetainedSize, 0);
    } catch (Throwable e) {
      return AnalysisResult.Companion.failure(e, since(analysisStartNanoTime));
    }
  }

  /**
   * Searches the heap dump for a {@link KeyedWeakReference} instance with the corresponding key,
   * and then computes the shortest strong reference path from that instance to the GC roots.
   */
  public @NonNull List<AnalysisResult> checkForLeaks(@NonNull File heapDumpFile,
      boolean computeRetainedSize) {
    long analysisStartNanoTime = System.nanoTime();

    if (!heapDumpFile.exists()) {
      Exception exception = new IllegalArgumentException("File does not exist: " + heapDumpFile);
      return singletonList(
          AnalysisResult.Companion.failure(exception, since(analysisStartNanoTime)));
    }

    try {
      listener.onProgressUpdate(READING_HEAP_DUMP_FILE);
      DataBuffer buffer = new MemoryMappedFileBuffer(heapDumpFile);
      listener.onProgressUpdate(PARSING_HEAP_DUMP);
      Snapshot snapshot = Snapshot.createSnapshot(buffer);
      listener.onProgressUpdate(DEDUPLICATING_GC_ROOTS);
      deduplicateGcRoots(snapshot);
      listener.onProgressUpdate(FINDING_LEAKING_REFS);

      ClassObj heapDumpMemoryStoreClass = snapshot.findClass(HeapDumpMemoryStore.class.getName());
      ArrayInstance retainedKeysArray =
         staticFieldValue(heapDumpMemoryStoreClass, "retainedKeysForHeapDump");
      List<String> retainedKeys = asStringArray(retainedKeysArray);
      long heapDumpUptimeMillis =
          staticFieldValue(heapDumpMemoryStoreClass, "heapDumpUptimeMillis");

      // False alarm, weak reference was cleared in between key check and heap dump.
      if (retainedKeys.size() == 0) {
        IllegalStateException exception =
            new IllegalStateException("No retained keys found in heap dump");
        return singletonList(
            AnalysisResult.Companion.failure(exception, since(analysisStartNanoTime)));
      }

      ClassObj refClass = snapshot.findClass(KeyedWeakReference.class.getName());
      if (refClass == null) {
        throw new IllegalStateException(
            "Could not find the "
                + KeyedWeakReference.class.getName()
                + " class in the heap dump.");
      }
      List<Instance> leakingWeakRefs = new ArrayList<>();
      List<String> keysFound = new ArrayList<>();
      for (Instance instance : refClass.getInstancesList()) {
        List<ClassInstance.FieldValue> values = classInstanceValues(instance);
        Object keyFieldValue = fieldValue(values, "key");
        if (keyFieldValue == null) {
          keysFound.add(null);
          continue;
        }
        String keyCandidate = asString(keyFieldValue);
        boolean wasRetained = retainedKeys.remove(keyCandidate);
        if (wasRetained) {
          leakingWeakRefs.add(instance);
        }
        keysFound.add(keyCandidate);
      }
      if (retainedKeys.size() > 0) {
        throw new IllegalStateException(
            "Could not find weak references with keys " + retainedKeys + " in " + keysFound);
      }

      List<AnalysisResult> analysisResults = new ArrayList<>();
      for (Instance leakingWeakRef : leakingWeakRefs) {
        List<ClassInstance.FieldValue> values = classInstanceValues(leakingWeakRef);
        Instance referent = fieldValue(values, "referent");
        String key = asString(
            fieldValue(values, "key"));
        String name = asString(fieldValue(values, "name"));
        long watchUptimeMillis = fieldValue(values, "watchUptimeMillis");
        long watchDurationMillis = heapDumpUptimeMillis - watchUptimeMillis;
        analysisResults.add(
            findLeakTrace(key, name, analysisStartNanoTime, snapshot, referent,
                computeRetainedSize, watchDurationMillis));
      }
      return analysisResults;
    } catch (Throwable e) {
      return singletonList(AnalysisResult.Companion.failure(e, since(analysisStartNanoTime)));
    }
  }

  /**
   * Pruning duplicates reduces memory pressure from hprof bloat added in Marshmallow.
   */
  void deduplicateGcRoots(Snapshot snapshot) {
    // THashMap has a smaller memory footprint than HashMap.
    final THashMap<String, RootObj> uniqueRootMap = new THashMap<>();

    final Collection<RootObj> gcRoots = snapshot.getGCRoots();
    for (RootObj root : gcRoots) {
      String key = generateRootKey(root);
      if (!uniqueRootMap.containsKey(key)) {
        uniqueRootMap.put(key, root);
      }
    }

    // Repopulate snapshot with unique GC roots.
    gcRoots.clear();
    uniqueRootMap.forEach(new TObjectProcedure<String>() {
      @Override public boolean execute(String key) {
        return gcRoots.add(uniqueRootMap.get(key));
      }
    });
  }

  private String generateRootKey(RootObj root) {
    return String.format("%s@0x%08x", root.getRootType().getName(), root.getId());
  }

  private Instance findLeakingReference(String key, Snapshot snapshot) {
    ClassObj refClass = snapshot.findClass(KeyedWeakReference.class.getName());
    if (refClass == null) {
      throw new IllegalStateException(
          "Could not find the " + KeyedWeakReference.class.getName() + " class in the heap dump.");
    }
    List<String> keysFound = new ArrayList<>();
    for (Instance instance : refClass.getInstancesList()) {
      List<ClassInstance.FieldValue> values = classInstanceValues(instance);
      Object keyFieldValue = fieldValue(values, "key");
      if (keyFieldValue == null) {
        keysFound.add(null);
        continue;
      }
      String keyCandidate = asString(keyFieldValue);
      if (keyCandidate.equals(key)) {
        return fieldValue(values, "referent");
      }
      keysFound.add(keyCandidate);
    }
    throw new IllegalStateException(
        "Could not find weak reference with key " + key + " in " + keysFound);
  }

  private AnalysisResult findLeakTrace(String referenceKey,
      String referenceName, long analysisStartNanoTime, Snapshot snapshot,
      Instance leakingRef, boolean computeRetainedSize, long watchDurationMs) {

    listener.onProgressUpdate(FINDING_SHORTEST_PATH);
    ShortestPathFinder pathFinder = new ShortestPathFinder(excludedRefs);
    ShortestPathFinder.Result result = pathFinder.findPath(snapshot, leakingRef);

    String className = leakingRef.getClassObj().getClassName();

    // False alarm, no strong reference path to GC Roots.
    if (result.leakingNode == null) {
      return AnalysisResult.Companion.noLeak(className, since(analysisStartNanoTime));
    }

    listener.onProgressUpdate(BUILDING_LEAK_TRACE);
    LeakTrace leakTrace = buildLeakTrace(result.leakingNode);

    long retainedSize;
    if (computeRetainedSize) {

      listener.onProgressUpdate(COMPUTING_DOMINATORS);
      // Side effect: computes retained size.
      snapshot.computeDominators();

      Instance leakingInstance = result.leakingNode.getInstance();

      retainedSize = leakingInstance.getTotalRetainedSize();
    } else {
      retainedSize = AnalysisResult.Companion.getRETAINED_HEAP_SKIPPED();
    }

    return AnalysisResult.Companion.leakDetected(referenceKey, referenceName,
        result.excludingKnownLeaks, className, leakTrace,
        retainedSize,
        since(analysisStartNanoTime), watchDurationMs);
  }

  private LeakTrace buildLeakTrace(LeakNode leakingNode) {
    List<LeakTraceElement> elements = new ArrayList<>();
    // We iterate from the leak to the GC root
    LeakNode node = new LeakNode(null, null, leakingNode, null);
    while (node != null) {
      LeakTraceElement element = buildLeakElement(node);
      if (element != null) {
        elements.add(0, element);
      }
      node = node.getParent();
    }

    List<Reachability> expectedReachability =
        computeExpectedReachability(elements);

    return new LeakTrace(elements, expectedReachability);
  }

  private List<Reachability> computeExpectedReachability(
      List<LeakTraceElement> elements) {
    int lastReachableElementIndex = 0;
    int lastElementIndex = elements.size() - 1;
    int firstUnreachableElementIndex = lastElementIndex;

    List<Reachability> expectedReachability = new ArrayList<>();

    int index = 0;
    for (LeakTraceElement element : elements) {
      Reachability reachability = inspectElementReachability(element);
      expectedReachability.add(reachability);
      if (reachability.status == REACHABLE) {
        lastReachableElementIndex = index;
      } else if (firstUnreachableElementIndex == lastElementIndex
          && reachability.status == UNREACHABLE) {
        firstUnreachableElementIndex = index;
      }
      index++;
    }

    if (expectedReachability.get(0).status == Reachability.Status.UNKNOWN) {
      expectedReachability.set(0, Reachability.reachable("it's a GC root"));
    }

    if (expectedReachability.get(lastElementIndex).status == Reachability.Status.UNKNOWN) {
      expectedReachability.set(lastElementIndex,
          Reachability.unreachable("it's the leaking instance"));
    }

    // First and last are always known.
    for (int i = 1; i < lastElementIndex; i++) {
      Reachability reachability = expectedReachability.get(i);
      if (reachability.status == Reachability.Status.UNKNOWN) {
        if (i <= lastReachableElementIndex) {
          String lastReachableName = elements.get(lastReachableElementIndex).getSimpleClassName();
          expectedReachability.set(i,
              Reachability.reachable(lastReachableName + " is not leaking"));
        } else if (i >= firstUnreachableElementIndex) {
          String firstUnreachableName =
              elements.get(firstUnreachableElementIndex).getSimpleClassName();
          expectedReachability.set(i,
              Reachability.unreachable(firstUnreachableName + " is leaking"));
        }
      }
    }
    return expectedReachability;
  }

  private Reachability inspectElementReachability(LeakTraceElement element) {
    for (Reachability.Inspector reachabilityInspector : reachabilityInspectors) {
      Reachability reachability = reachabilityInspector.expectedReachability(element);
      if (reachability.status != Reachability.Status.UNKNOWN) {
        return reachability;
      }
    }
    return Reachability.unknown();
  }

  private LeakTraceElement buildLeakElement(LeakNode node) {
    if (node.getParent() == null) {
      // Ignore any root node.
      return null;
    }
    Instance holder = node.getParent().getInstance();

    if (holder instanceof RootObj) {
      return null;
    }
    LeakTraceElement.Holder holderType;
    String className;
    String extra = null;
    List<LeakReference> leakReferences = describeFields(holder);

    className = getClassName(holder);

    List<String> classHierarchy = new ArrayList<>();
    classHierarchy.add(className);
    String rootClassName = Object.class.getName();
    if (holder instanceof ClassInstance) {
      ClassObj classObj = holder.getClassObj();
      while (!(classObj = classObj.getSuperClassObj()).getClassName().equals(rootClassName)) {
        classHierarchy.add(classObj.getClassName());
      }
    }

    if (holder instanceof ClassObj) {
      holderType = CLASS;
    } else if (holder instanceof ArrayInstance) {
      holderType = ARRAY;
    } else {
      ClassObj classObj = holder.getClassObj();
      if (extendsThread(classObj)) {
        holderType = THREAD;
        String threadName = threadName(holder);
        extra = "(named '" + threadName + "')";
      } else if (className.matches(ANONYMOUS_CLASS_NAME_PATTERN)) {
        String parentClassName = classObj.getSuperClassObj().getClassName();
        if (rootClassName.equals(parentClassName)) {
          holderType = OBJECT;
          try {
            // This is an anonymous class implementing an interface. The API does not give access
            // to the interfaces implemented by the class. We check if it's in the class path and
            // use that instead.
            Class<?> actualClass = Class.forName(classObj.getClassName());
            Class<?>[] interfaces = actualClass.getInterfaces();
            if (interfaces.length > 0) {
              Class<?> implementedInterface = interfaces[0];
              extra = "(anonymous implementation of " + implementedInterface.getName() + ")";
            } else {
              extra = "(anonymous subclass of java.lang.Object)";
            }
          } catch (ClassNotFoundException ignored) {
          }
        } else {
          holderType = OBJECT;
          // Makes it easier to figure out which anonymous class we're looking at.
          extra = "(anonymous subclass of " + parentClassName + ")";
        }
      } else {
        holderType = OBJECT;
      }
    }
    return new LeakTraceElement(node.getLeakReference(), holderType, classHierarchy, extra,
        node.getExclusion(), leakReferences);
  }

  private List<LeakReference> describeFields(Instance instance) {
    List<LeakReference> leakReferences = new ArrayList<>();
    if (instance instanceof ClassObj) {
      ClassObj classObj = (ClassObj) instance;
      for (Map.Entry<Field, Object> entry : classObj.getStaticFieldValues().entrySet()) {
        String name = entry.getKey().getName();
        String stringValue = valueAsString(entry.getValue());
        leakReferences.add(new LeakReference(STATIC_FIELD, name, stringValue));
      }
    } else if (instance instanceof ArrayInstance) {
      ArrayInstance arrayInstance = (ArrayInstance) instance;
      if (arrayInstance.getArrayType() == Type.OBJECT) {
        Object[] values = arrayInstance.getValues();
        for (int i = 0; i < values.length; i++) {
          String name = Integer.toString(i);
          String stringValue = valueAsString(values[i]);
          leakReferences.add(new LeakReference(ARRAY_ENTRY, name, stringValue));
        }
      }
    } else {
      ClassObj classObj = instance.getClassObj();
      for (Map.Entry<Field, Object> entry : classObj.getStaticFieldValues().entrySet()) {
        String name = entry.getKey().getName();
        String stringValue = valueAsString(entry.getValue());
        leakReferences.add(new LeakReference(STATIC_FIELD, name, stringValue));
      }
      ClassInstance classInstance = (ClassInstance) instance;
      for (ClassInstance.FieldValue field : classInstance.getValues()) {
        String name = field.getField().getName();
        String stringValue = valueAsString(field.getValue());
        leakReferences.add(new LeakReference(INSTANCE_FIELD, name, stringValue));
      }
    }
    return leakReferences;
  }

  private String getClassName(Instance instance) {
    String className;
    if (instance instanceof ClassObj) {
      ClassObj classObj = (ClassObj) instance;
      className = classObj.getClassName();
    } else if (instance instanceof ArrayInstance) {
      ArrayInstance arrayInstance = (ArrayInstance) instance;
      className = arrayInstance.getClassObj().getClassName();
    } else {
      ClassObj classObj = instance.getClassObj();
      className = classObj.getClassName();
    }
    return className;
  }

  private long since(long analysisStartNanoTime) {
    return NANOSECONDS.toMillis(System.nanoTime() - analysisStartNanoTime);
  }
}
