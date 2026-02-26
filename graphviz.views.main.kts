#!/usr/bin/env kotlin -language-version 1.9

// Make sure you run "brew install kotlin graphviz" first.

@file:Repository("https://repo.maven.apache.org/maven2/")
@file:Repository("https://dl.google.com/dl/android/maven2/")
@file:DependsOn("com.squareup.leakcanary:shark-android:3.0-alpha-8")

import java.io.File
import shark.ActualMatchingReferenceReaderFactory
import shark.HeapObject
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HprofHeapGraph.Companion.openHeapGraph

val heapDumpFile = File("/Users/py/Desktop/memory-20240919T161101.hprof")

heapDumpFile.openHeapGraph().use { graph ->
  val referenceReader = ActualMatchingReferenceReaderFactory(emptyList()).createFor(graph)

  val visitedReferences = mutableListOf<ObjectReference>()

  val traversalRoots = graph.findClassByName("android.app.Activity")?.instances ?: emptySequence()

  val visitedObjectIds = traverseReferenceGraph(traversalRoots) { sourceObject ->
    referenceReader.read(sourceObject).mapNotNull { reference ->
      val targetObject = graph.findObjectById(reference.valueObjectId)
      val isView = targetObject is HeapInstance &&
        targetObject instanceOf "android.view.View"
      val isViewArray = targetObject is HeapObjectArray &&
        targetObject.arrayClassName == "android.view.View[]"
      if (isView || isViewArray) {
        visitedReferences += ObjectReference(
          sourceObjectId = sourceObject.objectId,
          targetObjectId = reference.valueObjectId,
          referenceName = reference.lazyDetailsResolver.resolve().name
        )
        targetObject
      } else {
        null
      }
    }
  }

  val objectNamesByObjectId =
    visitedObjectIds.associateWith { graph.findObjectById(it).toString() }

  val dotFile = File(heapDumpFile.parent, "${heapDumpFile.nameWithoutExtension}-views-refs.dot")
  dotFile.createGraphVizDotFile(objectNamesByObjectId, visitedReferences)

  val generatedFile = File(dotFile.parent, "${dotFile.nameWithoutExtension}.png")
  Runtime.getRuntime().exec("dot -Tpng ${dotFile.absolutePath} -o ${generatedFile.absolutePath}")
  println("Image generated at ${generatedFile.absolutePath}")
}

data class ObjectReference(
  val sourceObjectId: Long,
  val targetObjectId: Long,
  val referenceName: String
)

fun File.createGraphVizDotFile(
  objectNamesByObjectId: Map<Long, String>,
  visitedReferences: MutableList<ObjectReference>
) {
  printWriter().use { writer ->
    with(writer) {
      println("digraph Heap {")
      objectNamesByObjectId.forEach { (objectId, objectName) ->
        println("  object${objectId} [label=\"${objectName}\"]")
      }
      println()
      visitedReferences.forEach { (sourceObjectId, targetObjectId, referenceName) ->
        println("  object$sourceObjectId -> object$targetObjectId [label=\"${referenceName}\"]")
      }
      println("}")
    }
  }
}

fun traverseReferenceGraph(
  roots: Sequence<HeapObject>,
  readObjectReferences: (HeapObject) -> Sequence<HeapObject>
): Set<Long> {
  val visitedObjectIds = mutableSetOf<Long>()
  val queue = ArrayDeque<HeapObject>()
  queue.addAll(roots)
  while (queue.isNotEmpty()) {
    val dequeuedObject = queue.removeFirst()
    if (dequeuedObject.objectId !in visitedObjectIds) {
      visitedObjectIds += dequeuedObject.objectId
      queue.addAll(readObjectReferences(dequeuedObject))
    }
  }
  return visitedObjectIds
}
