package com.squareup.leakcanary;

import com.squareup.haha.perflib.ClassInstance;
import com.squareup.haha.perflib.Instance;
import java.util.List;

import static com.squareup.leakcanary.HahaHelper.classInstanceValues;
import static com.squareup.leakcanary.HahaHelper.fieldValue;

public class ActivityZombieMatcher implements OOMAutopsy.ZombieMatcher {
  @Override public String rootClassName() {
    return "android.app.Activity";
  }

  @Override public boolean isZombie(Instance instance) {
    List<ClassInstance.FieldValue> fields = classInstanceValues(instance);
    try {
      return fieldValue(fields, "mDestroyed");
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }
}
