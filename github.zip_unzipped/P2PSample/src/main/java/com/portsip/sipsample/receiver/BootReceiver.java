package com.portsip.sipsample.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;

import com.portsip.sipsample.service.PortSipService;

/**
 * Receives BOOT_COMPLETED and auto-starts the SIP service
 * so Clevertel VoIP reconnects after a device reboot.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "ClevertelBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.MY_PACKAGE_REPLACED".equals(action)) {
            return;
        }
        Log.d(TAG, "Boot/update received — checking if we should auto-start");

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String username  = prefs.getString(PortSipService.USER_NAME, "");
        String password  = prefs.getString(PortSipService.USER_PASSWORD, "");
        boolean signedOut = prefs.getBoolean(PortSipService.PREF_USER_SIGNED_OUT, true);

        // Only auto-start if credentials exist and user did not explicitly sign out
        if (!TextUtils.isEmpty(username) && !TextUtils.isEmpty(password) && !signedOut) {
            Log.d(TAG, "Auto-starting Clevertel VoIP service for: " + username);
            Intent serviceIntent = new Intent(context, PortSipService.class);
            serviceIntent.setAction(PortSipService.ACTION_SIP_REGIEST);
            PortSipService.startServiceCompatibility(context, serviceIntent);
        } else {
            Log.d(TAG, "Skipping auto-start (no credentials or user signed out)");
        }
    }
}
