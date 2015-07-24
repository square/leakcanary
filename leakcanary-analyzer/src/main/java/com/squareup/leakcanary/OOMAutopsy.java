package com.squareup.leakcanary;

import com.squareup.haha.perflib.ClassObj;
import com.squareup.haha.perflib.HprofParser;
import com.squareup.haha.perflib.Instance;
import com.squareup.haha.perflib.Snapshot;
import com.squareup.haha.perflib.io.HprofBuffer;
import com.squareup.haha.perflib.io.MemoryMappedFileBuffer;
import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.squareup.leakcanary.HahaHelper.buildLeakTrace;
import static com.squareup.leakcanary.HahaHelper.since;
import static java.util.Collections.emptyList;

public class OOMAutopsy {

  public interface ZombieMatcher extends Serializable {
    String rootClassName();

    boolean isZombie(Instance instance);
  }

  private final ExcludedRefs excludedRefs;
  private final List<ZombieMatcher> zombieMatchers;

  public OOMAutopsy(ExcludedRefs excludedRefs, List<ZombieMatcher> zombieMatchers) {
    this.excludedRefs = excludedRefs;
    this.zombieMatchers = zombieMatchers;
  }

  public Autopsy performAutopsy(File heapDumpFile) {
    long analysisStartNanoTime = System.nanoTime();

    if (!heapDumpFile.exists()) {
      Exception exception = new IllegalArgumentException("File does not exist: " + heapDumpFile);
      return Autopsy.failure(exception, since(analysisStartNanoTime));
    }

    try {
      HprofBuffer buffer = new MemoryMappedFileBuffer(heapDumpFile);
      HprofParser parser = new HprofParser(buffer);
      Snapshot snapshot = parser.parse();

      List<Instance> leakingRefs = findLeakingReferences(snapshot);

      List<LeakTrace> leakTraces;
      if (leakingRefs.size() == 0) {
        leakTraces = emptyList();
      } else {
        leakTraces = buildLeakTraces(snapshot, leakingRefs);
      }
      return Autopsy.result(leakTraces, since(analysisStartNanoTime));
    } catch (Throwable e) {
      return Autopsy.failure(e, since(analysisStartNanoTime));
    }
  }

  private List<Instance> findLeakingReferences(Snapshot snapshot) {
    // Those are objects in a destroyed state that were still around. We need to figure out whether
    // the GC could have collected them or not.
    List<Instance> zombies = new ArrayList<>();
    for (ZombieMatcher matcher : zombieMatchers) {
      String className = matcher.rootClassName();
      ClassObj rootClass = snapshot.findClass(className);
      List<ClassObj> classHierarchy = rootClass.getDescendantClasses();
      for (ClassObj clazz : classHierarchy) {
        List<Instance> instances = clazz.getInstancesList();
        for (Instance maybeZombie : instances) {
          if (matcher.isZombie(maybeZombie)) {
            zombies.add(maybeZombie);
          }
        }
      }
    }
    return zombies;
  }

  private List<LeakTrace> buildLeakTraces(Snapshot snapshot, List<Instance> leakingRefs) {
    ShortestPathFinder pathFinder = new ShortestPathFinder(excludedRefs);
    ShortestPathFinder.Result result = pathFinder.findPath(snapshot, leakingRefs);

    if (result.leakingNodes.size() == 0) {
      return Collections.emptyList();
    }

    Set<LeakNode> dominatedNodes = new LinkedHashSet<>();
    for (LeakNode maybeDominator : result.leakingNodes) {
      dominated:
      for (LeakNode maybeDominated : result.leakingNodes) {
        if (maybeDominator == maybeDominated) {
          continue;
        }
        LeakNode ancestor = maybeDominated.parent;
        while (ancestor != null) {
          if (ancestor.instance == maybeDominator.instance) {
            dominatedNodes.add(maybeDominated);
            continue dominated;
          }
          ancestor = ancestor.parent;
        }
      }
    }

    result.leakingNodes.removeAll(dominatedNodes);

    List<LeakTrace> leakTraces = new ArrayList<>();
    for (LeakNode leakNode : result.leakingNodes) {
      leakTraces.add(buildLeakTrace(snapshot, leakNode));
    }

    Collections.sort(leakTraces, new Comparator<LeakTrace>() {
      @Override public int compare(LeakTrace lhs, LeakTrace rhs) {
        return Long.valueOf(rhs.retainedSize).compareTo(lhs.retainedSize);
      }
    });

    return leakTraces;
  }
}
