package shark.internal

import shark.HeapObject
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray

internal class DelegatingObjectReferenceReader(
  private val classReferenceReader: ReferenceReader<HeapClass>,
  private val instanceReferenceReader: ReferenceReader<HeapInstance>,
  private val objectArrayReferenceReader: ReferenceReader<HeapObjectArray>,
) : ReferenceReader<HeapObject> {
  override fun read(source: HeapObject): Sequence<Reference> {
    return when(source) {
      is HeapClass -> classReferenceReader.read(source)
      is HeapInstance -> instanceReferenceReader.read(source)
      is HeapObjectArray -> objectArrayReferenceReader.read(source)
      is HeapPrimitiveArray -> emptySequence()
    }
  }
}
