package com.squareup.leakcanary;

import androidx.annotation.NonNull;

/**
 * No-op implementation of {@link RefWatcher} for release builds. Please use {@link
 * RefWatcher#DISABLED}.
 */
public final class RefWatcher {

  @NonNull public static final RefWatcher DISABLED = new RefWatcher();

  private RefWatcher() {
  }

  public void watch(@NonNull Object watchedReference) {
  }

  public void watch(@NonNull Object watchedReference, @NonNull String referenceName) {
  }
}
