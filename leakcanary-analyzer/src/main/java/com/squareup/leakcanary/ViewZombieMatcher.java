package com.squareup.leakcanary;

import com.squareup.haha.perflib.ClassInstance;
import com.squareup.haha.perflib.Instance;
import java.util.List;

import static com.squareup.leakcanary.HahaHelper.classInstanceValues;
import static com.squareup.leakcanary.HahaHelper.fieldValue;

public class ViewZombieMatcher implements OOMAutopsy.ZombieMatcher {
  @Override public String rootClassName() {
    return "android.view.View";
  }

  @Override public boolean isZombie(Instance instance) {
    List<ClassInstance.FieldValue> fields = classInstanceValues(instance);
    try {
      Object mParent = fieldValue(fields, "mParent");
      Object mAttachInfo = fieldValue(fields, "mAttachInfo");
      // We only want the root view of a leaking view hierarchy, the rest is included by it.
      return mParent == null && mAttachInfo == null;
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }
}
