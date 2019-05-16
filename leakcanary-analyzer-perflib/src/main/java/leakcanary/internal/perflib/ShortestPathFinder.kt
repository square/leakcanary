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
package leakcanary.internal.perflib

import com.squareup.haha.perflib.ArrayInstance
import com.squareup.haha.perflib.ClassInstance
import com.squareup.haha.perflib.ClassObj
import com.squareup.haha.perflib.Instance
import com.squareup.haha.perflib.RootObj
import com.squareup.haha.perflib.RootType
import com.squareup.haha.perflib.Snapshot
import com.squareup.haha.perflib.Type
import com.squareup.haha.perflib.allocatingThread
import leakcanary.PerflibExcludedRefs
import leakcanary.PerflibExclusion
import leakcanary.LeakReference
import leakcanary.internal.perflib.HahaHelper.isPrimitiveOrWrapperArray
import leakcanary.internal.perflib.HahaHelper.isPrimitiveWrapper
import leakcanary.LeakTraceElement.Type.ARRAY_ENTRY
import leakcanary.LeakTraceElement.Type.INSTANCE_FIELD
import leakcanary.LeakTraceElement.Type.LOCAL
import leakcanary.LeakTraceElement.Type.STATIC_FIELD
import java.util.ArrayDeque
import java.util.Deque
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/**
 * Not thread safe.
 *
 * Finds the shortest path from a leaking reference to a gc root, ignoring excluded
 * refs first and then including the ones that are not "always ignorable" as needed if no path is
 * found.
 */
