package com.squareup.leakcanary.internal;

import android.support.v4.content.FileProvider;

/**
 * There can only be one {@link FileProvider} provider registered per app, so we extend that class
 * just to use a distinct name.
 */
public class LeakCanaryFileProvider extends FileProvider {
}
