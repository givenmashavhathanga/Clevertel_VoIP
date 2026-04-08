package com.portsip.sipsample.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import com.portsip.sipsample.service.PortSipService;

public class PortMessageReceiver extends BroadcastReceiver {

    public interface BroadcastListener {
        void onBroadcastReceiver(Intent intent);
    }

    public BroadcastListener broadcastReceiver;

    private final Context mContext;

    public PortMessageReceiver(Context context) {
        mContext = context;
    }

    public void register(Context context) {
        IntentFilter filter = new IntentFilter();
        filter.addAction(PortSipService.REGISTER_CHANGE_ACTION);
        filter.addAction(PortSipService.CALL_CHANGE_ACTION);
        filter.addAction(PortSipService.PRESENCE_CHANGE_ACTION);
        filter.addAction(PortSipService.ACTION_SIP_AUDIODEVICE);
        context.registerReceiver(this, filter);
    }

    public void unregister(Context context) {
        try {
            context.unregisterReceiver(this);
        } catch (IllegalArgumentException ignored) {}
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (broadcastReceiver != null) {
            broadcastReceiver.onBroadcastReceiver(intent);
        }
    }
}
