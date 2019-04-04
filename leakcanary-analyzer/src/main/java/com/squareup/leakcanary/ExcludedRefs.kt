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
package com.squareup.leakcanary

import java.io.Serializable
import java.util.Collections.unmodifiableMap
import java.util.LinkedHashMap

/**
 * Prevents specific references from being taken into account when computing the shortest strong
 * reference path from a suspected leaking instance to the GC roots.
 *
 * This class lets you ignore known memory leaks that you known about. If the shortest path
 * matches [ExcludedRefs], than the heap analyzer should look for a longer path with nothing
 * matching in [ExcludedRefs].
 */
class ExcludedRefs internal constructor(builder: BuilderWithParams) : Serializable {

  val fieldNameByClassName: Map<String, Map<String, Exclusion>>
  val staticFieldNameByClassName: Map<String, Map<String, Exclusion>>
  val threadNames: Map<String, Exclusion>
  val classNames: Map<String, Exclusion>

  init {
    this.fieldNameByClassName = unmodifiableRefStringMap(builder.fieldNameByClassName)
    this.staticFieldNameByClassName = unmodifiableRefStringMap(builder.staticFieldNameByClassName)
    this.threadNames = unmodifiableRefMap(builder.threadNames)
    this.classNames = unmodifiableRefMap(builder.classNames)
  }

  private fun unmodifiableRefStringMap(
    mapmap: Map<String, Map<String, ParamsBuilder>>
  ): Map<String, Map<String, Exclusion>> {
    val fieldNameByClassName = LinkedHashMap<String, Map<String, Exclusion>>()
    for ((key, value) in mapmap) {
      fieldNameByClassName[key] = unmodifiableRefMap(value)
    }
    return unmodifiableMap(fieldNameByClassName)
  }

  private fun unmodifiableRefMap(fieldBuilderMap: Map<String, ParamsBuilder>): Map<String, Exclusion> {
    val fieldMap = LinkedHashMap<String, Exclusion>()
    for ((key, value) in fieldBuilderMap) {
      fieldMap[key] = Exclusion(value)
    }
    return unmodifiableMap(fieldMap)
  }

  override fun toString(): String {
    var string = ""
    for ((clazz, value) in fieldNameByClassName) {
      for ((key, value1) in value) {
        val always = if (value1.alwaysExclude) " (always)" else ""
        string += "| Field: $clazz.$key$always\n"
      }
    }
    for ((clazz, value) in staticFieldNameByClassName) {
      for ((key, value1) in value) {
        val always = if (value1.alwaysExclude) " (always)" else ""
        string += "| Static field: $clazz.$key$always\n"
      }
    }
    for ((key, value) in threadNames) {
      val always = if (value.alwaysExclude) " (always)" else ""
      string += "| Thread:$key$always\n"
    }
    for ((key, value) in classNames) {
      val always = if (value.alwaysExclude) " (always)" else ""
      string += "| Class:$key$always\n"
    }
    return string
  }

  @Suppress("unused")
  internal class ParamsBuilder(val matching: String) {
    var name: String? = null
    var reason: String? = null
    var alwaysExclude: Boolean = false
  }

  interface Builder {
    fun instanceField(
      className: String,
      fieldName: String
    ): BuilderWithParams

    fun staticField(
      className: String,
      fieldName: String
    ): BuilderWithParams

    fun thread(threadName: String): BuilderWithParams

    fun clazz(className: String): BuilderWithParams

    fun build(): ExcludedRefs
  }

  class BuilderWithParams internal constructor() : Builder {

    internal val fieldNameByClassName = LinkedHashMap<String, MutableMap<String, ParamsBuilder>>()
    internal val staticFieldNameByClassName =
      LinkedHashMap<String, MutableMap<String, ParamsBuilder>>()
    internal val threadNames = LinkedHashMap<String, ParamsBuilder>()
    internal val classNames = LinkedHashMap<String, ParamsBuilder>()

    private var lastParams: ParamsBuilder? = null

    override fun instanceField(
      className: String,
      fieldName: String
    ): BuilderWithParams {
      var excludedFields: MutableMap<String, ParamsBuilder>? = fieldNameByClassName[className]
      if (excludedFields == null) {
        excludedFields = LinkedHashMap()
        fieldNameByClassName[className] = excludedFields
      }
      lastParams = ParamsBuilder("field $className#$fieldName")
      excludedFields[fieldName] = lastParams!!
      return this
    }

    override fun staticField(
      className: String,
      fieldName: String
    ): BuilderWithParams {
      var excludedFields: MutableMap<String, ParamsBuilder>? = staticFieldNameByClassName[className]
      if (excludedFields == null) {
        excludedFields = LinkedHashMap()
        staticFieldNameByClassName[className] = excludedFields
      }
      lastParams = ParamsBuilder("static field $className#$fieldName")
      excludedFields[fieldName] = lastParams!!
      return this
    }

    override fun thread(threadName: String): BuilderWithParams {
      lastParams = ParamsBuilder("any threads named $threadName")
      threadNames[threadName] = lastParams!!
      return this
    }

    /** Ignores all fields and static fields of all subclasses of the provided class name.  */
    override fun clazz(className: String): BuilderWithParams {
      lastParams = ParamsBuilder("any subclass of $className")
      classNames[className] = lastParams!!
      return this
    }

    fun named(name: String): BuilderWithParams {
      lastParams!!.name = name
      return this
    }

    fun reason(reason: String): BuilderWithParams {
      lastParams!!.reason = reason
      return this
    }

    fun alwaysExclude(): BuilderWithParams {
      lastParams!!.alwaysExclude = true
      return this
    }

    override fun build(): ExcludedRefs {
      return ExcludedRefs(this)
    }
  }

  companion object {

    fun builder(): Builder {
      return BuilderWithParams()
    }
  }
}
