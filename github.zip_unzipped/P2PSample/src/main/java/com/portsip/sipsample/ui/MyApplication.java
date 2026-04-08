package com.portsip.sipsample.ui;

import android.app.Application;
import android.util.Log;
import com.portsip.PortSipSdk;

public class MyApplication extends Application {
    private static final String TAG = "ClevertelApp";

    public PortSipSdk mEngine;
    public boolean    mConference = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Clevertel VoIP starting");
        mEngine = new PortSipSdk();
        mEngine.CreatePortSipSdk(this);
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        if (mEngine != null) {
            mEngine.destroyConference();
            mEngine = null;
        }
    }
}
