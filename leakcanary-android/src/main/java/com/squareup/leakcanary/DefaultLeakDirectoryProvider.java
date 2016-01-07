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
package com.squareup.leakcanary;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Environment;
import com.squareup.leakcanary.internal.RequestStoragePermissionActivity;
import java.io.File;

import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.M;
import static android.os.Environment.DIRECTORY_DOWNLOADS;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.showNotification;

public final class DefaultLeakDirectoryProvider implements LeakDirectoryProvider {

  private final Context context;

  public DefaultLeakDirectoryProvider(Context context) {
    this.context = context.getApplicationContext();
  }

  @Override public File leakDirectory() {
    File downloadsDirectory = Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS);
    File directory = new File(downloadsDirectory, "leakcanary-" + context.getPackageName());
    boolean success = directory.mkdirs();
    if (!success && !directory.exists()) {
      throw new UnsupportedOperationException(
          "Could not create leak directory " + directory.getPath());
    }
    return directory;
  }

  @Override public void requestWritePermission() {
    if (hasStoragePermission()) {
      return;
    }
    PendingIntent pendingIntent = RequestStoragePermissionActivity.createPendingIntent(context);
    String contentTitle = context.getString(R.string.leak_canary_permission_notification_title);
    CharSequence packageName = context.getPackageName();
    String contentText =
        context.getString(R.string.leak_canary_permission_notification_text, packageName);
    showNotification(context, contentTitle, contentText, pendingIntent);
  }

  @Override public boolean isLeakStorageWritable() {
    if (!hasStoragePermission()) {
      return false;
    }
    String state = Environment.getExternalStorageState();
    return Environment.MEDIA_MOUNTED.equals(state);
  }

  @TargetApi(M) private boolean hasStoragePermission() {
    if (SDK_INT < M) {
      return true;
    }
    return context.checkSelfPermission(WRITE_EXTERNAL_STORAGE) == PERMISSION_GRANTED;
  }
}
