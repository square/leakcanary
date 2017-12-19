package com.squareup.leakcanary;

import com.squareup.haha.perflib.ArrayInstance;
import com.squareup.haha.perflib.ClassInstance;
import com.squareup.haha.perflib.ClassObj;
import com.squareup.haha.perflib.Field;
import com.squareup.haha.perflib.Snapshot;
import com.squareup.haha.perflib.Type;
import com.squareup.haha.perflib.io.HprofBuffer;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(JUnit4.class)
public class HahaHelperTest {
  private static final int STRING_CLASS_ID = 100;
  private static final int CHAR_ARRAY_CLASS_ID = 101;
  private static final int STRING_INSTANCE_ID = 102;
  private static final int VALUE_ARRAY_INSTANCE_ID = 103;
  private static final int BYTE_ARRAY_CLASS_ID = 104;

  private static final int VALUE_ARRAY_LENGTH = 6;
  private static final int COUNT_VALUE = 5;
  private static final int OFFSET_VALUE = 1;

  private FakeHprofBuffer buffer;
  private Snapshot snapshot;

  @Before
  public void setUp() {
    buffer = new FakeHprofBuffer();
    initSnapshot(buffer);
  }

  private void initSnapshot(HprofBuffer buffer) {
    snapshot = new Snapshot(buffer);
    // set HPROF identifier size; required for Object instance field lookups
    // cf. https://java.net/downloads/heap-snapshot/hprof-binary-format.html
    snapshot.setIdSize(4);
  }

  @Test public void readStringOffsetFromHeapDumpInstance_pre_O() {
    buffer.setIntsToRead(COUNT_VALUE, OFFSET_VALUE, VALUE_ARRAY_INSTANCE_ID);
    buffer.setStringsToRead("abcdef");

    addStringClassToSnapshotWithFields(snapshot, new Field[]{
            new Field(Type.INT, "count"),
            new Field(Type.INT, "offset"),
            new Field(Type.OBJECT, "value")
    });

    ClassInstance stringInstance = createStringInstance();
    createCharArrayValueInstance();

    String actual = HahaHelper.asString(stringInstance);
    assertTrue(actual.equals("bcdef"));
  }

  @Test public void defaultToZeroStringOffsetWhenHeapDumpInstanceIsMissingOffsetValue_pre_O() {
    buffer.setIntsToRead(COUNT_VALUE, VALUE_ARRAY_INSTANCE_ID);
    buffer.setStringsToRead("abcdef");

    addStringClassToSnapshotWithFields(snapshot, new Field[]{
            new Field(Type.INT, "count"),
            new Field(Type.OBJECT, "value")
    });

    ClassInstance stringInstance = createStringInstance();
    createCharArrayValueInstance();

    String actual = HahaHelper.asString(stringInstance);
    assertTrue(actual.equals("abcde"));
  }

  @Test public void readStringAsByteArrayFromHeapDumpInstance_O() {
    // O uses default charset UTF-8
    buffer = new FakeHprofBuffer("UTF-8");
    initSnapshot(buffer);

    buffer.setIntsToRead(COUNT_VALUE, VALUE_ARRAY_INSTANCE_ID);
    buffer.setStringsToRead("abcdef");

    addStringClassToSnapshotWithFields_O(snapshot, new Field[]{
        new Field(Type.INT, "count"),
        new Field(Type.OBJECT, "value")
    });

    ClassInstance stringInstance = createStringInstance();
    createByteArrayValueInstance();

    String actual = HahaHelper.asString(stringInstance);
    assertTrue(actual.equals("abcde"));
  }

  @Test public void throwExceptionWhenNotArrayValueForString() {
    buffer.setIntsToRead(COUNT_VALUE, OFFSET_VALUE, VALUE_ARRAY_INSTANCE_ID);
    buffer.setStringsToRead("abcdef");

    addStringClassToSnapshotWithFields(snapshot, new Field[]{
            new Field(Type.INT, "count"),
            new Field(Type.INT, "offset"),
            new Field(Type.OBJECT, "value")
    });

    ClassInstance stringInstance = createStringInstance();
    createObjectValueInstance();

    try {
      HahaHelper.asString(stringInstance);
      fail("this test should have thrown UnsupportedOperationException");
    } catch (UnsupportedOperationException uoe) {
      String message = uoe.getMessage();
      assertTrue(message.equals("Could not find char array in " + stringInstance));
    }
  }

  private void addStringClassToSnapshotWithFields(Snapshot snapshot, Field[] fields) {
    ClassObj charArrayClass = new ClassObj(0, null, "char[]", 0);
    snapshot.addClass(CHAR_ARRAY_CLASS_ID, charArrayClass);

    ClassObj stringClass = new ClassObj(0, null, "string", 0);
    stringClass.setFields(fields);
    snapshot.addClass(STRING_CLASS_ID, stringClass);
  }

  private void addStringClassToSnapshotWithFields_O(Snapshot snapshot, Field[] fields) {
    ClassObj byteArrayClass = new ClassObj(0, null, "byte[]", 0);
    snapshot.addClass(BYTE_ARRAY_CLASS_ID, byteArrayClass);

    ClassObj stringClass = new ClassObj(0, null, "string", 0);
    stringClass.setFields(fields);
    snapshot.addClass(STRING_CLASS_ID, stringClass);
  }

  private void createCharArrayValueInstance() {
    ArrayInstance valueArrayInstance = new ArrayInstance(0, null, Type.CHAR, VALUE_ARRAY_LENGTH, 0);
    snapshot.addInstance(VALUE_ARRAY_INSTANCE_ID, valueArrayInstance);
  }

  private void createByteArrayValueInstance() {
    ArrayInstance valueArrayInstance = new ArrayInstance(0, null, Type.BYTE, VALUE_ARRAY_LENGTH, 0);
    snapshot.addInstance(VALUE_ARRAY_INSTANCE_ID, valueArrayInstance);
  }

  private void createObjectValueInstance() {
    ClassInstance valueInstance = new ClassInstance(0, null, 0);
    snapshot.addInstance(VALUE_ARRAY_INSTANCE_ID, valueInstance);
  }

  private ClassInstance createStringInstance() {
    ClassInstance stringInstance = new ClassInstance(STRING_INSTANCE_ID, null, 100);
    stringInstance.setClassId(STRING_CLASS_ID);
    snapshot.addInstance(0, stringInstance);
    return stringInstance;
  }
}
