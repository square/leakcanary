package com.squareup.leakcanary.tests;

import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;

public class TestActivity extends FragmentActivity {

  private static Fragment leakingFragment;

  @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    leakingFragment = new TestFragment();
    getSupportFragmentManager().beginTransaction().add(0, leakingFragment).commitNow();
  }
}
