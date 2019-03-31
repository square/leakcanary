package com.squareup.leakcanary.internal;

import androidx.core.content.FileProvider;

/**
 * There can only be one {@link FileProvider} provider registered per app, so we extend that class
 * just to use a distinct name.
 */
public class LeakCanaryFileProvider extends FileProvider {
}
