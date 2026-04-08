package com.portsip.sipsample.ui;

import com.portsip.R;
import com.portsip.sipsample.receiver.PortMessageReceiver;
import com.portsip.sipsample.service.PortSipService;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.RadioGroup;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final String TAG = "ClevertelMain";
    private static final int PERM_REQUEST_CODE = 101;

    public PortMessageReceiver receiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        receiver = new PortMessageReceiver(this);

        setupTabs();
        requestRequiredPermissions();
        requestBatteryOptimizationExemption();

        // Start the foreground SIP service
        Intent serviceIntent = new Intent(this, PortSipService.class);
        PortSipService.startServiceCompatibility(this, serviceIntent);
    }

    private void setupTabs() {
        RadioGroup tabGroup = findViewById(R.id.tab_menu);
        if (tabGroup == null) return;

        // Show login tab by default
        showOnlyFragment(R.id.login_fragment);
        tabGroup.check(R.id.tab_login);

        tabGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if      (checkedId == R.id.tab_login)   showOnlyFragment(R.id.login_fragment);
            else if (checkedId == R.id.tab_numpad)  showOnlyFragment(R.id.numpad_fragment);
            else if (checkedId == R.id.tab_video)   showOnlyFragment(R.id.video_fragment);
            else if (checkedId == R.id.tab_message) showOnlyFragment(R.id.message_fragment);
            else if (checkedId == R.id.tab_setting) showOnlyFragment(R.id.setting_fragment);
        });
    }

    private void showOnlyFragment(int visibleId) {
        int[] allFragmentIds = {
            R.id.login_fragment, R.id.numpad_fragment, R.id.video_fragment,
            R.id.message_fragment, R.id.setting_fragment
        };
        FragmentManager fm = getFragmentManager();
        android.app.FragmentTransaction ft = fm.beginTransaction();
        for (int id : allFragmentIds) {
            Fragment f = fm.findFragmentById(id);
            if (f != null) {
                if (id == visibleId) ft.show(f);
                else ft.hide(f);
            }
        }
        ft.commit();
    }

    @Override
    protected void onResume() {
        super.onResume();
        receiver.register(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        receiver.unregister(this);
    }

    // ------------------------------------------------------------------ Permissions

    private void requestRequiredPermissions() {
        List<String> needed = new ArrayList<>();
        String[] required = {
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        };
        for (String p : required) {
            if (ContextCompat.checkSelfPermission(this, p)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(p);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    needed.toArray(new String[0]), PERM_REQUEST_CODE);
        }
    }

    @SuppressLint("BatteryLife")
    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                new AlertDialog.Builder(this)
                    .setTitle("Background Calling")
                    .setMessage("Allow Clevertel VoIP to run unrestricted in the background to receive calls.")
                    .setPositiveButton("Allow", (d, w) -> {
                        startActivity(new Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName())));
                    })
                    .setNegativeButton("Later", null)
                    .show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Log.d(TAG, "Permissions result code=" + requestCode);
    }
}
