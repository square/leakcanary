package shark.explorer

import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.SharkLog

/**
 * The class an object is gathered under wherever the explorer gathers objects by class: the classes the
 * root's children are drawn as in [HeapDominatorTreemap], and the row every object starts on in
 * [ReverseDominatorTree].
 *
 * One of these per heap dump rather than one per tree, because a class name is expensive to look up and both
 * trees look up the same ones: [HeapGraph.findClassByName] performs two linear scans over every string of the
 * dump, and asking it per object of a production dump — once for every class object, once for every `byte[]`
 * — took the best part of a minute. So an answer is remembered whether or not there was one.
 */
internal class GroupingClasses(private val graph: HeapGraph) {

  private val classIdByName = mutableMapOf<String, Long?>()

  /**
   * The class [heapObject] is gathered under, or null for one that isn't gathered: a class object, unless
   * the dump has `java.lang.Class` for them all to gather under, which every Android heap dump does.
   */
  fun classIdOf(heapObject: HeapObject): Long? = when (heapObject) {
    is HeapInstance -> heapObject.instanceClassId
    is HeapObjectArray -> heapObject.arrayClassId
    // Not [HeapPrimitiveArray.arrayClass], which goes through findClassByName every time it's asked.
    is HeapPrimitiveArray -> classIdOf(heapObject.arrayClassName)
    is HeapClass -> classIdOf(JAVA_LANG_CLASS)
  }

  /** The id of a class by name, looked up once per name however many objects are asked about. */
  fun classIdOf(className: String): Long? {
    if (className in classIdByName) {
      return classIdByName[className]
    }
    val classId = graph.findClassByName(className)?.objectId
    if (classId == null) {
      // Which leaves the objects of that class ungathered — a cell each under the dominator tree's root,
      // one row for all of them in the tree read from the classes up — so it's worth a line saying so.
      SharkLog.d { "No class named $className in the heap dump, so nothing groups by it" }
    }
    classIdByName[className] = classId
    return classId
  }

  private companion object {
    const val JAVA_LANG_CLASS = "java.lang.Class"
  }
}
