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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

public final class RefWatcher {

  // TODO Remove this, there should be a different API for overall on / off.
  public static final RefWatcher DISABLED = new RefWatcher();

  public interface NewRefListener {
    void onNewKeyedWeakReference(KeyedWeakReference reference);
  }

  private final Set<String> retainedKeys;
  private final ReferenceQueue<Object> queue;
  // TODO What makes sense for thread safety here?
  private final List<NewRefListener> newRefListeners;

  RefWatcher() {
    retainedKeys = new CopyOnWriteArraySet<>();
    queue = new ReferenceQueue<>();
    newRefListeners = new ArrayList<>();
  }

  public void addNewRefListener(NewRefListener listener) {
    newRefListeners.add(listener);
  }

  public void removeNewRefListener(NewRefListener listener) {
    newRefListeners.remove(listener);
  }

  /**
   * Identical to {@link #watch(Object, String)} with an empty string reference name.
   */
  public void watch(Object watchedReference) {
    watch(watchedReference, "");
  }

  /**
   * Watches the provided references and checks if it can be GCed. This method is non blocking,
   * the check is done on the {@link WatchExecutor} this {@link RefWatcher} has been constructed
   * with.
   *
   * @param referenceName An logical identifier for the watched object.
   */
  public void watch(Object watchedReference, String referenceName) {
    String key = UUID.randomUUID().toString();
    retainedKeys.add(key);
    KeyedWeakReference reference =
        new KeyedWeakReference(watchedReference, key, referenceName, queue);

    for (NewRefListener listener : newRefListeners) {
      listener.onNewKeyedWeakReference(reference);
    }
  }

  /**
   * LeakCanary will stop watching any references that were passed to {@link #watch(Object, String)}
   * so far.
   */
  public void clearWatchedReferences() {
    // TODO The heap dumper should be able to get the set of watched refs and then tell ref watcher
    // to remove all of those (and mark them as such in a KeyedWeakRef field).
    // That way they're clearly identifiable prior as well as after.
    retainedKeys.clear();
  }

  boolean isEmpty() {
    removeWeaklyReachableReferences();
    return retainedKeys.isEmpty();
  }

  Set<String> getRetainedKeys() {
    return new HashSet<>(retainedKeys);
  }

  public boolean gone(KeyedWeakReference reference) {
    removeWeaklyReachableReferences();
    return !retainedKeys.contains(reference.key);
  }

  private void removeWeaklyReachableReferences() {
    // WeakReferences are enqueued as soon as the object to which they point to becomes weakly
    // reachable. This is before finalization or garbage collection has actually happened.
    KeyedWeakReference ref;
    while ((ref = (KeyedWeakReference) queue.poll()) != null) {
      retainedKeys.remove(ref.key);
    }
  }
}
