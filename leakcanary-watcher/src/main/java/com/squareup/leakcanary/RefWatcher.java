/*
 * Copyright (C) 2015 Square, Inc.
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

import java.lang.ref.ReferenceQueue;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Thread safe by locking on all methods, which should be reasonably efficient given how often
 * these methods are accessed.
 */
public final class RefWatcher {

  // TODO Remove this, there should be a different API for overall on / off.
  public static final RefWatcher DISABLED = new RefWatcher(new Clock() {
    @Override public long uptimeMillis() {
      return 0;
    }
  });

  public interface NewRefListener {
    void onNewKeyedWeakReference();
  }

  private final Clock clock;
  private final Map<String, KeyedWeakReference> retainedKeys;
  private final ReferenceQueue<Object> queue;
  private final List<NewRefListener> newRefListeners;

  RefWatcher(Clock clock) {
    this.clock = clock;
    retainedKeys = new LinkedHashMap<>();
    queue = new ReferenceQueue<>();
    newRefListeners = new ArrayList<>();
  }

  public synchronized void addNewRefListener(NewRefListener listener) {
    newRefListeners.add(listener);
  }

  public synchronized void removeNewRefListener(NewRefListener listener) {
    newRefListeners.remove(listener);
  }

  /**
   * Identical to {@link #watch(Object, String)} with an empty string reference name.
   */
  public synchronized void watch(Object watchedReference) {
    watch(watchedReference, "");
  }

  /**
   * Watches the provided references and checks if it can be GCed. This method is non blocking,
   * the check is done on the {@link WatchExecutor} this {@link RefWatcher} has been constructed
   * with.
   *
   * @param referenceName An logical identifier for the watched object.
   */
  public synchronized void watch(Object watchedReference, String referenceName) {
    String key = UUID.randomUUID().toString();
    long watchUptimeMillis = clock.uptimeMillis();
    KeyedWeakReference reference =
        new KeyedWeakReference(watchedReference, key, referenceName, watchUptimeMillis, queue);
    retainedKeys.put(key, reference);

    for (NewRefListener listener : newRefListeners) {
      listener.onNewKeyedWeakReference();
    }
  }

  /**
   * LeakCanary will stop watching any references that were passed to {@link #watch(Object, String)}
   * so far.
   */
  public synchronized void clearWatchedReferences() {
    retainedKeys.clear();
  }

  public synchronized boolean hasReferencesOlderThan(long durationMillis) {
    removeWeaklyReachableReferences();
    long now = clock.uptimeMillis();
    int count = 0;
    for (KeyedWeakReference reference : retainedKeys.values()) {
      if (now - reference.watchUptimeMillis >= durationMillis) {
        count++;
      }
    }
    return count > 0;
  }

  synchronized boolean isEmpty() {
    removeWeaklyReachableReferences();
    return retainedKeys.isEmpty();
  }

  public synchronized Set<String> getRetainedKeys() {
    removeWeaklyReachableReferences();
    return new HashSet<>(retainedKeys.keySet());
  }

  public synchronized Set<String> getRetainedKeysOlderThan(long durationMillis) {
    removeWeaklyReachableReferences();
    long now = clock.uptimeMillis();
    Set<String> retainedKeys = new HashSet<>();
    for (Map.Entry<String, KeyedWeakReference> entry : this.retainedKeys.entrySet()) {
      if (now - entry.getValue().watchUptimeMillis >= durationMillis) {
        retainedKeys.add(entry.getKey());
      }
    }
    return retainedKeys;
  }

  public synchronized void removeRetainedKeys(Set<String> keysToRemove) {
    retainedKeys.keySet().removeAll(keysToRemove);
  }

  private synchronized void removeWeaklyReachableReferences() {
    // WeakReferences are enqueued as soon as the object to which they point to becomes weakly
    // reachable. This is before finalization or garbage collection has actually happened.
    KeyedWeakReference ref;
    while ((ref = (KeyedWeakReference) queue.poll()) != null) {
      retainedKeys.remove(ref.key);
    }
  }
}
