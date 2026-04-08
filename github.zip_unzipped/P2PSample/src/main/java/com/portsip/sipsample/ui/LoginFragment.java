package com.portsip.sipsample.ui;

import com.portsip.R;
import com.portsip.sipsample.receiver.PortMessageReceiver;
import com.portsip.sipsample.service.PortSipService;
import com.portsip.sipsample.util.CallManager;

import android.app.Fragment;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import androidx.annotation.Nullable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import static com.portsip.sipsample.service.PortSipService.EXTRA_REGISTER_STATE;

/**
 * Clevertel VoIP - Login screen.
 * SIP domain is statically set to sip.clevertel.co.za.
 * Only username and password are exposed to the user.
 */
public class LoginFragment extends Fragment
        implements View.OnClickListener, PortMessageReceiver.BroadcastListener {

    // Static SIP domain - never changes
    public static final String SIP_DOMAIN = "sip.clevertel.co.za";
    // Default SIP port
    private static final String DEFAULT_SIP_PORT = "5060";

    MyApplication application;
    MainActivity activity;

    private EditText etUsername;
    private EditText etPassword;
    private TextView tvStatus;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();
        application = (MyApplication) activity.getApplicationContext();
        return inflater.inflate(R.layout.login, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvStatus  = view.findViewById(R.id.txtips);
        etUsername = view.findViewById(R.id.etUsername);
        etPassword = view.findViewById(R.id.etPassword);

        loadUserInfo();
        setOnlineStatus(null);

        activity.receiver.broadcastReceiver = this;
        view.findViewById(R.id.btonline).setOnClickListener(this);
        view.findViewById(R.id.btoffline).setOnClickListener(this);
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            activity.receiver.broadcastReceiver = this;
            setOnlineStatus(null);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        activity.receiver.broadcastReceiver = null;
    }

    private void loadUserInfo() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
        etUsername.setText(prefs.getString(PortSipService.USER_NAME, ""));
        // We intentionally do NOT pre-fill password for security
    }

    private void saveUserInfo() {
        String username = etUsername.getText().toString().trim();
        String password = etPassword.getText().toString();

        SharedPreferences.Editor editor =
                PreferenceManager.getDefaultSharedPreferences(getActivity()).edit();

        // Store static domain and credentials
        editor.putString(PortSipService.SVR_HOST, SIP_DOMAIN);
        editor.putString(PortSipService.SVR_PORT, DEFAULT_SIP_PORT);
        editor.putString(PortSipService.USER_NAME, username);
        editor.putString(PortSipService.USER_PASSWORD, password);
        // Auth name defaults to username when not specified separately
        editor.putString(PortSipService.USER_AUTHNAME, username);
        editor.putString(PortSipService.USER_DOMAIN, SIP_DOMAIN);
        editor.apply();
    }

    @Override
    public void onBroadcastReceiver(Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (PortSipService.REGISTER_CHANGE_ACTION.equals(action)) {
            String tips = intent.getStringExtra(EXTRA_REGISTER_STATE);
            setOnlineStatus(tips);
        }
    }

    private void setOnlineStatus(String tips) {
        if (tvStatus == null) return;
        if (CallManager.Instance().isRegistered) {
            tvStatus.setText(TextUtils.isEmpty(tips)
                    ? getString(R.string.online) : tips);
        } else {
            tvStatus.setText(TextUtils.isEmpty(tips)
                    ? getString(R.string.offline) : tips);
        }
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        if (id == R.id.btonline) {
            String username = etUsername.getText().toString().trim();
            String password = etPassword.getText().toString();
            if (TextUtils.isEmpty(username)) {
                Toast.makeText(getActivity(),
                        "Please enter your username", Toast.LENGTH_SHORT).show();
                return;
            }
            if (TextUtils.isEmpty(password)) {
                Toast.makeText(getActivity(),
                        "Please enter your password", Toast.LENGTH_SHORT).show();
                return;
            }
            saveUserInfo();
            Intent onLineIntent = new Intent(getActivity(), PortSipService.class);
            onLineIntent.setAction(PortSipService.ACTION_SIP_REGIEST);
            PortSipService.startServiceCompatibility(getActivity(), onLineIntent);
            tvStatus.setText("Signing in…");

        } else if (id == R.id.btoffline) {
            // Clear auto-reconnect flag so user explicitly signed out
            PreferenceManager.getDefaultSharedPreferences(getActivity())
                    .edit()
                    .putBoolean(PortSipService.PREF_USER_SIGNED_OUT, true)
                    .apply();
            Intent offLineIntent = new Intent(getActivity(), PortSipService.class);
            offLineIntent.setAction(PortSipService.ACTION_SIP_UNREGIEST);
            PortSipService.startServiceCompatibility(getActivity(), offLineIntent);
            tvStatus.setText("Signing out…");
        }
    }
}
