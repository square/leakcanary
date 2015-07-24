package com.squareup.leakcanary;

import com.squareup.haha.perflib.ClassInstance;
import com.squareup.haha.perflib.Instance;
import java.util.List;

import static com.squareup.leakcanary.HahaHelper.classInstanceValues;
import static com.squareup.leakcanary.HahaHelper.fieldValue;

public class FragmentZombieMatcher implements OOMAutopsy.ZombieMatcher {

  private static final int CREATED = 1;

  @Override public String rootClassName() {
    return "android.app.Fragment";
  }

  @Override public boolean isZombie(Instance instance) {
    List<ClassInstance.FieldValue> fields = classInstanceValues(instance);
    try {
      Object mParentFragment = fieldValue(fields, "mParentFragment");
      int mState = fieldValue(fields, "mState");
      // Awesome internals, right? No value for DESTROY, just going back through CREATED.
      return mParentFragment == null && mState < CREATED;
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }
}
