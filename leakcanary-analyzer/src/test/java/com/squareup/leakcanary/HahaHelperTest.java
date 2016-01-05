package com.squareup.leakcanary;

import com.squareup.haha.perflib.ArrayInstance;
import com.squareup.haha.perflib.ClassInstance;
import com.squareup.haha.perflib.ClassObj;
import com.squareup.haha.perflib.Field;
import com.squareup.haha.perflib.Snapshot;
import com.squareup.haha.perflib.Type;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class HahaHelperTest {
  private static final int STRING_CLASS_ID = 100;
  private static final int CHAR_ARRAY_CLASS_ID = 101;
  private static final int STRING_INSTANCE_ID = 102;
  private static final int VALUE_ARRAY_INSTANCE_ID = 103;

  private static final int VALUE_ARRAY_LENGTH = 6;
  private static final int COUNT_VALUE = 5;
  private static final int OFFSET_VALUE = 1;

  private FakeHprofBuffer buffer;
  private Snapshot snapshot;

  @Before
  public void setUp() {
    buffer = new FakeHprofBuffer();

    snapshot = new Snapshot(buffer);
    // set HPROF identifier size; required for Object instance field lookups
    // cf. https://java.net/downloads/heap-snapshot/hprof-binary-format.html
    snapshot.setIdSize(4);
  }

  @Test
  public void readStringOffsetFromHeapDumpInstance() {
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

  @Test
  public void defaultToZeroStringOffsetWhenHeapDumpInstanceIsMissingOffsetValue() {
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

  @Test
  public void defaultToZeroStringOffsetWhenReadingMPreview2HeapDump() {
    buffer.setIntsToRead(COUNT_VALUE, OFFSET_VALUE, VALUE_ARRAY_INSTANCE_ID);
    buffer.setStringsToRead("abcdef");

    addStringClassToSnapshotWithFields(snapshot, new Field[]{
            new Field(Type.INT, "count"),
            new Field(Type.INT, "offset"),
            new Field(Type.OBJECT, "value")
    });

    ClassInstance stringInstance = createStringInstance();
    createCharArrayValueInstance_M_Preview2();

    String actual = HahaHelper.asString(stringInstance);
    assertTrue(actual.equals("abcde"));
  }

  @Test
  public void throwExceptionWhenMissingCharArrayValueForStringInMPreview2HeapDump() {
    buffer.setIntsToRead(COUNT_VALUE, OFFSET_VALUE, VALUE_ARRAY_INSTANCE_ID);
    buffer.setStringsToRead("abcdef");

    addStringClassToSnapshotWithFields(snapshot, new Field[]{
            new Field(Type.INT, "count"),
            new Field(Type.INT, "offset"),
            new Field(Type.OBJECT, "value")
    });

    ClassInstance stringInstance = createStringInstance();
    createObjectValueInstance_M_Preview2();

    try {
      HahaHelper.asString(stringInstance);
      fail("this test should have thrown UnsupportedOperationException");
    }
    catch (UnsupportedOperationException uoe) {
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

  private void createCharArrayValueInstance() {
    ArrayInstance valueArrayInstance = new ArrayInstance(0, null, Type.CHAR, VALUE_ARRAY_LENGTH, 0);
    snapshot.addInstance(VALUE_ARRAY_INSTANCE_ID, valueArrayInstance);
  }

  private void createCharArrayValueInstance_M_Preview2() {
    ArrayInstance valueInstance = new ArrayInstance(0, null, Type.CHAR, VALUE_ARRAY_LENGTH, 0);
    snapshot.addInstance(STRING_INSTANCE_ID + 16, valueInstance);
  }

  private void createObjectValueInstance_M_Preview2() {
    ClassInstance valueInstance = new ClassInstance(0, null, 0);
    snapshot.addInstance(STRING_INSTANCE_ID + 16, valueInstance);
  }

  private ClassInstance createStringInstance() {
    ClassInstance stringInstance = new ClassInstance(STRING_INSTANCE_ID, null, 100);
    stringInstance.setClassId(STRING_CLASS_ID);
    snapshot.addInstance(0, stringInstance);
    return stringInstance;
  }
}
