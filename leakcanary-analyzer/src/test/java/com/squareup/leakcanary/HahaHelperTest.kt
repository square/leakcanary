package com.squareup.leakcanary

import com.android.tools.perflib.captures.DataBuffer
import com.squareup.haha.perflib.ArrayInstance
import com.squareup.haha.perflib.ClassInstance
import com.squareup.haha.perflib.ClassObj
import com.squareup.haha.perflib.Field
import com.squareup.haha.perflib.Snapshot
import com.squareup.haha.perflib.Type
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class HahaHelperTest {

  private lateinit var buffer: FakeDataBuffer
  private lateinit var snapshot: Snapshot

  @Before
  fun setUp() {
    buffer = FakeDataBuffer()
    initSnapshot(buffer)
  }

  private fun initSnapshot(buffer: DataBuffer) {
    snapshot = Snapshot(buffer)
    // set HPROF identifier size; required for Object instance field lookups
    // cf. https://java.net/downloads/heap-snapshot/hprof-binary-format.html
    snapshot.setIdSize(4)
  }

  @Test fun readStringOffsetFromHeapDumpInstance_pre_O() {
    buffer.setIntsToRead(COUNT_VALUE, OFFSET_VALUE, VALUE_ARRAY_INSTANCE_ID)
    buffer.setStringsToRead("abcdef")

    addStringClassToSnapshotWithFields(
        snapshot,
        arrayOf(Field(Type.INT, "count"), Field(Type.INT, "offset"), Field(Type.OBJECT, "value"))
    )

    val stringInstance = createStringInstance()
    createCharArrayValueInstance()

    val actual = HahaHelper.asString(stringInstance)
    assertTrue(actual == "bcdef")
  }

  @Test fun defaultToZeroStringOffsetWhenHeapDumpInstanceIsMissingOffsetValue_pre_O() {
    buffer.setIntsToRead(COUNT_VALUE, VALUE_ARRAY_INSTANCE_ID)
    buffer.setStringsToRead("abcdef")

    addStringClassToSnapshotWithFields(
        snapshot, arrayOf(Field(Type.INT, "count"), Field(Type.OBJECT, "value"))
    )

    val stringInstance = createStringInstance()
    createCharArrayValueInstance()

    val actual = HahaHelper.asString(stringInstance)
    assertTrue(actual == "abcde")
  }

  @Test fun readStringAsByteArrayFromHeapDumpInstance_O() {
    // O uses default charset UTF-8
    buffer = FakeDataBuffer("UTF-8")
    initSnapshot(buffer)

    buffer.setIntsToRead(COUNT_VALUE, VALUE_ARRAY_INSTANCE_ID)
    buffer.setStringsToRead("abcdef")

    addStringClassToSnapshotWithFields_O(
        snapshot, arrayOf(Field(Type.INT, "count"), Field(Type.OBJECT, "value"))
    )

    val stringInstance = createStringInstance()
    createByteArrayValueInstance()

    val actual = HahaHelper.asString(stringInstance)
    assertTrue(actual == "abcde")
  }

  @Test fun throwExceptionWhenNotArrayValueForString() {
    buffer.setIntsToRead(COUNT_VALUE, OFFSET_VALUE, VALUE_ARRAY_INSTANCE_ID)
    buffer.setStringsToRead("abcdef")

    addStringClassToSnapshotWithFields(
        snapshot,
        arrayOf(Field(Type.INT, "count"), Field(Type.INT, "offset"), Field(Type.OBJECT, "value"))
    )

    val stringInstance = createStringInstance()
    createObjectValueInstance()

    try {
      HahaHelper.asString(stringInstance)
      fail("this test should have thrown UnsupportedOperationException")
    } catch (uoe: UnsupportedOperationException) {
      val message = uoe.message
      assertTrue(message == "Could not find char array in $stringInstance")
    }

  }

  private fun addStringClassToSnapshotWithFields(
    snapshot: Snapshot,
    fields: Array<Field>
  ) {
    val charArrayClass = ClassObj(0, null, "char[]", 0)
    snapshot.addClass(CHAR_ARRAY_CLASS_ID.toLong(), charArrayClass)

    val stringClass = ClassObj(0, null, "string", 0)
    stringClass.fields = fields
    snapshot.addClass(STRING_CLASS_ID.toLong(), stringClass)
  }

  private fun addStringClassToSnapshotWithFields_O(
    snapshot: Snapshot,
    fields: Array<Field>
  ) {
    val byteArrayClass = ClassObj(0, null, "byte[]", 0)
    snapshot.addClass(BYTE_ARRAY_CLASS_ID.toLong(), byteArrayClass)

    val stringClass = ClassObj(0, null, "string", 0)
    stringClass.fields = fields
    snapshot.addClass(STRING_CLASS_ID.toLong(), stringClass)
  }

  private fun createCharArrayValueInstance() {
    val valueArrayInstance = ArrayInstance(0, null, Type.CHAR, VALUE_ARRAY_LENGTH, 0)
    snapshot.addInstance(VALUE_ARRAY_INSTANCE_ID.toLong(), valueArrayInstance)
  }

  private fun createByteArrayValueInstance() {
    val valueArrayInstance = ArrayInstance(0, null, Type.BYTE, VALUE_ARRAY_LENGTH, 0)
    snapshot.addInstance(VALUE_ARRAY_INSTANCE_ID.toLong(), valueArrayInstance)
  }

  private fun createObjectValueInstance() {
    val valueInstance = ClassInstance(0, null, 0)
    snapshot.addInstance(VALUE_ARRAY_INSTANCE_ID.toLong(), valueInstance)
  }

  private fun createStringInstance(): ClassInstance {
    val stringInstance = ClassInstance(STRING_INSTANCE_ID.toLong(), null, 100)
    stringInstance.setClassId(STRING_CLASS_ID.toLong())
    snapshot.addInstance(0, stringInstance)
    return stringInstance
  }

  companion object {
    private const val STRING_CLASS_ID = 100
    private const val CHAR_ARRAY_CLASS_ID = 101
    private const val STRING_INSTANCE_ID = 102
    private const val VALUE_ARRAY_INSTANCE_ID = 103
    private const val BYTE_ARRAY_CLASS_ID = 104
    private const val VALUE_ARRAY_LENGTH = 6
    private const val COUNT_VALUE = 5
    private const val OFFSET_VALUE = 1
  }
}
