/*
 * Copyright (C) 2016 Square, Inc.
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
package leakcanary.internal

import android.annotation.TargetApi
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.squareup.leakcanary.R

import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Build.VERSION_CODES.M
import android.widget.Toast.LENGTH_LONG

@TargetApi(M) //
class RequestStoragePermissionActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    if (savedInstanceState == null) {
      if (hasStoragePermission()) {
        finish()
        return
      }
      val permissions = arrayOf(WRITE_EXTERNAL_STORAGE)
      requestPermissions(permissions, 42)
    }
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<String>,
    grantResults: IntArray
  ) {
    if (!hasStoragePermission()) {
      Toast.makeText(application, R.string.leak_canary_permission_not_granted, LENGTH_LONG)
          .show()
    }
    finish()
  }

  override fun finish() {
    // Reset the animation to avoid flickering.
    overridePendingTransition(0, 0)
    super.finish()
  }

  private fun hasStoragePermission(): Boolean {
    return checkSelfPermission(WRITE_EXTERNAL_STORAGE) == PERMISSION_GRANTED
  }

  companion object {

    fun createPendingIntent(context: Context): PendingIntent {
      LeakCanaryInternals.setEnabledBlocking(
          context, RequestStoragePermissionActivity::class.java, true
      )
      val intent = Intent(context, RequestStoragePermissionActivity::class.java)
      intent.flags = FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP
      return PendingIntent.getActivity(context, 1, intent, FLAG_UPDATE_CURRENT)
    }
  }
}
