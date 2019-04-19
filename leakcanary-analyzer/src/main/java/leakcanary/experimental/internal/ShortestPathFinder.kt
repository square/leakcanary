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
package leakcanary.experimental.internal

import leakcanary.ExcludedRefs
import leakcanary.Exclusion
import leakcanary.HeapValue
import leakcanary.HeapValue.ObjectReference
import leakcanary.HprofParser
import leakcanary.LeakReference
import leakcanary.LeakTraceElement.Type.ARRAY_ENTRY
import leakcanary.LeakTraceElement.Type.INSTANCE_FIELD
import leakcanary.LeakTraceElement.Type.STATIC_FIELD
import leakcanary.ObjectIdMetadata.PRIMITIVE_ARRAY
import leakcanary.ObjectIdMetadata.PRIMITIVE_WRAPPER
import leakcanary.ObjectIdMetadata.PRIMITIVE_WRAPPER_ARRAY
import leakcanary.ObjectIdMetadata.SHALLOW_INSTANCE
import leakcanary.ObjectIdMetadata.STRING
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord
import leakcanary.experimental.internal.LeakNode.ChildNode
import leakcanary.experimental.internal.LeakNode.RootNode
import java.util.ArrayDeque
import java.util.Deque
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/**
 * Not thread safe.
 *
 * Finds the shortest path from leaking references to a gc root, ignoring excluded
 * refs first and then including the ones that are not "always ignorable" as needed if no path is
 * found.
 *
 * Skips enqueuing strings as an optimization, so if the leaking reference is a string then it will
 * never be found.
 */
