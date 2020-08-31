package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.ValueHolder.IntHolder
import java.io.File

class HprofDeobfuscatorTest {

  @get:Rule
  var testFolder = TemporaryFolder()
  private lateinit var hprofFile: File

  @Before
  fun setUp() {
    hprofFile = testFolder.newFile("temp.hprof")
  }

  @Test
  fun deobfuscateHprofClassName() {
    val proguardMapping = ProguardMapping().create {
      clazz("Foo" to "a")
    }

    hprofFile.dump {
      "a" clazz {}
    }

    val deobfuscator = HprofDeobfuscator()
    val deobfuscatedFile = deobfuscator.deobfuscate(proguardMapping, hprofFile)

    deobfuscatedFile.readHprof { graph ->
      val fooClass = graph.findClassByName("Foo")
      assertThat(fooClass).isNotNull
    }
  }

  @Test
  fun deobfuscateHprofStaticFieldName() {
    val proguardMapping = ProguardMapping().create {
      clazz("Foo" to "a") {
        field { "staticField" to "b" }
      }
    }

    hprofFile.dump {
      "a" clazz {
        staticField["b"] = IntHolder(42)
      }
    }

    val deobfuscator = HprofDeobfuscator()
    val deobfuscatedFile = deobfuscator.deobfuscate(proguardMapping, hprofFile)

    deobfuscatedFile.readHprof { graph ->
      val fooClass = graph.findClassByName("Foo")!!

      assertThat(
          fooClass.readStaticFields()
              .map { it.name }
              .toList()
      ).contains("staticField")
    }
  }

  @Test
  fun deobfuscateHprofMemberFieldName() {
    val proguardMapping = ProguardMapping().create {
      clazz("Foo" to "a") {
        field { "instanceField" to "b" }
      }
    }

    hprofFile.dump {
      val classId = clazz(
          className = "a",
          fields = listOf("b" to IntHolder::class)
      )
      instance(classId, listOf(IntHolder(0)))
    }

    val deobfuscator = HprofDeobfuscator()
    val deobfuscatedFile = deobfuscator.deobfuscate(proguardMapping, hprofFile)

    deobfuscatedFile.readHprof { graph ->
      val instance = graph.instances.find { heapInstance ->
        heapInstance.instanceClassName == "Foo"
      }!!

      assertThat(
          instance.readFields()
              .map { it.name }
              .toList()
      ).contains("instanceField")
    }
  }

  @Test
  fun deobfuscateHprofClassNameUsedAsFieldName() {
    val proguardMapping = ProguardMapping().create {
      clazz("Foo" to "a") {
        field { "instanceField" to "a" }
      }
    }

    hprofFile.dump {
      val classNameRecord = stringRecord("a")

      val classId = clazz(
          classNameRecord = classNameRecord,
          fields = listOf(classNameRecord.id to IntHolder::class)
      )
      instance(classId, listOf(IntHolder(0)))
    }

    val deobfuscator = HprofDeobfuscator()
    val deobfuscatedFile = deobfuscator.deobfuscate(proguardMapping, hprofFile)

    deobfuscatedFile.readHprof { graph ->
      val instance = graph.instances.find { heapInstance ->
        heapInstance.instanceClassName == "Foo"
      }!!

      assertThat(
          instance.readFields()
              .map { it.name }
              .toList()
      ).contains("instanceField")
    }
  }

  @Test
  fun deobfuscateHprofClassNameUsedAsStaticFieldName() {
    val proguardMapping = ProguardMapping().create {
      clazz("Foo" to "a") {
        field { "staticField" to "a" }
      }
    }

    hprofFile.dump {
      val classNameRecord = stringRecord("a")

      clazz(
          classNameRecord = classNameRecord,
          staticFields = listOf(classNameRecord.id to IntHolder(42))
      )
    }

    val deobfuscator = HprofDeobfuscator()
    val deobfuscatedFile = deobfuscator.deobfuscate(proguardMapping, hprofFile)

    deobfuscatedFile.readHprof { graph ->
      val fooClass = graph.findClassByName("Foo")!!

      assertThat(
          fooClass.readStaticFields()
              .map { it.name }
              .toList()
      ).contains("staticField")
    }
  }

  @Test
  fun deobfuscateHprofTwoFieldsWithSameName() {
    val proguardMapping = ProguardMapping().create {
      clazz("Foo" to "a") {
        field { "instanceField1" to "c" }
      }
      clazz("Bar" to "b") {
        field { "instanceField2" to "c" }
      }
    }

    hprofFile.dump {
      val fooClassNameRecord = stringRecord("a")
      val barClassNameRecord = stringRecord("b")
      val fieldNameRecord = stringRecord("c")

      val fooClassId = clazz(
          classNameRecord = fooClassNameRecord,
          fields = listOf(fieldNameRecord.id to IntHolder::class)
      )
      instance(fooClassId, listOf(IntHolder(0)))

      val barClassId = clazz(
          classNameRecord = barClassNameRecord,
          fields = listOf(fieldNameRecord.id to IntHolder::class)
      )
      instance(barClassId, listOf(IntHolder(0)))
    }

    val deobfuscator = HprofDeobfuscator()
    val deobfuscatedFile = deobfuscator.deobfuscate(proguardMapping, hprofFile)

    deobfuscatedFile.readHprof { graph ->
      val fooInstance = graph.instances.find { heapInstance ->
        heapInstance.instanceClassName == "Foo"
      }!!

      assertThat(
          fooInstance.readFields()
              .map { it.name }
              .toList()
      ).contains("instanceField1")

      val barInstance = graph.instances.find { heapInstance ->
        heapInstance.instanceClassName == "Bar"
      }!!

      assertThat(
          barInstance.readFields()
              .map { it.name }
              .toList()
      ).contains("instanceField2")
    }
  }

  private fun File.readHprof(block: (HeapGraph) -> Unit) {
    Hprof.open(this)
        .use { hprof ->
          block(HprofHeapGraph.indexHprof(hprof))
        }
  }
}