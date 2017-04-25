/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.leakcanary;

import com.squareup.haha.perflib.ArrayInstance;
import com.squareup.haha.perflib.ClassInstance;
import com.squareup.haha.perflib.ClassObj;
import com.squareup.haha.perflib.Field;
import com.squareup.haha.perflib.Instance;
import com.squareup.haha.perflib.Type;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.squareup.leakcanary.Preconditions.checkNotNull;
import static java.util.Arrays.asList;

public final class HahaHelper {

  private static final Set<String> WRAPPER_TYPES = new HashSet<>(
      asList(Boolean.class.getName(), Character.class.getName(), Float.class.getName(),
          Double.class.getName(), Byte.class.getName(), Short.class.getName(),
          Integer.class.getName(), Long.class.getName()));

  static String fieldToString(Map.Entry<Field, Object> entry) {
    return fieldToString(entry.getKey(), entry.getValue());
  }

  static String fieldToString(ClassInstance.FieldValue fieldValue) {
    return fieldToString(fieldValue.getField(), fieldValue.getValue());
  }

  static String fieldToString(Field field, Object value) {
    return field.getName() + " = " + value;
  }

  static String threadName(Instance holder) {
    List<ClassInstance.FieldValue> values = classInstanceValues(holder);
    Object nameField = fieldValue(values, "name");
    if (nameField == null) {
      // Sometimes we can't find the String at the expected memory address in the heap dump.
      // See https://github.com/square/leakcanary/issues/417 .
      return "Thread name not available";
    }
    return asString(nameField);
  }

  static boolean extendsThread(ClassObj clazz) {
    boolean extendsThread = false;
    ClassObj parentClass = clazz;
    while (parentClass.getSuperClassObj() != null) {
      if (clazz.getClassName().equals(Thread.class.getName())) {
        extendsThread = true;
        break;
      }
      parentClass = parentClass.getSuperClassObj();
    }
    return extendsThread;
  }

  static String asString(Object stringObject) {
    Instance instance = (Instance) stringObject;
    List<ClassInstance.FieldValue> values = classInstanceValues(instance);

    Integer count = fieldValue(values, "count");
    checkNotNull(count, "count");
    if (count == 0) {
      return "";
    }

    Object value = fieldValue(values, "value");
    checkNotNull(value, "value");

    Integer offset;
    ArrayInstance array;
    if (isCharArray(value)) {
      array = (ArrayInstance) value;

      offset = 0;
      // < API 23
      // As of Marshmallow, substrings no longer share their parent strings' char arrays
      // eliminating the need for String.offset
      // https://android-review.googlesource.com/#/c/83611/
      if (hasField(values, "offset")) {
        offset = fieldValue(values, "offset");
        checkNotNull(offset, "offset");
      }

      char[] chars = array.asCharArray(offset, count);
      return new String(chars);
    } else if (isByteArray(value)) {
      // In API 26, Strings are now internally represented as byte arrays.
      array = (ArrayInstance) value;

      // HACK - remove when HAHA's perflib is updated to https://goo.gl/Oe7ZwO.
      try {
        Method asRawByteArray =
            ArrayInstance.class.getDeclaredMethod("asRawByteArray", int.class, int.class);
        asRawByteArray.setAccessible(true);
        byte[] rawByteArray = (byte[]) asRawByteArray.invoke(array, 0, count);
        return new String(rawByteArray, Charset.forName("UTF-8"));
      } catch (NoSuchMethodException e) {
        throw new RuntimeException(e);
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      } catch (InvocationTargetException e) {
        throw new RuntimeException(e);
      }
    } else {
      throw new UnsupportedOperationException("Could not find char array in " + instance);
    }
  }

  public static boolean isPrimitiveWrapper(Object value) {
    if (!(value instanceof ClassInstance)) {
      return false;
    }
    return WRAPPER_TYPES.contains(((ClassInstance) value).getClassObj().getClassName());
  }

  public static boolean isPrimitiveOrWrapperArray(Object value) {
    if (!(value instanceof ArrayInstance)) {
      return false;
    }
    ArrayInstance arrayInstance = (ArrayInstance) value;
    if (arrayInstance.getArrayType() != Type.OBJECT) {
      return true;
    }
    return WRAPPER_TYPES.contains(arrayInstance.getClassObj().getClassName());
  }

  private static boolean isCharArray(Object value) {
    return value instanceof ArrayInstance && ((ArrayInstance) value).getArrayType() == Type.CHAR;
  }

  private static boolean isByteArray(Object value) {
    return value instanceof ArrayInstance && ((ArrayInstance) value).getArrayType() == Type.BYTE;
  }

  static List<ClassInstance.FieldValue> classInstanceValues(Instance instance) {
    ClassInstance classInstance = (ClassInstance) instance;
    return classInstance.getValues();
  }

  @SuppressWarnings({ "unchecked", "TypeParameterUnusedInFormals" })
  static <T> T fieldValue(List<ClassInstance.FieldValue> values, String fieldName) {
    for (ClassInstance.FieldValue fieldValue : values) {
      if (fieldValue.getField().getName().equals(fieldName)) {
        return (T) fieldValue.getValue();
      }
    }
    throw new IllegalArgumentException("Field " + fieldName + " does not exists");
  }

  static boolean hasField(List<ClassInstance.FieldValue> values, String fieldName) {
    for (ClassInstance.FieldValue fieldValue : values) {
      if (fieldValue.getField().getName().equals(fieldName)) {
        //noinspection unchecked
        return true;
      }
    }
    return false;
  }

  private HahaHelper() {
    throw new AssertionError();
  }
}
