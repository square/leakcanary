package com.squareup.leakcanary.tests;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import com.squareup.leakcanary.support.fragment.R;

public class TestActivity extends FragmentActivity {

  @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_test);
  }
}