internal class ShortestPathFinder(
  private val excludedRefs: PerflibExcludedRefs,
  private val ignoreStrings: Boolean
) {
  private val toVisitQueue: Deque<LeakNode>
  private val toVisitIfNoPathQueue: Deque<LeakNode>
  private val toVisitSet: LinkedHashSet<Instance>
  private val toVisitIfNoPathSet: LinkedHashSet<Instance>
  private val visitedSet: LinkedHashSet<Instance>

  init {
    toVisitQueue = ArrayDeque()
    toVisitIfNoPathQueue = ArrayDeque()
    toVisitSet = LinkedHashSet()
    toVisitIfNoPathSet = LinkedHashSet()
    visitedSet = LinkedHashSet()
  }

  @Deprecated("Kept for tests which still rely on findPath()")
  internal class OldResult(
    val leakingNode: LeakNode?,
    val excludingKnownLeaks: Boolean
  )

  internal class Result(
    val leakingNode: LeakNode,
    val excludingKnownLeaks: Boolean,
    val weakReference: HasReferent
  )

  fun findPaths(
    snapshot: Snapshot,
    leakingWeakRefs: List<HasReferent>
  ): List<Result> {
    clearState()

    val referentMap = leakingWeakRefs.associateBy { it.referent }

    enqueueGcRoots(snapshot)

    var excludingKnownLeaks = false
    val results = mutableListOf<Result>()
    while (!toVisitQueue.isEmpty() || !toVisitIfNoPathQueue.isEmpty()) {
      val node: LeakNode
      if (!toVisitQueue.isEmpty()) {
        node = toVisitQueue.poll()
      } else {
        node = toVisitIfNoPathQueue.poll()
        if (node.exclusion == null) {
          throw IllegalStateException("Expected node to have an exclusion $node")
        }
        excludingKnownLeaks = true
      }

      if (checkSeen(node)) {
        continue
      }

      val weakReference = referentMap[node.instance]
      if (weakReference != null) {
        results.add(
            Result(
                node, excludingKnownLeaks, weakReference
            )
        )
        // Found all refs, stop searching.
        if (results.size == leakingWeakRefs.size) {
          break
        }
      }
      when (node.instance) {
        is RootObj -> visitRootObj(node)
        is ClassObj -> visitClassObj(node)
        is ClassInstance -> visitClassInstance(node)
        is ArrayInstance -> visitArrayInstance(node)
        else -> throw IllegalStateException("Unexpected type for ${node.instance}")
      }
    }
    return results
  }

  fun findPath(
    snapshot: Snapshot,
    leakingRef: Instance
  ): OldResult {
    clearState()

    enqueueGcRoots(snapshot)

    var excludingKnownLeaks = false
    var leakingNode: LeakNode? = null
    while (!toVisitQueue.isEmpty() || !toVisitIfNoPathQueue.isEmpty()) {
      val node: LeakNode
      if (!toVisitQueue.isEmpty()) {
        node = toVisitQueue.poll()
      } else {
        node = toVisitIfNoPathQueue.poll()
        if (node.exclusion == null) {
          throw IllegalStateException("Expected node to have an exclusion $node")
        }
        excludingKnownLeaks = true
      }

      // Termination
      if (node.instance === leakingRef) {
        leakingNode = node
        break
      }

      if (checkSeen(node)) {
        continue
      }

      when {
        node.instance is RootObj -> visitRootObj(node)
        node.instance is ClassObj -> visitClassObj(node)
        node.instance is ClassInstance -> visitClassInstance(node)
        node.instance is ArrayInstance -> visitArrayInstance(node)
        else -> throw IllegalStateException("Unexpected type for " + node.instance)
      }
    }
    return OldResult(
        leakingNode, excludingKnownLeaks
    )
  }

  private fun clearState() {
    toVisitQueue.clear()
    toVisitIfNoPathQueue.clear()
    toVisitSet.clear()
    toVisitIfNoPathSet.clear()
    visitedSet.clear()
  }

  private fun enqueueGcRoots(snapshot: Snapshot) {
    val gcRoots = snapshot.gcRoots as MutableList<RootObj>
    // Sorting GC roots to get stable shortest path
    gcRoots.sortWith(compareBy({ it.rootType.type }, {
      val referredInstance = it.referredInstance
      if (referredInstance == null) {
        "null"
      } else {
        when (referredInstance) {
          is ClassObj -> referredInstance.className
          is ClassInstance -> referredInstance.classObj.className
          is ArrayInstance -> referredInstance.classObj.className
          else -> throw IllegalStateException("Unexpected type for $referredInstance")
        }
      }
    }))
    for (rootObj in gcRoots) {
      when (rootObj.rootType) {
        RootType.JAVA_LOCAL -> {
          val thread = rootObj.allocatingThread()
          val threadName = HahaHelper.threadName(thread)
          val params = excludedRefs.threadNames[threadName]
          if (params == null || !params.alwaysExclude) {
            enqueue(params, null, rootObj, null)
          }
        }
        // Added at https://android.googlesource.com/platform/tools/base/+/c0f0d528c155cab32e372dac77370569a386245c
        RootType.THREAD_OBJECT, RootType.INTERNED_STRING, RootType.DEBUGGER, RootType.INVALID_TYPE,
          // An object that is unreachable from any other root, but not a root itself.
        RootType.UNREACHABLE, RootType.UNKNOWN,
          // An object that is in a queue, waiting for a finalizer to run.
        RootType.FINALIZING -> {
        }
        RootType.SYSTEM_CLASS, RootType.VM_INTERNAL,
          // A local variable in native code.
        RootType.NATIVE_LOCAL,
          // A global variable in native code.
        RootType.NATIVE_STATIC,
          // An object that was referenced from an active thread block.
        RootType.THREAD_BLOCK,
          // Everything that called the wait() or notify() methods, or that is synchronized.
        RootType.BUSY_MONITOR, RootType.NATIVE_MONITOR, RootType.REFERENCE_CLEANUP,
          // Input or output parameters in native code.
        RootType.NATIVE_STACK, RootType.JAVA_STATIC -> enqueue(null, null, rootObj, null)
        else -> throw UnsupportedOperationException("Unknown root type:" + rootObj.rootType)
      }
    }
  }

  private fun checkSeen(node: LeakNode): Boolean {
    return !visitedSet.add(node.instance)
  }

  private fun visitRootObj(node: LeakNode) {
    val rootObj = node.instance as RootObj
    val child = rootObj.referredInstance

    if (rootObj.rootType == RootType.JAVA_LOCAL) {
      val holder = rootObj.allocatingThread()
      // We switch the parent node with the thread instance that holds
      // the local reference.
      var exclusion: PerflibExclusion? = null
      if (node.exclusion != null) {
        exclusion = node.exclusion
      }
      val parent = LeakNode(null, holder, null, null)
      enqueue(exclusion, parent, child, LeakReference(LOCAL, ""))
    } else {
      enqueue(null, node, child, null)
    }
  }

  private fun visitClassObj(node: LeakNode) {
    val classObj = node.instance as ClassObj?
    val ignoredStaticFields = excludedRefs.staticFieldNameByClassName[classObj!!.className]
    for ((field, value) in classObj.staticFieldValues) {
      if (field.type != Type.OBJECT) {
        continue
      }
      val fieldName = field.name
      if (fieldName == "\$staticOverhead") {
        continue
      }
      val child = value as Instance?
      var visit = true
      val leakReference = LeakReference(STATIC_FIELD, fieldName)
      if (ignoredStaticFields != null) {
        val params = ignoredStaticFields[fieldName]
        if (params != null) {
          visit = false
          if (!params.alwaysExclude) {
            enqueue(params, node, child, leakReference)
          }
        }
      }
      if (visit) {
        enqueue(null, node, child, leakReference)
      }
    }
  }

  private fun visitClassInstance(node: LeakNode) {
    val classInstance = node.instance as ClassInstance?
    val ignoredFields = LinkedHashMap<String, PerflibExclusion>()
    var superClassObj: ClassObj? = classInstance!!.classObj
    var classExclusion: PerflibExclusion? = null
    while (superClassObj != null) {
      val params = excludedRefs.classNames[superClassObj.className]
      if (params != null) {
        // true overrides null or false.
        if (classExclusion == null || !classExclusion.alwaysExclude) {
          classExclusion = params
        }
      }
      val classIgnoredFields = excludedRefs.fieldNameByClassName[superClassObj.className]
      if (classIgnoredFields != null) {
        ignoredFields.putAll(classIgnoredFields)
      }
      superClassObj = superClassObj.superClassObj
    }

    if (classExclusion != null && classExclusion.alwaysExclude) {
      return
    }

    val values = classInstance.values
    values.sortBy { it.field.name }
    for (fieldValue in values) {
      var fieldExclusion = classExclusion
      val field = fieldValue.field
      if (field.type != Type.OBJECT) {
        continue
      }
      val child = fieldValue.value as Instance?
      val fieldName = field.name
      val params = ignoredFields[fieldName]
      // If we found a field exclusion and it's stronger than a class exclusion
      if (params != null && (fieldExclusion == null || params.alwaysExclude && !fieldExclusion.alwaysExclude)) {
        fieldExclusion = params
      }
      enqueue(
          fieldExclusion, node, child,
          LeakReference(INSTANCE_FIELD, fieldName)
      )
    }
  }

  private fun visitArrayInstance(node: LeakNode) {
    val arrayInstance = node.instance as ArrayInstance
    if (arrayInstance.arrayType == Type.OBJECT) {
      val values = arrayInstance.values
      for (i in values.indices) {
        val child = values[i] as Instance?
        val name = Integer.toString(i)
        enqueue(null, node, child, LeakReference(ARRAY_ENTRY, name))
      }
    }
  }

  private fun enqueue(
    exclusion: PerflibExclusion?,
    parent: LeakNode?,
    child: Instance?,
    leakReference: LeakReference?
  ) {
    if (child == null) {
      return
    }
    if (isPrimitiveOrWrapperArray(child) || isPrimitiveWrapper(child)) {
      return
    }
    if (ignoreStrings && isString(child)) {
      return
    }
    // Whether we want to visit now or later, we should skip if this is already to visit.
    if (toVisitSet.contains(child)) {
      return
    }
    val visitNow = exclusion == null
    if (!visitNow && toVisitIfNoPathSet.contains(child)) {
      return
    }
    if (visitedSet.contains(child)) {
      return
    }
    val childNode = LeakNode(exclusion, child, parent, leakReference)
    if (visitNow) {
      toVisitSet.add(child)
      toVisitQueue.add(childNode)
    } else {
      toVisitIfNoPathSet.add(child)
      toVisitIfNoPathQueue.add(childNode)
    }
  }

  private fun isString(instance: Instance): Boolean {
    return instance.classObj != null && instance.classObj
        .className == String::class.java.name
  }
}
