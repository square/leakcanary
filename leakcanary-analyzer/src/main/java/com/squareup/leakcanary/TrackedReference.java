package com.squareup.leakcanary;

import androidx.annotation.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An instance tracked by a {@link KeyedWeakReference} that hadn't been cleared when the
 * heap was dumped. May or may not point to a leaking reference.
 */
public class TrackedReference {

  /** Corresponds to {@link KeyedWeakReference#key}. */
  @NonNull public final String key;

  /** Corresponds to {@link KeyedWeakReference#name}. */
  @NonNull public final String name;

  /** Class of the tracked instance. */
  @NonNull public final String className;

  /** List of all fields (member and static) for that instance. */
  @NonNull public final List<LeakReference> fields;

  public TrackedReference(@NonNull String key, @NonNull String name, @NonNull String className,
      @NonNull List<LeakReference> fields) {
    this.key = key;
    this.name = name;
    this.className = className;
    this.fields = Collections.unmodifiableList(new ArrayList<>(fields));
  }
}
