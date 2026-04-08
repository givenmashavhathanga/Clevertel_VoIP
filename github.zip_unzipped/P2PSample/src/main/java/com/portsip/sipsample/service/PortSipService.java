package com.portsip.sipsample.service;

import com.portsip.PortSipEnumDefine;
import com.portsip.PortSipErrorcode;
import com.portsip.PortSipSdk;
import com.portsip.OnPortSIPEvent;
import com.portsip.R;

import com.portsip.sipsample.ui.IncomingActivity;
import com.portsip.sipsample.ui.MainActivity;
import com.portsip.sipsample.ui.MyApplication;
import com.portsip.sipsample.util.CallManager;
import com.portsip.sipsample.util.Contact;
import com.portsip.sipsample.util.ContactManager;
import com.portsip.sipsample.util.NetworkManager;
import com.portsip.sipsample.util.Ring;
import com.portsip.sipsample.util.Session;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.util.Set;
import java.util.UUID;

public class PortSipService extends Service
        implements OnPortSIPEvent, NetworkManager.NetWorkChangeListner {

    private static final String TAG = "ClevertelSIP";

    // Actions
    public static final String ACTION_SIP_REGIEST   = "Clevertel.VoIP.REGISTER";
    public static final String ACTION_SIP_UNREGIEST = "Clevertel.VoIP.UNREGISTER";

    // Preference keys
    public static final String INSTANCE_ID        = "instanceid";
    public static final String SVR_HOST           = "svr host";
    public static final String SVR_PORT           = "svr port";
    public static final String USER_NAME          = "user name";
    public static final String USER_PASSWORD      = "user password";
    public static final String USER_DOMAIN        = "user domain";
    public static final String USER_AUTHNAME      = "user authname";
    public static final String STUN_HOST          = "stun host";
    public static final String STUN_PORT          = "stun port";
    public static final String TRANS              = "trans type";
    public static final String SRTP               = "srtp type";
    public static final String PREF_USER_SIGNED_OUT = "user_signed_out";

    // Static SIP domain
    public static final String SIP_DOMAIN = "sip.clevertel.co.za";

    // Broadcast actions
    public static final String ACTION_SIP_AUDIODEVICE   = "Clevertel.VoIP.AudioDeviceUpdate";
    public static final String REGISTER_CHANGE_ACTION   = "Clevertel.VoIP.RegisterStatusChange";
    public static final String CALL_CHANGE_ACTION       = "Clevertel.VoIP.CallStatusChange";
    public static final String PRESENCE_CHANGE_ACTION   = "Clevertel.VoIP.PresenceStatusChange";

    public static String EXTRA_REGISTER_STATE  = "RegisterStatus";
    public static String EXTRA_CALL_SEESIONID  = "SessionID";
    public static String EXTRA_CALL_DESCRIPTION = "Description";

    private static final int  SERVICE_NOTIFICATION = 31414;
    private static final String CHANNEL_ID         = "ClevertelVoIPService";

    // Auto-reconnect delay: 30 seconds
    private static final long RECONNECT_DELAY_MS = 30_000L;
    private static final int  MAX_RECONNECT_ATTEMPTS = 10;
    private int reconnectAttempts = 0;

    protected PowerManager.WakeLock mCpuLock;
    private PortSipSdk mEngine;
    private MyApplication application;
    private Handler reconnectHandler;
    private Runnable reconnectRunnable;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        application   = (MyApplication) getApplicationContext();
        mEngine       = application.mEngine;
        reconnectHandler = new Handler(Looper.getMainLooper());

        createNotificationChannel();
        showServiceNotification();

        if (NetworkManager.getNetWorkmanager().start(this)) {
            NetworkManager.getNetWorkmanager().setNetWorkChangeListner(this);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_HIGH);
            ch.enableLights(true);
            ch.enableVibration(true);
            ch.setShowBadge(true);
            nm.createNotificationChannel(ch);
        }
    }

    private void showServiceNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = buildNotificationBuilder();
        builder.setSmallIcon(R.drawable.logo)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.service_running))
                .setContentIntent(pi)
                .setOngoing(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(SERVICE_NOTIFICATION, builder.build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(SERVICE_NOTIFICATION, builder.build());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        if (intent != null) {
            if (ACTION_SIP_REGIEST.equals(intent.getAction())
                    && !CallManager.Instance().isRegistered) {
                // Clear the signed-out flag when user explicitly signs in
                PreferenceManager.getDefaultSharedPreferences(this)
                        .edit().putBoolean(PREF_USER_SIGNED_OUT, false).apply();
                registerToServer();
            } else if (ACTION_SIP_UNREGIEST.equals(intent.getAction())) {
                cancelReconnect();
                unregisterToServer();
            }
        }
        // START_STICKY so Android restarts the service if killed
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cancelReconnect();
        if (mEngine != null) {
            mEngine.destroyConference();
            mEngine = null;
            application.mEngine = null;
        }
        if (mCpuLock != null) mCpuLock.release();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.cancelAll();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.deleteNotificationChannel(CHANNEL_ID);
        }
        NetworkManager.getNetWorkmanager().stop();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ------------------------------------------------------------------ SIP registration

    public void registerToServer() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        String username  = prefs.getString(USER_NAME, "");
        String password  = prefs.getString(USER_PASSWORD, "");
        String authName  = prefs.getString(USER_AUTHNAME, username);
        int    srtpType  = prefs.getInt(SRTP, 0);

        if (TextUtils.isEmpty(username) || TextUtils.isEmpty(password)) {
            Log.w(TAG, "registerToServer: username or password empty, skipping");
            return;
        }

        if (mEngine == null) {
            application.mEngine = new PortSipSdk();
            application.mEngine.CreatePortSipSdk(this);
            mEngine = application.mEngine;
        }

        mEngine.setOnPortSIPEvent(this);
        String dataPath  = getExternalFilesDir(null).getAbsolutePath();
        String certRoot  = dataPath + "/certs";

        // Initialize SDK on port 5060 UDP
        int result = mEngine.initialize(
                PortSipEnumDefine.ENUM_TRANSPORT_UDP,
                "0.0.0.0",
                5060,
                PortSipEnumDefine.ENUM_LOG_LEVEL_DEBUG,
                dataPath,
                8,
                "Clevertel VoIP",
                0, 0,
                certRoot, "", false, null);

        if (result != PortSipErrorcode.ECoreErrorNone) {
            Log.e(TAG, "SDK initialize failed: " + result);
            onRegisterFailure("Init failed", result, "");
            return;
        }

        // License key (keep original placeholder)
        mEngine.setLicenseKey("LicenseKey");

        // ---- Features ON by default ----
        mEngine.enableAudioManager(true);
        mEngine.setVideoDeviceId(1);
        mEngine.setSrtpPolicy(srtpType, false);

        // PRACK / reliable provisional (early media) - always ON for Clevertel
        // 0=disabled, 1=supported, 2=required
        boolean prackEnabled = prefs.getBoolean(getString(R.string.str_pracktitle), true);
        mEngine.setReliableProvisional(prackEnabled ? 2 : 0);

        // Intercom speaker mode
        mEngine.enableIntercomSpeaker(true);

        // 3GPP tags off (cleaner SDP)
        mEngine.enable3GppTags(false);

        // ---- Codecs: G.729 > G.722 > PCMA > PCMU (in priority order) ----
        mEngine.clearAudioCodec();
        mEngine.addAudioCodec(PortSipEnumDefine.ENUM_AUDIOCODEC_G729);
        mEngine.addAudioCodec(PortSipEnumDefine.ENUM_AUDIOCODEC_G722);
        mEngine.addAudioCodec(PortSipEnumDefine.ENUM_AUDIOCODEC_PCMA);
        mEngine.addAudioCodec(PortSipEnumDefine.ENUM_AUDIOCODEC_PCMU);

        // DTMF always useful
        mEngine.addAudioCodec(PortSipEnumDefine.ENUM_AUDIOCODEC_DTMF);

        // Audio quality features
        mEngine.enableAEC(true);
        mEngine.enableAGC(true);
        mEngine.enableCNG(true);
        mEngine.enableVAD(true);
        mEngine.enableANS(false);

        // Apply other preferences (video codecs etc.)
        applyMediaPreferences(prefs);

        // ---- Set user against static SIP domain ----
        result = mEngine.setUser(
                username,          // display name
                username,          // user name (SIP username)
                authName,          // auth name
                password,          // password
                SIP_DOMAIN,        // SIP domain
                SIP_DOMAIN,        // SIP server (same as domain)
                5060,              // SIP server port
                null, 0,           // outbound proxy
                null, 0);          // STUN

        if (result != PortSipErrorcode.ECoreErrorNone) {
            Log.e(TAG, "setUser failed: " + result);
            onRegisterFailure("Login failed", result, "");
            return;
        }

        mEngine.setInstanceId(getInstanceId());

        // Kick off registration
        result = mEngine.registerServer(3600, 0);
        if (result != PortSipErrorcode.ECoreErrorNone) {
            Log.e(TAG, "registerServer failed: " + result);
            onRegisterFailure("Register failed", result, "");
        }
    }

    /** Apply video codec prefs and misc settings (called after codec setup). */
    private void applyMediaPreferences(SharedPreferences prefs) {
        // Video codecs
        mEngine.clearVideoCodec();
        if (prefs.getBoolean(getString(R.string.MEDIA_H264), true))
            mEngine.addVideoCodec(PortSipEnumDefine.ENUM_VIDEOCODEC_H264);
        if (prefs.getBoolean(getString(R.string.MEDIA_VP8), true))
            mEngine.addVideoCodec(PortSipEnumDefine.ENUM_VIDEOCODEC_VP8);
        if (prefs.getBoolean(getString(R.string.MEDIA_VP9), false))
            mEngine.addVideoCodec(PortSipEnumDefine.ENUM_VIDEOCODEC_VP9);

        mEngine.setVideoNackStatus(prefs.getBoolean(getString(R.string.VIDEO_NACK), true));

        // Call forward if configured
        boolean fwd = prefs.getBoolean(getString(R.string.str_fwopenkey), false);
        String  fwTo = prefs.getString(getString(R.string.str_fwtokey), null);
        boolean fwBusy = prefs.getBoolean(getString(R.string.str_fwbusykey), false);
        if (fwd && !TextUtils.isEmpty(fwTo)) {
            mEngine.enableCallForward(fwBusy, fwTo);
        }
    }

    public void unregisterToServer() {
        if (CallManager.Instance().isRegistered) {
            mEngine.unRegisterServer(100);
            mEngine.removeUser();
            mEngine.unInitialize();
            CallManager.Instance().isRegistered = false;
        }
        onRegisterFailure("Signed out", PortSipErrorcode.ECoreErrorNone, "");
    }

    // ------------------------------------------------------------------ Auto-reconnect

    private void scheduleReconnect() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean userSignedOut = prefs.getBoolean(PREF_USER_SIGNED_OUT, false);
        if (userSignedOut) {
            Log.d(TAG, "User signed out intentionally — no reconnect");
            return;
        }
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached");
            reconnectAttempts = 0;
            // Reset and try again after longer delay
            reconnectHandler.postDelayed(this::scheduleReconnect, 120_000L);
            return;
        }
        reconnectAttempts++;
        Log.d(TAG, "Scheduling reconnect attempt " + reconnectAttempts
                + " in " + (RECONNECT_DELAY_MS / 1000) + "s");

        cancelReconnect();
        reconnectRunnable = () -> {
            Log.d(TAG, "Auto-reconnect attempt " + reconnectAttempts);
            if (!CallManager.Instance().isRegistered) {
                registerToServer();
            }
        };
        reconnectHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS);

        // Update status
        Intent broadIntent = new Intent(REGISTER_CHANGE_ACTION);
        broadIntent.putExtra(EXTRA_REGISTER_STATE,
                getString(R.string.str_reconnecting));
        sendBroadcast(broadIntent);
    }

    private void cancelReconnect() {
        if (reconnectRunnable != null) {
            reconnectHandler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
    }

    // ------------------------------------------------------------------ SIP callbacks

    @Override
    public void onRegisterSuccess(String statusText, int statusCode, String sipMessage) {
        Log.d(TAG, "onRegisterSuccess: " + statusText);
        reconnectAttempts = 0;
        cancelReconnect();
        CallManager.Instance().isRegistered = true;
        keepCpuRun(true);
        Intent broadIntent = new Intent(REGISTER_CHANGE_ACTION);
        broadIntent.putExtra(EXTRA_REGISTER_STATE, getString(R.string.online));
        sendBroadcast(broadIntent);
    }

    @Override
    public void onRegisterFailure(String statusText, int statusCode, String sipMessage) {
        Log.d(TAG, "onRegisterFailure: " + statusText + " code=" + statusCode);
        CallManager.Instance().isRegistered = false;
        CallManager.Instance().resetAll();
        keepCpuRun(false);
        Intent broadIntent = new Intent(REGISTER_CHANGE_ACTION);
        broadIntent.putExtra(EXTRA_REGISTER_STATE, statusText);
        sendBroadcast(broadIntent);
        // Auto-reconnect unless user deliberately signed out
        scheduleReconnect();
    }

    @Override
    public void onInviteIncoming(long sessionId,
                                 String callerDisplayName, String caller,
                                 String calleeDisplayName, String callee,
                                 String audioCodecNames, String videoCodecNames,
                                 boolean existsAudio, boolean existsVideo,
                                 String sipMessage) {
        if (CallManager.Instance().findIncomingCall() != null) {
            application.mEngine.rejectCall(sessionId, 486);
            return;
        }
        Session session = CallManager.Instance().findIdleSession();
        session.state       = Session.CALL_STATE_FLAG.INCOMING;
        session.hasVideo    = existsVideo;
        session.sessionID   = sessionId;
        session.remote      = caller;
        session.displayName = callerDisplayName;

        // Launch full-screen incoming call activity
        Intent activityIntent = new Intent(this, IncomingActivity.class);
        activityIntent.putExtra("incomingSession", sessionId);
        activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        startActivity(activityIntent);

        // High-priority notification for background
        showIncomingCallNotification(callerDisplayName, caller, sessionId);

        Intent broadIntent = new Intent(CALL_CHANGE_ACTION);
        broadIntent.putExtra(EXTRA_CALL_SEESIONID, sessionId);
        broadIntent.putExtra(EXTRA_CALL_DESCRIPTION, "Incoming call from " + callerDisplayName);
        sendBroadcast(broadIntent);

        Ring.getInstance(this).startRingTone();

        // Vibrate for background notification
        vibrateDevice();
    }

    private void showIncomingCallNotification(String displayName, String caller, long sessionId) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        Intent fullScreenIntent = new Intent(this, IncomingActivity.class);
        fullScreenIntent.putExtra("incomingSession", sessionId);
        fullScreenIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent fullScreenPi = PendingIntent.getActivity(
                this, (int) sessionId, fullScreenIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = buildNotificationBuilder();
        builder.setSmallIcon(R.drawable.logo)
                .setContentTitle(getString(R.string.notification_call_title))
                .setContentText(TextUtils.isEmpty(displayName) ? caller : displayName)
                .setFullScreenIntent(fullScreenPi, true)
                .setPriority(Notification.PRIORITY_MAX)
                .setCategory(Notification.CATEGORY_CALL)
                .setAutoCancel(false)
                .setOngoing(true);

        nm.notify(SERVICE_NOTIFICATION + 1, builder.build());
    }

    private void vibrateDevice() {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            long[] pattern = {0, 500, 500, 500, 500};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, 0));
            } else {
                v.vibrate(pattern, 0);
            }
        } catch (Exception e) {
            Log.w(TAG, "Vibrate failed: " + e.getMessage());
        }
    }

    @Override
    public void onInviteTrying(long sessionId) {}

    @Override
    public void onInviteSessionProgress(long sessionId,
                                        String audioCodecNames, String videoCodecNames,
                                        boolean existsEarlyMedia,
                                        boolean existsAudio, boolean existsVideo,
                                        String sipMessage) {
        // Early media is handled automatically by SDK when setReliableProvisional(2) is set
    }

    @Override
    public void onInviteRinging(long sessionId, String statusText,
                                int statusCode, String sipMessage) {}

    @Override
    public void onInviteAnswered(long sessionId,
                                 String callerDisplayName, String caller,
                                 String calleeDisplayName, String callee,
                                 String audioCodecNames, String videoCodecNames,
                                 boolean existsAudio, boolean existsVideo,
                                 String sipMessage) {
        Session session = CallManager.Instance().findSessionBySessionID(sessionId);
        if (session != null) {
            session.state    = Session.CALL_STATE_FLAG.CONNECTED;
            session.hasVideo = existsVideo;
            Intent broadIntent = new Intent(CALL_CHANGE_ACTION);
            broadIntent.putExtra(EXTRA_CALL_SEESIONID, sessionId);
            broadIntent.putExtra(EXTRA_CALL_DESCRIPTION, session.lineName + " answered");
            sendBroadcast(broadIntent);
        }
        Ring.getInstance(this).stopRingBackTone();
        stopVibration();
    }

    private void stopVibration() {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) v.cancel();
        } catch (Exception ignored) {}
    }

    @Override
    public void onInviteFailure(long sessionId, String callerDisplayName,
                                String caller, String calleeDisplayName, String callee,
                                String reason, int code, String sipMessage) {
        Session session = CallManager.Instance().findSessionBySessionID(sessionId);
        if (session != null) {
            session.state     = Session.CALL_STATE_FLAG.FAILED;
            session.sessionID = sessionId;
            Intent broadIntent = new Intent(CALL_CHANGE_ACTION);
            broadIntent.putExtra(EXTRA_CALL_SEESIONID, sessionId);
            broadIntent.putExtra(EXTRA_CALL_DESCRIPTION, session.lineName + " failed");
            sendBroadcast(broadIntent);
        }
        Ring.getInstance(this).stopRingBackTone();
        stopVibration();
    }

    @Override
    public void onInviteUpdated(long sessionId, String audioCodecs, String videoCodecs,
                                String screenCodecs, boolean existsAudio,
                                boolean existsVideo, boolean existsScreen,
                                String sipMessage) {
        Session session = CallManager.Instance().findSessionBySessionID(sessionId);
        if (session != null) {
            session.state    = Session.CALL_STATE_FLAG.CONNECTED;
            session.hasVideo = existsVideo;
            Intent broadIntent = new Intent(CALL_CHANGE_ACTION);
            broadIntent.putExtra(EXTRA_CALL_SEESIONID, sessionId);
            sendBroadcast(broadIntent);
        }
    }

    @Override
    public void onInviteConnected(long sessionId) {
        Session session = CallManager.Instance().findSessionBySessionID(sessionId);
        if (session != null) {
            session.state     = Session.CALL_STATE_FLAG.CONNECTED;
            session.sessionID = sessionId;
            if (application.mConference) {
                application.mEngine.joinToConference(session.sessionID);
            }
            Intent broadIntent = new Intent(CALL_CHANGE_ACTION);
            broadIntent.putExtra(EXTRA_CALL_SEESIONID, sessionId);
            broadIntent.putExtra(EXTRA_CALL_DESCRIPTION, session.lineName + " connected");
            sendBroadcast(broadIntent);
        }
    }

    @Override
    public void onInviteBeginingForward(String forwardTo) {}

    @Override
    public void onInviteClosed(long sessionId, String sipMessage) {
        Session session = CallManager.Instance().findSessionBySessionID(sessionId);
        if (session != null) {
            session.state     = Session.CALL_STATE_FLAG.CLOSED;
            session.sessionID = sessionId;
            Intent broadIntent = new Intent(CALL_CHANGE_ACTION);
            broadIntent.putExtra(EXTRA_CALL_SEESIONID, sessionId);
            broadIntent.putExtra(EXTRA_CALL_DESCRIPTION, session.lineName + " closed");
            sendBroadcast(broadIntent);
        }
        Ring.getInstance(this).stopRingTone();
        stopVibration();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.cancel(SERVICE_NOTIFICATION + 1);
    }

    @Override
    public void onDialogStateUpdated(String BLFMonitoredUri, String BLFDialogState,
                                     String BLFDialogId, String BLFDialogDirection) {}

    @Override
    public void onRemoteUnHold(long sessionId, String audioCodecNames,
                               String videoCodecNames, boolean existsAudio,
                               boolean existsVideo) {}

    @Override
    public void onRemoteHold(long sessionId) {}

    @Override
    public void onReceivedRefer(long sessionId, long referId, String to,
                                String referFrom, String referSipMessage) {}

    @Override
    public void onReferAccepted(long sessionId) {
        Session session = CallManager.Instance().findSessionBySessionID(sessionId);
        if (session != null) {
            session.state = Session.CALL_STATE_FLAG.CLOSED;
            Intent broadIntent = new Intent(CALL_CHANGE_ACTION);
            broadIntent.putExtra(EXTRA_CALL_SEESIONID, sessionId);
            broadIntent.putExtra(EXTRA_CALL_DESCRIPTION, "Transfer accepted");
            sendBroadcast(broadIntent);
        }
        Ring.getInstance(this).stopRingTone();
    }

    @Override public void onReferRejected(long sessionId, String reason, int code) {}
    @Override public void onTransferTrying(long sessionId) {}
    @Override public void onTransferRinging(long sessionId) {}

    @Override
    public void onACTVTransferSuccess(long sessionId) {
        Session session = CallManager.Instance().findSessionBySessionID(sessionId);
        if (session != null) {
            session.state = Session.CALL_STATE_FLAG.CLOSED;
            mEngine.hangUp(sessionId);
            Intent broadIntent = new Intent(CALL_CHANGE_ACTION);
            broadIntent.putExtra(EXTRA_CALL_SEESIONID, sessionId);
            broadIntent.putExtra(EXTRA_CALL_DESCRIPTION, "Transfer succeeded");
            sendBroadcast(broadIntent);
        }
    }

    @Override
    public void onACTVTransferFailure(long sessionId, String reason, int code) {
        Session session = CallManager.Instance().findSessionBySessionID(sessionId);
        if (session != null) {
            Intent broadIntent = new Intent(CALL_CHANGE_ACTION);
            broadIntent.putExtra(EXTRA_CALL_SEESIONID, sessionId);
            broadIntent.putExtra(EXTRA_CALL_DESCRIPTION, "Transfer failed");
            sendBroadcast(broadIntent);
        }
    }

    @Override public void onReceivedSignaling(long sessionId, String signaling) {}
    @Override public void onSendingSignaling(long sessionId, String signaling) {}
    @Override public void onWaitingVoiceMessage(String messageAccount, int urgentNew,
        int urgentOld, int newMsg, int oldMsg) {}
    @Override public void onWaitingFaxMessage(String messageAccount, int urgentNew,
        int urgentOld, int newMsg, int oldMsg) {}
    @Override public void onRecvDtmfTone(long sessionId, int tone) {}
    @Override public void onRecvOptions(String optionsMessage) {}
    @Override public void onRecvInfo(String infoMessage) {}
    @Override public void onRecvNotifyOfSubscription(long sessionId, String notifyMessage,
        byte[] messageData, int messageDataLength) {}

    @Override
    public void onPresenceRecvSubscribe(long subscribeId, String fromDisplayName,
                                        String from, String subject) {
        Contact contact = ContactManager.Instance().findContactBySipAddr(from);
        if (contact == null) {
            contact = new Contact();
            contact.sipAddr = from;
            ContactManager.Instance().AddContact(contact);
        }
        contact.subRequestDescription = subject;
        contact.SubId = subscribeId;
        switch (contact.state) {
            case ACCEPTED:
                application.mEngine.presenceAcceptSubscribe(subscribeId); break;
            case REJECTED:
                application.mEngine.presenceRejectSubscribe(subscribeId); break;
            case UNSUBSCRIBE:
                contact.state = Contact.SUBSCRIBE_STATE_FLAG.UNSETTLLED; break;
            default: break;
        }
        sendBroadcast(new Intent(PRESENCE_CHANGE_ACTION));
    }

    @Override
    public void onPresenceOnline(String fromDisplayName, String from, String stateText) {
        Contact c = ContactManager.Instance().findContactBySipAddr(from);
        if (c != null) c.subDescription = stateText;
        sendBroadcast(new Intent(PRESENCE_CHANGE_ACTION));
    }

    @Override
    public void onPresenceOffline(String fromDisplayName, String from) {
        Contact c = ContactManager.Instance().findContactBySipAddr(from);
        if (c != null) c.subDescription = "Offline";
        sendBroadcast(new Intent(PRESENCE_CHANGE_ACTION));
    }

    @Override public void onRecvMessage(long sessionId, String mimeType, String subMimeType,
        byte[] messageData, int messageDataLength) {}

    @Override
    public void onRecvOutOfDialogMessage(String fromDisplayName, String from,
                                         String toDisplayName, String to,
                                         String mimeType, String subMimeType,
                                         byte[] messageData, int messageDataLength,
                                         String sipMessage) {
        if ("text".equals(mimeType) && "plain".equals(subMimeType)) {
            Toast.makeText(this,
                    "Message from " + from + ": " + new String(messageData),
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void onSendMessageSuccess(long sessionId, long messageId, String sipMessage) {}
    @Override public void onSendMessageFailure(long sessionId, long messageId, String reason,
        int code, String sipMessage) {}
    @Override public void onSendOutOfDialogMessageSuccess(long messageId, String fromDisplayName,
        String from, String toDisplayName, String to, String sipMessage) {}
    @Override public void onSendOutOfDialogMessageFailure(long messageId, String fromDisplayName,
        String from, String toDisplayName, String to, String reason, int code,
        String sipMessage) {}
    @Override public void onSubscriptionFailure(long subscribeId, int statusCode) {}
    @Override public void onSubscriptionTerminated(long subscribeId) {}
    @Override public void onPlayFileFinished(long sessionId, String fileName) {}
    @Override public void onStatistics(long sessionId, String statistics) {}
    @Override public void onRTPPacketCallback(long sessionId, int mediaType,
        int enum_direction, byte[] RTPPacket, int packetSize) {}
    @Override public void onAudioRawCallback(long sessionId, int callbackType,
        byte[] data, int dataLength, int samplingFreqHz) {}
    @Override public void onVideoRawCallback(long l, int i, int i1, int i2,
        byte[] bytes, int i3) {}

    // ------------------------------------------------------------------ Network

    @Override
    public void handleNetworkChangeEvent(boolean ethernet, boolean wifiConnect,
                                         boolean mobileConnect, boolean netTypeChange) {
        Log.d(TAG, "Network change — wifi=" + wifiConnect + " mobile=" + mobileConnect);
        if ((wifiConnect || mobileConnect) && !CallManager.Instance().isRegistered) {
            // Network came back — try to re-register
            reconnectAttempts = 0;
            cancelReconnect();
            registerToServer();
        } else if (mEngine != null) {
            mEngine.refreshRegistration(0);
        }
    }

    // ------------------------------------------------------------------ Audio device

    @Override
    public void onAudioDeviceChanged(PortSipEnumDefine.AudioDevice audioDevice,
                                     Set<PortSipEnumDefine.AudioDevice> set) {
        CallManager.Instance().setSelectalbeAudioDevice(audioDevice, set);
        sendBroadcast(new Intent(ACTION_SIP_AUDIODEVICE));
    }

    @Override public void onAudioFocusChange(int focusChange) {}

    // ------------------------------------------------------------------ Notification helper

    public void sendPortSipMessage(String message, Intent broadIntent) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = buildNotificationBuilder()
                .setSmallIcon(R.drawable.logo)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(message)
                .setContentIntent(pi)
                .build();

        nm.notify(1, notification);
        sendBroadcast(broadIntent);
    }

    private Notification.Builder buildNotificationBuilder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID);
        }
        return new Notification.Builder(this);
    }

    // ------------------------------------------------------------------ Helpers

    public void keepCpuRun(boolean keepRun) {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (keepRun) {
            if (mCpuLock == null) {
                mCpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        getPackageName() + ":ClevertelCpuLock");
                mCpuLock.setReferenceCounted(false);
            }
            if (!mCpuLock.isHeld()) mCpuLock.acquire();
        } else {
            if (mCpuLock != null && mCpuLock.isHeld()) mCpuLock.release();
        }
    }

    String getInstanceId() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String id = prefs.getString(INSTANCE_ID, "");
        if (TextUtils.isEmpty(id)) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(INSTANCE_ID, id).apply();
        }
        return id;
    }

    public static void startServiceCompatibility(@NonNull Context context,
                                                  @NonNull Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }
}
