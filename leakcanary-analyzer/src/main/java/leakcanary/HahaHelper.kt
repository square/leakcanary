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
package leakcanary

import com.squareup.haha.perflib.ArrayInstance
import com.squareup.haha.perflib.ClassInstance
import com.squareup.haha.perflib.ClassObj
import com.squareup.haha.perflib.Instance
import com.squareup.haha.perflib.Type
import java.lang.reflect.InvocationTargetException
import java.nio.charset.Charset
import java.util.Arrays.asList
import java.util.HashSet

internal object HahaHelper {

  private val WRAPPER_TYPES = HashSet(
      asList(
          Boolean::class.java.name, Char::class.java.name, Float::class.java.name,
          Double::class.java.name, Byte::class.java.name, Short::class.java.name,
          Int::class.java.name, Long::class.java.name
      )
  )

  fun threadName(holder: Instance): String {
    val values = classInstanceValues(holder)
    val nameField = fieldValue<Any>(values, "name")
        ?: // Sometimes we can't find the String at the expected memory address in the heap dump.
        // See https://github.com/square/leakcanary/issues/417 .
        return "Thread name not available"
    return asString(nameField)
  }

  fun extendsThread(clazz: ClassObj): Boolean {
    var extendsThread = false
    var parentClass = clazz
    while (parentClass.superClassObj != null) {
      if (parentClass.className == Thread::class.java.name) {
        extendsThread = true
        break
      }
      parentClass = parentClass.superClassObj
    }
    return extendsThread
  }

  /**
   * This returns a string representation of any object or value passed in.
   */
  fun valueAsString(value: Any?): String {
    val stringValue: String
    if (value == null) {
      stringValue = "null"
    } else if (value is ClassInstance) {
      val valueClassName = value.classObj.className
      if (valueClassName == String::class.java.name) {
        stringValue = '"'.toString() + asString(value) + '"'.toString()
      } else {
        stringValue = value.toString()
      }
    } else {
      stringValue = value.toString()
    }
    return stringValue
  }

  fun asStringArray(arrayInstance: ArrayInstance): MutableList<String> {
    val entries = mutableListOf<String>()
    for (arrayEntry in arrayInstance.values) {
      entries.add(asString(arrayEntry))
    }
    return entries
  }

  /** Given a string instance from the heap dump, this returns its actual string value.  */
  fun asString(stringObject: Any): String {
    val instance = stringObject as Instance
    val values = classInstanceValues(instance)

    val count = fieldValue<Int>(values, "count")!!
    if (count == 0) {
      return ""
    }

    val value = fieldValue<Any>(values, "value")!!

    var offset: Int?
    val array: ArrayInstance
    if (isCharArray(value)) {
      array = value as ArrayInstance

      offset = 0
      // < API 23
      // As of Marshmallow, substrings no longer share their parent strings' char arrays
      // eliminating the need for String.offset
      // https://android-review.googlesource.com/#/c/83611/
      if (hasField(values, "offset")) {
        offset = fieldValue<Int>(values, "offset")!!
      }

      val chars = array.asCharArray(offset, count)
      return String(chars)
    } else if (isByteArray(value)) {
      // In API 26, Strings are now internally represented as byte arrays.
      array = value as ArrayInstance

      // HACK - remove when HAHA's perflib is updated to https://goo.gl/Oe7ZwO.
      try {
        val asRawByteArray = ArrayInstance::class.java.getDeclaredMethod(
            "asRawByteArray", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        )
        asRawByteArray.isAccessible = true
        val rawByteArray = asRawByteArray.invoke(array, 0, count) as ByteArray
        return String(rawByteArray, Charset.forName("UTF-8"))
      } catch (e: NoSuchMethodException) {
        throw RuntimeException(e)
      } catch (e: IllegalAccessException) {
        throw RuntimeException(e)
      } catch (e: InvocationTargetException) {
        throw RuntimeException(e)
      }

    } else {
      throw UnsupportedOperationException("Could not find char array in $instance")
    }
  }

  fun isPrimitiveWrapper(value: Any): Boolean {
    return if (value !is ClassInstance) {
      false
    } else WRAPPER_TYPES.contains(
        value.classObj.className
    )
  }

  fun isPrimitiveOrWrapperArray(value: Any): Boolean {
    if (value !is ArrayInstance) {
      return false
    }
    return if (value.arrayType != Type.OBJECT) {
      true
    } else WRAPPER_TYPES.contains(
        value.classObj.className
    )
  }

  private fun isCharArray(value: Any): Boolean {
    return value is ArrayInstance && value.arrayType == Type.CHAR
  }

  private fun isByteArray(value: Any): Boolean {
    return value is ArrayInstance && value.arrayType == Type.BYTE
  }

  fun classInstanceValues(instance: Instance): List<ClassInstance.FieldValue> {
    val classInstance = instance as ClassInstance
    return classInstance.values
  }

  fun <T> fieldValue(
    values: List<ClassInstance.FieldValue>,
    fieldName: String
  ): T? {
    for (fieldValue in values) {
      if (fieldValue.field.name == fieldName) {
        @Suppress("UNCHECKED_CAST")
        return fieldValue.value as T
      }
    }
    throw IllegalArgumentException("Field $fieldName does not exists")
  }

  fun hasField(
    values: List<ClassInstance.FieldValue>,
    fieldName: String
  ): Boolean {
    for (fieldValue in values) {
      if (fieldValue.field.name == fieldName) {

        return true
      }
    }
    return false
  }

  fun <T> staticFieldValue(
    classObj: ClassObj,
    fieldName: String
  ): T {
    for ((key, value) in classObj.staticFieldValues) {
      if (key.name == fieldName) {
        @Suppress("UNCHECKED_CAST")
        return value as T
      }
    }
    throw IllegalArgumentException("Field $fieldName does not exists")
  }
}
