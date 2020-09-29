package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.ValueHolder.CharHolder

class HprofHeapGraphTest {

  @Test fun `class index is consistent when parsing dump twice`() {
    val heapDump = dump {
      "SomeClass" clazz {}
    }

    val classIndex = heapDump.openHeapGraph().use { graph ->
      graph.findClassByName("SomeClass")!!.objectIndex
    }

    heapDump.openHeapGraph().use { graph ->
      val heapObject = graph.findObjectByIndex(classIndex)
      assertThat(heapObject).isInstanceOf(HeapClass::class.java)
      heapObject as HeapClass
      assertThat(heapObject.name).isEqualTo("SomeClass")
    }
  }

  @Test fun `instance index is consistent when parsing dump twice`() {
    val heapDump = dump {
      "SomeClass" instance {}
    }

    val instanceIndex = heapDump.openHeapGraph().use { graph ->
      graph.findClassByName("SomeClass")!!.instances.single().objectIndex
    }

    heapDump.openHeapGraph().use { graph ->
      val heapObject = graph.findObjectByIndex(instanceIndex)
      assertThat(heapObject).isInstanceOf(HeapInstance::class.java)
      heapObject as HeapInstance
      assertThat(heapObject.instanceClassName).isEqualTo("SomeClass")
    }
  }

  @Test fun `char is correctly converted back`() {
    val heapDump = dump {
      "SomeClass" clazz { staticField["myChar"] = CharHolder('p') }
    }
    val myChar = heapDump.openHeapGraph().use { graph ->
      val myClass = graph.findClassByName("SomeClass")!!
      myClass.readStaticField("myChar")!!.value.asChar!!
    }
    assertThat(myChar).isEqualTo('p')
  }
}