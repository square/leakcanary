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
package com.squareup.leakcanary.internal;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import com.squareup.leakcanary.R;

import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Build.VERSION_CODES.M;
import static android.widget.Toast.LENGTH_LONG;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.setEnabledBlocking;

@TargetApi(M) //
public class RequestStoragePermissionActivity extends Activity {

  public static PendingIntent createPendingIntent(Context context) {
    setEnabledBlocking(context, RequestStoragePermissionActivity.class, true);
    Intent intent = new Intent(context, RequestStoragePermissionActivity.class);
    intent.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP);
    return PendingIntent.getActivity(context, 1, intent, FLAG_UPDATE_CURRENT);
  }

  @Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    if (savedInstanceState == null) {
      if (hasStoragePermission()) {
        finish();
        return;
      }
      String[] permissions = {
          WRITE_EXTERNAL_STORAGE
      };
      requestPermissions(permissions, 42);
    }
  }

  @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
      int[] grantResults) {
    if (!hasStoragePermission()) {
      Toast.makeText(getApplication(), R.string.leak_canary_permission_not_granted, LENGTH_LONG)
          .show();
    }
    finish();
  }

  @Override public void finish() {
    // Reset the animation to avoid flickering.
    overridePendingTransition(0, 0);
    super.finish();
  }

  private boolean hasStoragePermission() {
    return checkSelfPermission(WRITE_EXTERNAL_STORAGE) == PERMISSION_GRANTED;
  }
}
