package com.squareup.leakcanary;

public class FragmentSupportV4ZombieMatcher extends FragmentZombieMatcher {

  @Override public String rootClassName() {
    return "android.support.v4.app.Fragment";
  }
}
