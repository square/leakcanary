package com.squareup.leakcanary;

import android.content.Context;
import android.os.Environment;
import java.io.File;

import static android.os.Environment.DIRECTORY_DOWNLOADS;

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

  @Override public boolean isLeakStorageWritable() {
    String state = Environment.getExternalStorageState();
    return Environment.MEDIA_MOUNTED.equals(state);
  }
}
