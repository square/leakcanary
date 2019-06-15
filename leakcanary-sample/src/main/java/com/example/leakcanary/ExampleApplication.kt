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
package com.example.leakcanary

import android.app.Application
import android.os.StrictMode
import android.view.View
import leakcanary.AllFieldsLabeler
import leakcanary.AndroidExcludedRefs
import leakcanary.AndroidLabelers
import leakcanary.AndroidLeakInspectors
import leakcanary.HprofReader
import leakcanary.LeakCanary
import leakcanary.LeakNodeStatus
import leakcanary.Record.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import leakcanary.boolean
import leakcanary.reference

open class ExampleApplication : Application() {
  val leakedViews = mutableListOf<View>()

  override fun onCreate() {
    super.onCreate()
    enabledStrictMode()
    LeakCanary.config = LeakCanary.config.copy(exclusionsFactory = {
      val default = AndroidExcludedRefs.exclusionsFactory(
          AndroidExcludedRefs.appDefaults
      )
      default(
          it
      )// + Exclusion(StaticFieldExclusion("android.view.inputmethod.InputMethodManager", "sInstance"))
    }, leakInspectors = AndroidLeakInspectors.defaultAndroidInspectors() + { parser, node ->
      with(parser) {

        val record = node.instance.objectRecord

        if (record is InstanceDumpRecord && parser.className(
                record.classId
            ) == "android.view.ViewRootImpl"
        ) {
          val instance = parser.hydrateInstance(record)
          val contextValue = instance["mContext"]
          val ref = contextValue.reference!!
          val context = ref.objectRecord.hydratedInstance

          val weakRef =
            context["mActivityContext"].reference!!.objectRecord.hydratedInstance

          val reference = weakRef["referent"].reference
          if (reference == null) {
            LeakNodeStatus.unknown()
          } else {
            val activity = reference.objectRecord.hydratedInstance
            val mDestroyed = activity["mDestroyed"].boolean!!
            if (mDestroyed) LeakNodeStatus.leaking("Activity destroyed") else LeakNodeStatus.notLeaking("Activity not destroyed")
          }
        } else {

          LeakNodeStatus.unknown()
        }

      }
    }, labelers = AndroidLabelers.defaultAndroidLabelers(
        this
    ) + { parser, node ->
      with(parser) {
        val labels = mutableListOf<String>()

        labels += "This object id: ${node.instance}"

        val record = node.instance.objectRecord

        if (record is InstanceDumpRecord && parser.className(
                record.classId
            ) == "android.view.ViewRootImpl"
        ) {

          val instance = parser.hydrateInstance(record)
          val contextValue = instance["mContext"]
          val ref = contextValue.reference!!
          val context = ref.objectRecord.hydratedInstance

          val weakRef =
            context["mActivityContext"].reference!!.objectRecord.hydratedInstance

          val reference = weakRef["referent"].reference
          if (reference == null) {
            labels += "weak ref has null activity"

          } else {
            val activity = reference.objectRecord.hydratedInstance
            labels += "Activity ${activity.record.id} class ${activity.classHierarchy[0].className} mDestroyed: ${activity["mDestroyed"]}"
          }

        }

        labels
      }
    } + AllFieldsLabeler()

    )
  }

  private fun enabledStrictMode() {
    StrictMode.setThreadPolicy(
        StrictMode.ThreadPolicy.Builder()
            .detectAll()
            .penaltyLog()
            .penaltyDeath()
            .build()
    )
  }
}
