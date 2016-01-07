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

import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Build.VERSION_CODES.M;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.setEnabledBlocking;

@TargetApi(M) //
public class RequestStoragePermissionActivity extends Activity {

  public static PendingIntent createPendingIntent(Context context) {
    setEnabledBlocking(context, RequestStoragePermissionActivity.class, true);
    Intent intent = new Intent(context, RequestStoragePermissionActivity.class);
    intent.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP);
    return PendingIntent.getActivity(context, 1, intent, FLAG_UPDATE_CURRENT);
  }

  @Override protected void onResume() {
    super.onResume();
    // This won't work well if the user doesn't enable the permission.
    // Seems ok for a dev tool, especially since you have to click a notification
    // to get here.
    if (checkSelfPermission(WRITE_EXTERNAL_STORAGE) == PERMISSION_GRANTED) {
      finish();
    } else {
      String[] permissions = {
          WRITE_EXTERNAL_STORAGE
      };
      requestPermissions(permissions, 42);
    }
  }
}
