package com.example.ncnn_llm_ctl;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private EditText inputX;
    private EditText inputY;
    private EditText inputText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        inputX = findViewById(R.id.inputX);
        inputY = findViewById(R.id.inputY);
        inputText = findViewById(R.id.inputText);

        Button btnOpenSettings = findViewById(R.id.btnOpenSettings);
        Button btnTap = findViewById(R.id.btnTap);
        Button btnInputText = findViewById(R.id.btnInputText);

        btnOpenSettings.setOnClickListener(v -> openAccessibilitySettings());

        btnTap.setOnClickListener(v -> {
            String xText = inputX.getText().toString().trim();
            String yText = inputY.getText().toString().trim();
            if (TextUtils.isEmpty(xText) || TextUtils.isEmpty(yText)) {
                toast("Please input X and Y.");
                return;
            }

            int x;
            int y;
            try {
                x = Integer.parseInt(xText);
                y = Integer.parseInt(yText);
            } catch (NumberFormatException e) {
                toast("Invalid coordinates.");
                return;
            }

            AccessCtlService service = requireService();
            if (service == null) {
                return;
            }

            boolean ok = service.clickAt(x, y);
            if (!ok) {
                toast("Tap failed. Ensure the service is enabled.");
            }
        });

        btnInputText.setOnClickListener(v -> {
            String text = inputText.getText().toString();
            if (TextUtils.isEmpty(text)) {
                toast("Please input text.");
                return;
            }

            AccessCtlService service = requireService();
            if (service == null) {
                return;
            }

            boolean ok = service.inputText(text);
            if (!ok) {
                toast("Input failed. Focus a text field first.");
            }
        });
    }

    private void openAccessibilitySettings() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivity(intent);
    }

    private AccessCtlService requireService() {
        if (!isServiceEnabled()) {
            toast("Enable the accessibility service first.");
            return null;
        }
        AccessCtlService service = AccessCtlService.getInstance();
        if (service == null) {
            toast("Service not connected yet.");
        }
        return service;
    }

    private boolean isServiceEnabled() {
        AccessibilityManager manager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (manager == null) {
            return false;
        }
        String id = getPackageName() + "/.AccessCtlService";
        List<AccessibilityServiceInfo> list = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo info : list) {
            if (id.equals(info.getId())) {
                return true;
            }
        }
        return false;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}