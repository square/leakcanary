package shark.internal

import java.util.LinkedHashMap
import kotlin.LazyThreadSafetyMode.NONE
import shark.HeapGraph
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.FieldRecord
import shark.IgnoredReferenceMatcher
import shark.LibraryLeakReferenceMatcher
import shark.PrimitiveType
import shark.PrimitiveType.BOOLEAN
import shark.PrimitiveType.BYTE
import shark.PrimitiveType.CHAR
import shark.PrimitiveType.DOUBLE
import shark.PrimitiveType.FLOAT
import shark.PrimitiveType.INT
import shark.PrimitiveType.LONG
import shark.PrimitiveType.SHORT
import shark.internal.Reference.LazyDetails
import shark.internal.ReferenceLocationType.INSTANCE_FIELD
import shark.ReferenceMatcher
import shark.ReferencePattern.InstanceFieldPattern
import shark.filterFor

/**
 * Expands instance fields that hold non null references.
 */
internal class FieldInstanceReferenceReader(
  graph: HeapGraph,
  referenceMatchers: List<ReferenceMatcher>
) : ReferenceReader<HeapInstance> {

  private val fieldNameByClassName: Map<String, Map<String, ReferenceMatcher>>
  private val javaLangObjectId: Long

  private val sizeOfObjectInstances: Int

  init {
    val objectClass = graph.findClassByName("java.lang.Object")
    javaLangObjectId = objectClass?.objectId ?: -1
    sizeOfObjectInstances = determineSizeOfObjectInstances(objectClass, graph)

    val fieldNameByClassName = mutableMapOf<String, MutableMap<String, ReferenceMatcher>>()
    referenceMatchers.filterFor(graph).forEach { referenceMatcher ->
      val pattern = referenceMatcher.pattern
      if (pattern is InstanceFieldPattern) {
        val mapOrNull = fieldNameByClassName[pattern.className]
        val map = if (mapOrNull != null) mapOrNull else {
          val newMap = mutableMapOf<String, ReferenceMatcher>()
          fieldNameByClassName[pattern.className] = newMap
          newMap
        }
        map[pattern.fieldName] = referenceMatcher
      }
    }
    this.fieldNameByClassName = fieldNameByClassName
  }

  override fun read(source: HeapInstance): Sequence<Reference> {
    if (source.isPrimitiveWrapper ||
      // We ignore the fact that String references a value array to avoid having
      // to read the string record and find the object id for that array, since we know
      // it won't be interesting anyway.
      // That also means the value array isn't added to the dominator tree, so we need to
      // add that back when computing shallow size in ShallowSizeCalculator.
      // Another side effect is that if the array is referenced elsewhere, we might
      // double count its side.
      source.instanceClassName == "java.lang.String" ||
      source.instanceClass.instanceByteSize <= sizeOfObjectInstances
    ) {
      return emptySequence()
    }

    val fieldReferenceMatchers = LinkedHashMap<String, ReferenceMatcher>()

    val classHierarchy = source.instanceClass.classHierarchyWithoutJavaLangObject(javaLangObjectId)

    classHierarchy.forEach {
      val referenceMatcherByField = fieldNameByClassName[it.name]
      if (referenceMatcherByField != null) {
        for ((fieldName, referenceMatcher) in referenceMatcherByField) {
          if (!fieldReferenceMatchers.containsKey(fieldName)) {
            fieldReferenceMatchers[fieldName] = referenceMatcher
          }
        }
      }
    }

    return with(source) {
      // Assigning to local variable to avoid repeated lookup and cast:
      // HeapInstance.graph casts HeapInstance.hprofGraph to HeapGraph in its getter
      val hprofGraph = graph
      val fieldReader by lazy(NONE) {
        FieldIdReader(readRecord(), hprofGraph.identifierByteSize)
      }
      val result = mutableListOf<Pair<String, Reference>>()
      var skipBytesCount = 0

      for (heapClass in classHierarchy) {
        for (fieldRecord in heapClass.readRecordFields()) {
          if (fieldRecord.type != PrimitiveType.REFERENCE_HPROF_TYPE) {
            // Skip all fields that are not references. Track how many bytes to skip
            skipBytesCount += hprofGraph.getRecordSize(fieldRecord)
          } else {
            // Skip the accumulated bytes offset
            fieldReader.skipBytes(skipBytesCount)
            skipBytesCount = 0
            val valueObjectId = fieldReader.readId()
            if (valueObjectId != 0L) {
              val name = heapClass.instanceFieldName(fieldRecord)
              val referenceMatcher = fieldReferenceMatchers[name]
              if (referenceMatcher !is IgnoredReferenceMatcher) {
                val locationClassObjectId = heapClass.objectId
                result.add(
                  name to Reference(
                    valueObjectId = valueObjectId,
                    isLowPriority = referenceMatcher != null,
                    lazyDetailsResolver = {
                      LazyDetails(
                        name = name,
                        locationClassObjectId = locationClassObjectId,
                        locationType = INSTANCE_FIELD,
                        matchedLibraryLeak = referenceMatcher as LibraryLeakReferenceMatcher?,
                        isVirtual = false
                      )
                    }
                  )
                )
              }
            }
          }
        }
      }
      result.sortBy { it.first }
      result.asSequence().map { it.second }
    }
  }

  /**
   * Returns class hierarchy for an instance, but without it's root element, which is always
   * java.lang.Object.
   * Why do we want class hierarchy without java.lang.Object?
   * In pre-M there were no ref fields in java.lang.Object; and FieldIdReader wouldn't be created
   * Android M added shadow$_klass_ reference to class, so now it triggers extra record read.
   * Solution: skip heap class for java.lang.Object completely when reading the records
   * @param javaLangObjectId ID of the java.lang.Object to run comparison against
   */
  private fun HeapClass.classHierarchyWithoutJavaLangObject(
    javaLangObjectId: Long
  ): List<HeapClass> {
    val result = mutableListOf<HeapClass>()
    var parent: HeapClass? = this
    while (parent != null && parent.objectId != javaLangObjectId) {
      result += parent
      parent = parent.superclass
    }
    return result
  }

  private fun HeapGraph.getRecordSize(field: FieldRecord) =
    when (field.type) {
      PrimitiveType.REFERENCE_HPROF_TYPE -> identifierByteSize
      BOOLEAN.hprofType -> 1
      CHAR.hprofType -> 2
      FLOAT.hprofType -> 4
      DOUBLE.hprofType -> 8
      BYTE.hprofType -> 1
      SHORT.hprofType -> 2
      INT.hprofType -> 4
      LONG.hprofType -> 8
      else -> throw IllegalStateException("Unknown type ${field.type}")
    }

  private fun determineSizeOfObjectInstances(
    objectClass: HeapClass?,
    graph: HeapGraph
  ): Int {
    return if (objectClass != null) {
      // In Android 16 ClassDumpRecord.instanceSize for java.lang.Object can be 8 yet there are 0
      // fields. This is likely because there is extra per instance data that isn't coming from
      // fields in the Object class. See #1374
      val objectClassFieldSize = objectClass.readFieldsByteSize()

      // shadow$_klass_ (object id) + shadow$_monitor_ (Int)
      val sizeOfObjectOnArt = graph.identifierByteSize + INT.byteSize
      if (objectClassFieldSize == sizeOfObjectOnArt) {
        sizeOfObjectOnArt
      } else {
        0
      }
    } else {
      0
    }
  }
}
