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
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Bundle
import android.os.Build
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import com.squareup.leakcanary.core.R

@TargetApi(Build.VERSION_CODES.M) //
internal class RequestPermissionActivity : Activity() {

  private val targetPermission: String
    get() = intent.getStringExtra(TARGET_PERMISSION_EXTRA)!!

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    if (savedInstanceState == null) {
      if (hasTargetPermission()) {
        finish()
        return
      }
      val permissions = arrayOf(targetPermission)
      requestPermissions(permissions, 42)
    }
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<String>,
    grantResults: IntArray
  ) {
    if (!hasTargetPermission()) {
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

  private fun hasTargetPermission(): Boolean {
    return checkSelfPermission(targetPermission) == PERMISSION_GRANTED
  }

  companion object {
    private const val TARGET_PERMISSION_EXTRA = "targetPermission"

    fun createIntent(context: Context, permission: String): Intent {
      return Intent(context, RequestPermissionActivity::class.java).apply {
        flags = FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP
        putExtra(TARGET_PERMISSION_EXTRA, permission)
      }
    }

    fun createPendingIntent(context: Context, permission: String): PendingIntent {
      val intent = createIntent(context, permission)
      val flags = if (Build.VERSION.SDK_INT >= 23) {
        FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE
      } else {
        FLAG_UPDATE_CURRENT
      }
      return PendingIntent.getActivity(context, 1, intent, flags)
    }
  }
}
