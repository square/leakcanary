package com.squareup.leakcanary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An instance tracked by a {@link KeyedWeakReference} that hadn't been cleared when the
 * heap was dumped. May or may not point to a leaking reference.
 */
public class TrackedReference {

  /** Corresponds to {@link KeyedWeakReference#key}. */
  public final String key;

  /** Corresponds to {@link KeyedWeakReference#name}. */
  public final String name;

  /** Class of the tracked instance. */
  public final String className;

  /** List of all fields (member and static) for that instance. */
  public final List<String> fields;

  public TrackedReference(String key, String name, String className, List<String> fields) {
    this.key = key;
    this.name = name;
    this.className = className;
    this.fields = Collections.unmodifiableList(new ArrayList<>(fields));
  }
}
