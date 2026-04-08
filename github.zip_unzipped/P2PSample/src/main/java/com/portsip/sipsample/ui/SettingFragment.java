package com.portsip.sipsample.ui;

import com.portsip.R;
import com.portsip.sipsample.receiver.PortMessageReceiver;
import com.portsip.sipsample.service.PortSipService;

import android.app.Fragment;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import androidx.annotation.Nullable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

/**
 * Settings fragment — codecs are pre-configured.
 * Uses the existing setting.xml preference screen.
 */
public class SettingFragment extends Fragment
        implements PortMessageReceiver.BroadcastListener {

    MainActivity activity;
    MyApplication application;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        activity    = (MainActivity) getActivity();
        application = (MyApplication) activity.getApplicationContext();
        View view = inflater.inflate(R.layout.main, container, false);
        // Ensure defaults are applied on first run
        applyDefaultCodecPreferences();
        return view;
    }

    /**
     * Force Clevertel codec defaults: G.729 > G.722 > PCMA > PCMU.
     * All other codecs are disabled by default.
     */
    private void applyDefaultCodecPreferences() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
        // Only apply once
        if (prefs.getBoolean("clevertel_defaults_applied", false)) return;

        SharedPreferences.Editor ed = prefs.edit();

        // Enable only our 4 codecs
        ed.putBoolean(getString(R.string.MEDIA_G729),   true);
        ed.putBoolean(getString(R.string.MEDIA_G722),   true);
        ed.putBoolean(getString(R.string.MEDIA_PCMA),   true);
        ed.putBoolean(getString(R.string.MEDIA_PCMU),   true);

        // Disable all others
        ed.putBoolean(getString(R.string.MEDIA_AMR),     false);
        ed.putBoolean(getString(R.string.MEDIA_AMRWB),   false);
        ed.putBoolean(getString(R.string.MEDIA_GSM),     false);
        ed.putBoolean(getString(R.string.MEDIA_SPEEX),   false);
        ed.putBoolean(getString(R.string.MEDIA_SPEEXWB), false);
        ed.putBoolean(getString(R.string.MEDIA_ILBC),    false);
        ed.putBoolean(getString(R.string.MEDIA_ISACWB),  false);
        ed.putBoolean(getString(R.string.MEDIA_ISACSWB), false);
        ed.putBoolean(getString(R.string.MEDIA_OPUS),    false);

        // Audio features ON
        ed.putBoolean(getString(R.string.MEDIA_AEC),  true);
        ed.putBoolean(getString(R.string.MEDIA_AGC),  true);
        ed.putBoolean(getString(R.string.MEDIA_CNG),  true);
        ed.putBoolean(getString(R.string.MEDIA_VAD),  true);
        ed.putBoolean(getString(R.string.MEDIA_ANS),  false);

        // Video
        ed.putBoolean(getString(R.string.MEDIA_H264), true);
        ed.putBoolean(getString(R.string.MEDIA_VP8),  true);
        ed.putBoolean(getString(R.string.MEDIA_VP9),  false);
        ed.putBoolean(getString(R.string.VIDEO_NACK), true);

        // PRACK (reliable provisional responses / early media) - ON
        ed.putBoolean(getString(R.string.str_pracktitle), true);

        ed.putBoolean("clevertel_defaults_applied", true);
        ed.apply();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            activity.receiver.broadcastReceiver = this;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        activity.receiver.broadcastReceiver = null;
    }

    @Override
    public void onBroadcastReceiver(Intent intent) {}
}