internal class ShortestPathFinder(
  private val excludedRefs: ExcludedRefs
) {
  /**
   * TODO If this queue grows large we can optimize it by replacing LeakNode with just (long, long)
   * and rebuild exclusion and leak reference after the analysis
   */
  private val toVisitQueue: Deque<LeakNode>
  private val toVisitIfNoPathQueue: Deque<LeakNode>
  private val toVisitSet: LinkedHashSet<Long>
  private val toVisitIfNoPathSet: LinkedHashSet<Long>
  private val visitedSet: LinkedHashSet<Long>

  init {
    toVisitQueue = ArrayDeque()
    toVisitIfNoPathQueue = ArrayDeque()
    toVisitSet = LinkedHashSet()
    toVisitIfNoPathSet = LinkedHashSet()
    visitedSet = LinkedHashSet()
  }

  internal class Result(
    val leakingNode: LeakNode,
    val excludingKnownLeaks: Boolean,
    val weakReference: KeyedWeakReferenceMirror
  )

  fun findPaths(
    parser: HprofParser,
    leakingWeakRefs: List<KeyedWeakReferenceMirror>,
    gcRootIds: MutableList<Long>
  ): List<Result> {
    clearState()
    // Referent object id to weak ref mirror
    val referentMap = leakingWeakRefs.associateBy { it.referent.value }

    enqueueGcRoots(parser, gcRootIds)
    gcRootIds.clear()

    var excludingKnownLeaks = false
    val results = mutableListOf<Result>()
    while (!toVisitQueue.isEmpty() || !toVisitIfNoPathQueue.isEmpty()) {
      val node: LeakNode
      if (!toVisitQueue.isEmpty()) {
        node = toVisitQueue.poll()
        toVisitSet.remove(node.instance)
      } else {
        node = toVisitIfNoPathQueue.poll()
        toVisitIfNoPathSet.remove(node.instance)
        if (node is RootNode || (node is ChildNode && node.exclusion == null)) {
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

      val record = parser.retrieveRecordById(node.instance)

      when (record) {
        is ClassDumpRecord -> visitClassRecord(parser, record, node)
        is InstanceDumpRecord -> visitInstanceRecord(parser, record, node)
        is ObjectArrayDumpRecord -> visitObjectArrayRecord(parser, record, node)
        else -> throw IllegalStateException("Unexpected type for $record")
      }
    }

    clearState()
    return results
  }

  private fun checkSeen(node: LeakNode): Boolean {
    val alreadySeen = visitedSet.add(node.instance)
    return !alreadySeen
  }

  private fun clearState() {
    toVisitQueue.clear()
    toVisitIfNoPathQueue.clear()
    toVisitSet.clear()
    toVisitIfNoPathSet.clear()
    visitedSet.clear()
  }

  private fun enqueueGcRoots(
    hprofParser: HprofParser,
    gcRootIds: List<Long>
  ) {
    // TODO sort GC roots based on type and class name (for class / instance / array)
    // Goal is to get a stable shortest path
    // TODO Add root type so that for java local we could exclude specific threads.
    // TODO java local: exclude specific threads,
    // TODO java local: parent should be set to the allocated thread
    gcRootIds.forEach {
      enqueue(hprofParser, RootNode(it))
    }
  }

  private fun visitClassRecord(
    hprofParser: HprofParser,
    record: ClassDumpRecord,
    node: LeakNode
  ) {
    val className = hprofParser.className(record.id)

    val ignoredStaticFields = excludedRefs.staticFieldNameByClassName[className] ?: emptyMap()

    for (staticField in record.staticFields) {
      val objectId = (staticField.value as? ObjectReference)?.value ?: continue
      val fieldName = hprofParser.hprofStringById(staticField.nameStringId)
      if (fieldName == "\$staticOverhead") {
        continue
      }

      val leakReference = LeakReference(STATIC_FIELD, fieldName, "object $objectId")

      val exclusion = ignoredStaticFields[fieldName]

      if (exclusion == null || !exclusion.alwaysExclude) {
        enqueue(hprofParser, ChildNode(objectId, exclusion, node, leakReference))
      }
    }
  }

  private fun visitInstanceRecord(
    hprofParser: HprofParser,
    record: InstanceDumpRecord,
    parent: LeakNode
  ) {
    val instance = hprofParser.hydrateInstance(record)

    val exclusions = instance.classHierarchy.map {
      excludedRefs.classNames[it.className]
    }

    if (exclusions.firstOrNull {
          it != null && it.alwaysExclude
        } != null) {
      return
    }

    val classExclusion = exclusions.firstOrNull { it != null }

    val ignoredFields = LinkedHashMap<String, Exclusion>()

    instance.classHierarchy.forEach {
      ignoredFields.putAll(excludedRefs.fieldNameByClassName[it.className] ?: emptyMap())
    }

    val fieldNamesAndValues = mutableListOf<Pair<String, HeapValue>>()

    instance.fieldValues.forEachIndexed { classIndex, classFieldValues ->
      classFieldValues.forEachIndexed { fieldIndex, fieldValue ->
        val fieldName = instance.classHierarchy[classIndex].fieldNames[fieldIndex]
        fieldNamesAndValues.add(fieldName to fieldValue)
      }
    }

    fieldNamesAndValues.sortBy { (name, _) -> name }

    fieldNamesAndValues.filter { (_, value) -> value is ObjectReference }
        .map { (name, reference) -> name to (reference as ObjectReference).value }
        .forEach { (fieldName, objectId) ->
          val fieldExclusion = ignoredFields[fieldName]

          val exclusion = if (classExclusion != null && classExclusion.alwaysExclude) {
            classExclusion
          } else if (fieldExclusion != null && fieldExclusion.alwaysExclude) {
            fieldExclusion
          } else classExclusion ?: fieldExclusion
          enqueue(
              hprofParser, ChildNode(
              objectId,
              exclusion, parent,
              LeakReference(INSTANCE_FIELD, fieldName, "object $objectId")
          )
          )
        }
  }

  private fun visitObjectArrayRecord(
    hprofParser: HprofParser,
    record: ObjectArrayDumpRecord,
    parentNode: LeakNode
  ) {
    record.elementIds.forEachIndexed { index, elementId ->
      val name = Integer.toString(index)
      val reference = LeakReference(ARRAY_ENTRY, name, "object $elementId")
      enqueue(hprofParser, ChildNode(elementId, null, parentNode, reference))
    }
  }

  private fun enqueue(
    hprofParser: HprofParser,
    node: LeakNode
  ) {
    if (node.instance == 0L) {
      return
    }

    // Whether we want to visit now or later, we should skip if this is already to visit.
    if (toVisitSet.contains(node.instance)) {
      return
    }
    if (visitedSet.contains(node.instance)) {
      return
    }

    val visitNow = node is RootNode || (node is ChildNode && node.exclusion == null)
    if (!visitNow && toVisitIfNoPathSet.contains(node.instance)) {
      return
    }

    val objectIdMetadata = hprofParser.objectIdMetadata(node.instance)
    if (objectIdMetadata in SKIP_ENQUEUE) {
      return
    }

    if (visitNow) {
      toVisitSet.add(node.instance)
      toVisitQueue.add(node)
    } else {
      toVisitIfNoPathSet.add(node.instance)
      toVisitIfNoPathQueue.add(node)
    }
  }

  companion object {
    private val SKIP_ENQUEUE =
      setOf(PRIMITIVE_WRAPPER, PRIMITIVE_WRAPPER_ARRAY, PRIMITIVE_ARRAY, STRING, SHALLOW_INSTANCE)
  }
}
