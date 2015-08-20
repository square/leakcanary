package com.squareup.leakcanary;

/**
 * No-op implementation of {@link RefWatcher} for release builds. Please use {@link
 * RefWatcher#DISABLED}.
 */
public final class RefWatcher {

  public static final RefWatcher DISABLED = new RefWatcher();

  private RefWatcher() {
  }

  public void watch(Object watchedReference) {
  }

  public void watch(Object watchedReference, String referenceName) {
  }
}
