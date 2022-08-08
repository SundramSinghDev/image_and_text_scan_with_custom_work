package com.example.customcamera;

import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import com.example.customcamera.databinding.ActivityLauncherBinding;

public class LauncherActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);
        ActivityLauncherBinding binding = DataBindingUtil.setContentView(this,R.layout.activity_launcher);
        binding.scanObject.setOnClickListener(view -> startActivity(new Intent(LauncherActivity.this,MainActivity.class).putExtra("for","image")));
        binding.scanText.setOnClickListener(view -> startActivity(new Intent(LauncherActivity.this,MainActivity.class).putExtra("for","text")));

    }
}