package com.squareup.leakcanary.tests;

import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.FragmentActivity;
import com.squareup.leakcanary.support.fragment.R;

public class TestActivity extends FragmentActivity {

  @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_test);
  }
}
