package shark

import java.util.LinkedHashMap
import kotlin.LazyThreadSafetyMode.NONE
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.FieldRecord
import shark.PrimitiveType.BOOLEAN
import shark.PrimitiveType.BYTE
import shark.PrimitiveType.CHAR
import shark.PrimitiveType.DOUBLE
import shark.PrimitiveType.FLOAT
import shark.PrimitiveType.INT
import shark.PrimitiveType.LONG
import shark.PrimitiveType.SHORT
import shark.Reference.LazyDetails
import shark.ReferenceLocationType.INSTANCE_FIELD
import shark.ReferencePattern.InstanceFieldPattern
import shark.internal.FieldIdReader

/**
 * Expands instance fields that hold non null references.
 */
class FieldInstanceReferenceReader(
  graph: HeapGraph,
  referenceMatchers: List<ReferenceMatcher>
) : ReferenceReader<HeapInstance> {

  private val fieldNameByClassName: Map<String, Map<String, ReferenceMatcher>>
  private val javaLangObjectId: Long

  private val sizeOfObjectInstances: Int

  private val stringValueFastPath: Boolean

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
    // A reference matcher on a java.lang.String field is unusual, and the fast path below doesn't
    // apply matchers, so it steps aside when there is one.
    stringValueFastPath = "java.lang.String" !in fieldNameByClassName
  }

  override fun read(source: HeapInstance): Sequence<Reference> {
    if (source.isPrimitiveWrapper ||
      source.instanceClass.instanceByteSize <= sizeOfObjectInstances
    ) {
      return emptySequence()
    }

    if (stringValueFastPath) {
      // Indexing picked up the id of the array a string holds its characters in as it read the heap
      // dump, so the one reference of the most numerous kind of instance costs no read here.
      val stringValueObjectId = source.indexedStringValueObjectId
      if (stringValueObjectId != ValueHolder.NULL_REFERENCE) {
        return sequenceOf(source.stringValueReference(stringValueObjectId))
      }
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

  private fun HeapInstance.stringValueReference(stringValueObjectId: Long): Reference {
    val locationClassObjectId = instanceClassId
    return Reference(
      valueObjectId = stringValueObjectId,
      isLowPriority = false,
      lazyDetailsResolver = {
        LazyDetails(
          name = stringValueFieldName(),
          locationClassObjectId = locationClassObjectId,
          locationType = INSTANCE_FIELD,
          matchedLibraryLeak = null,
          isVirtual = false
        )
      }
    )
  }

  /**
   * The name of the one and only reference field of java.lang.String, read from the class fields the
   * index holds rather than from the heap dump. `value` in every heap dump we've seen, but the index
   * finds the field by type, so this doesn't assume the name.
   */
  private fun HeapInstance.stringValueFieldName(): String {
    val stringClass = instanceClass
    val valueFieldRecord = stringClass.readRecordFields()
      .single { it.type == PrimitiveType.REFERENCE_HPROF_TYPE }
    return stringClass.instanceFieldName(valueFieldRecord)
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
