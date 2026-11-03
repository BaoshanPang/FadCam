package com.fadcam.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.remote.RemoteServerService;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.button.MaterialButton;

/**
 * Remote control tab (ported from the FadCam v3 "FadCam Remote" feature and
 * adapted to the v1.3.0 recording pipeline).
 */
public class RemoteFragment extends BaseFragment {

    private SharedPreferencesManager prefs;
    private MaterialSwitch enableSwitch;
    private TextInputEditText portInput;
    private TextInputEditText usernameInput;
    private TextInputEditText passwordInput;
    private TextView statusText;
    private TextView urlText;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_remote, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        prefs = SharedPreferencesManager.getInstance(requireContext());

        MaterialToolbar toolbar = view.findViewById(R.id.topAppBar);
        if (toolbar != null) {
            int colorTopBar = resolveThemeColor(R.attr.colorTopBar);
            toolbar.setBackgroundColor(colorTopBar);
        }

        enableSwitch = view.findViewById(R.id.remote_enable_switch);
        portInput = view.findViewById(R.id.remote_port_input);
        usernameInput = view.findViewById(R.id.remote_username_input);
        passwordInput = view.findViewById(R.id.remote_password_input);
        statusText = view.findViewById(R.id.remote_status);
        urlText = view.findViewById(R.id.remote_url);
        MaterialButton apply = view.findViewById(R.id.remote_apply_button);

        portInput.setText(String.valueOf(prefs.getRemotePort()));
        usernameInput.setText(prefs.getRemoteUsername());
        passwordInput.setText(prefs.getRemotePassword());
        enableSwitch.setChecked(prefs.isRemoteEnabled());

        enableSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.setRemoteEnabled(isChecked);
            if (isChecked) {
                startServer();
            } else {
                stopServer();
            }
            refreshStatus();
        });

        apply.setOnClickListener(v -> applyAndRestart());
        refreshStatus();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void applyAndRestart() {
        int port;
        try {
            port = Integer.parseInt(TextUtils.isEmpty(portInput.getText()) ? "8080" : portInput.getText().toString().trim());
        } catch (NumberFormatException e) {
            Toast.makeText(requireContext(), "Invalid port", Toast.LENGTH_SHORT).show();
            return;
        }
        if (port < 1024 || port > 65535) {
            Toast.makeText(requireContext(), "Port must be 1024-65535", Toast.LENGTH_SHORT).show();
            return;
        }
        String user = usernameInput.getText() == null ? "fadcam" : usernameInput.getText().toString().trim();
        String pass = passwordInput.getText() == null ? "" : passwordInput.getText().toString();
        if (user.isEmpty()) user = "fadcam";

        prefs.setRemotePort(port);
        prefs.setRemoteUsername(user);
        prefs.setRemotePassword(pass);
        prefs.setRemoteEnabled(true);
        enableSwitch.setChecked(true);

        // Just (re)start; the service re-binds the server if the port changed.
        startServer();
        refreshStatus();
        Toast.makeText(requireContext(), "Remote server updated", Toast.LENGTH_SHORT).show();
    }

    private void startServer() {
        Intent intent = new Intent(requireContext(), RemoteServerService.class);
        intent.setAction(RemoteServerService.ACTION_START);
        requireContext().startService(intent);
    }

    private void stopServer() {
        Intent intent = new Intent(requireContext(), RemoteServerService.class);
        intent.setAction(RemoteServerService.ACTION_STOP);
        requireContext().startService(intent);
    }

    private void refreshStatus() {
        if (statusText == null) return;
        boolean enabled = prefs.isRemoteEnabled();
        if (enabled) {
            String ip = RemoteServerService.getLastKnownIp();
            statusText.setText("Server enabled (start recording to enable the live view)");
            urlText.setText("http://" + ip + ":" + prefs.getRemotePort() + "/");
        } else {
            statusText.setText("Remote server is off");
            urlText.setText("");
        }
    }

    private int resolveThemeColor(int attr) {
        android.util.TypedValue typedValue = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(attr, typedValue, true);
        return typedValue.data;
    }
}
