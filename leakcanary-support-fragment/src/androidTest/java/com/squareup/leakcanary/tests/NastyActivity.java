package com.squareup.leakcanary.tests;

import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.FragmentActivity;
import android.view.View;

import com.squareup.leakcanary.support.fragment.R;

public class NastyActivity extends FragmentActivity {

  @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setContentView(R.layout.activity_main);

    findViewById(R.id.add).setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        getSupportFragmentManager()
            .beginTransaction()
            .addToBackStack(null)
            .replace(R.id.fragments, new NastyFragment())
            .commit();
      }
    });
  }
}
