/*
 * Copyright (C) 2026 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package leakcanary;

/**
 * Android Studio's Profiler drives LeakCanary through a bridge library it injects into the app,
 * {@code com.android.tools.studio.leakcanary:leakcanary}, whose source lives in AOSP at
 * {@code tools/base/studio-leakcanary}. That bridge has no compile time dependency on LeakCanary:
 * it resolves everything it needs reflectively, by name, and a single failed lookup aborts the
 * whole initialization, at which point Studio reports LeakCanary as missing from the app.
 *
 * <p>Up to LeakCanary 2.14 the default {@link GcTrigger} was an object nested in {@link GcTrigger},
 * so it compiled to a class named {@code leakcanary.GcTrigger$Default} holding an {@code INSTANCE}
 * field, and that is the name the bridge looks up. In LeakCanary 3 the default moved to
 * {@link FinalizingInProcessGcTrigger}, reachable as {@code GcTrigger.inProcess()}, which left no
 * class under the old name. This class restores that name, and nothing else. It is deprecated
 * because no source should call it — {@code GcTrigger.inProcess()} is the same trigger under a name
 * meant to be read — but unlike most deprecated code it cannot be deleted, because the bridge looks
 * it up by string.
 *
 * <p>{@code AndroidStudioProfilerContractTest} in {@code leakcanary-android} replays the bridge's
 * lookups against the artifact an app actually gets, so deleting or renaming what the bridge needs
 * fails there rather than silently in the IDE.
 *
 * @deprecated Use {@code GcTrigger.inProcess()}. This type exists for Android Studio's reflective
 *     lookup and must not be removed.
 */
@Deprecated
public final class GcTrigger$Default implements GcTrigger {

  public static final GcTrigger$Default INSTANCE = new GcTrigger$Default();

  private GcTrigger$Default() {}

  @Override public void runGc() {
    FinalizingInProcessGcTrigger.INSTANCE.runGc();
  }
}
