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
import com.squareup.haha.perflib.Heap;
import com.squareup.haha.perflib.Instance;
import com.squareup.haha.perflib.RootObj;
import com.squareup.haha.perflib.Snapshot;
import com.squareup.haha.perflib.Type;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.squareup.leakcanary.LeakTraceElement.Holder.ARRAY;
import static com.squareup.leakcanary.LeakTraceElement.Holder.CLASS;
import static com.squareup.leakcanary.LeakTraceElement.Holder.OBJECT;
import static com.squareup.leakcanary.LeakTraceElement.Holder.THREAD;
import static com.squareup.leakcanary.Preconditions.checkNotNull;
import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

public final class HahaHelper {

  private static final String ANONYMOUS_CLASS_NAME_PATTERN = "^.+\\$\\d+$";

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
    Object value = fieldValue(values, "value");
    Integer offset;
    ArrayInstance charArray;
    if (isCharArray(value)) {
      charArray = (ArrayInstance) value;
      offset = fieldValue(values, "offset");
    } else {
      // In M preview 2+, the underlying char buffer resides in the heap with ID equalling the
      // String's ID + 16.
      // https://android-review.googlesource.com/#/c/160380/2/android/src/com/android/tools/idea/
      // editors/hprof/descriptors/InstanceFieldDescriptorImpl.java
      Heap heap = instance.getHeap();
      Instance inlineInstance = heap.getInstance(instance.getId() + 16);
      if (isCharArray(inlineInstance)) {
        charArray = (ArrayInstance) inlineInstance;
        offset = 0;
      } else {
        throw new UnsupportedOperationException("Could not find char array in " + instance);
      }
    }
    checkNotNull(count, "count");
    checkNotNull(charArray, "charArray");
    checkNotNull(offset, "offset");

    if (count == 0) {
      return "";
    }

    char[] chars = charArray.asCharArray(offset, count);

    return new String(chars);
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

  static List<ClassInstance.FieldValue> classInstanceValues(Instance instance) {
    ClassInstance classInstance = (ClassInstance) instance;
    return classInstance.getValues();
  }

  static <T> T fieldValue(List<ClassInstance.FieldValue> values, String fieldName) {
    for (ClassInstance.FieldValue fieldValue : values) {
      if (fieldValue.getField().getName().equals(fieldName)) {
        //noinspection unchecked
        return (T) fieldValue.getValue();
      }
    }
    throw new IllegalArgumentException("Field " + fieldName + " does not exists");
  }

  static LeakTrace buildLeakTrace(Snapshot snapshot, LeakNode leakingNode) {
    // Compute retained size.
    snapshot.computeDominators();

    List<LeakTraceElement> elements = new ArrayList<>();
    // We iterate from the leak to the GC root
    LeakNode node = new LeakNode(null, leakingNode, null, null);
    while (node != null) {
      LeakTraceElement element = buildLeakElement(node);
      if (element != null) {
        elements.add(0, element);
      }
      node = node.parent;
    }
    long retainedSize = leakingNode.instance.getTotalRetainedSize();

    return new LeakTrace(elements, retainedSize);
  }

  static LeakTraceElement buildLeakElement(LeakNode node) {
    if (node.parent == null) {
      // Ignore any root node.
      return null;
    }
    Instance holder = node.parent.instance;

    if (holder instanceof RootObj) {
      return null;
    }
    LeakTraceElement.Type type = node.referenceType;
    String referenceName = node.referenceName;

    LeakTraceElement.Holder holderType;
    String className;
    String extra = null;
    List<String> fields = new ArrayList<>();
    if (holder instanceof ClassObj) {
      ClassObj classObj = (ClassObj) holder;
      holderType = CLASS;
      className = classObj.getClassName();
      for (Map.Entry<Field, Object> entry : classObj.getStaticFieldValues().entrySet()) {
        Field field = entry.getKey();
        Object value = entry.getValue();
        fields.add("static " + field.getName() + " = " + value);
      }
    } else if (holder instanceof ArrayInstance) {
      ArrayInstance arrayInstance = (ArrayInstance) holder;
      holderType = ARRAY;
      className = arrayInstance.getClassObj().getClassName();
      if (arrayInstance.getArrayType() == Type.OBJECT) {
        Object[] values = arrayInstance.getValues();
        for (int i = 0; i < values.length; i++) {
          fields.add("[" + i + "] = " + values[i]);
        }
      }
    } else {
      ClassInstance classInstance = (ClassInstance) holder;
      ClassObj classObj = holder.getClassObj();
      for (Map.Entry<Field, Object> entry : classObj.getStaticFieldValues().entrySet()) {
        fields.add("static " + fieldToString(entry));
      }
      for (ClassInstance.FieldValue field : classInstance.getValues()) {
        fields.add(fieldToString(field));
      }
      className = classObj.getClassName();

      if (extendsThread(classObj)) {
        holderType = THREAD;
        String threadName = threadName(holder);
        extra = "(named '" + threadName + "')";
      } else if (className.matches(ANONYMOUS_CLASS_NAME_PATTERN)) {
        String parentClassName = classObj.getSuperClassObj().getClassName();
        if (Object.class.getName().equals(parentClassName)) {
          holderType = OBJECT;
          // This is an anonymous class implementing an interface. The API does not give access
          // to the interfaces implemented by the class. Let's see if it's in the class path and
          // use that instead.
          try {
            Class<?> actualClass = Class.forName(classObj.getClassName());
            Class<?> implementedInterface = actualClass.getInterfaces()[0];
            extra = "(anonymous class implements " + implementedInterface.getName() + ")";
          } catch (ClassNotFoundException ignored) {
          }
        } else {
          holderType = OBJECT;
          // Makes it easier to figure out which anonymous class we're looking at.
          extra = "(anonymous class extends " + parentClassName + ")";
        }
      } else {
        holderType = OBJECT;
      }
    }
    return new LeakTraceElement(referenceName, type, holderType, className, extra, fields);
  }

  static String getStackTraceString(Throwable throwable) {
    if (throwable == null) {
      return "";
    }
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    throwable.printStackTrace(pw);
    pw.flush();
    return sw.toString();
  }

  static long since(long analysisStartNanoTime) {
    return NANOSECONDS.toMillis(System.nanoTime() - analysisStartNanoTime);
  }

  private HahaHelper() {
    throw new AssertionError();
  }
}
