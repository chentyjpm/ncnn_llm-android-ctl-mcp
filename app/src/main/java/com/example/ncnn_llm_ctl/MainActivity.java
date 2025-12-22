package com.example.ncnn_llm_ctl;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private TextView outputText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        outputText = findViewById(R.id.outputText);

        Button btnOpenSettings = findViewById(R.id.btnOpenSettings);
        Button btnDumpUi = findViewById(R.id.btnDumpUi);
        Button btnGlobalAction = findViewById(R.id.btnGlobalAction);

        btnOpenSettings.setOnClickListener(v -> openAccessibilitySettings());

        btnDumpUi.setOnClickListener(v -> {
            AccessCtlService service = requireService();
            if (service == null) {
                return;
            }
            String dump = service.getCurrentUiDump();
            if (TextUtils.isEmpty(dump)) {
                toast("未获取到屏幕内容。");
            } else {
                outputText.setText(dump);
            }
        });

        btnGlobalAction.setOnClickListener(v -> {
            AccessCtlService service = requireService();
            if (service == null) {
                return;
            }
            showGlobalActionDialog(service);
        });
    }

    private void openAccessibilitySettings() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivity(intent);
    }

    private AccessCtlService requireService() {
        if (!isServiceEnabled()) {
            toast("请先开启无障碍服务。");
            return null;
        }
        AccessCtlService service = AccessCtlService.getInstance();
        if (service == null) {
            toast("服务尚未连接。");
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

    private void showGlobalActionDialog(AccessCtlService service) {
        List<String> names = service.getGlobalActionNames();
        if (names == null || names.isEmpty()) {
            toast("无可用的全局动作。");
            return;
        }
        String[] items = names.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("选择全局动作")
                .setItems(items, (dialog, which) -> {
                    String name = service.performGlobalActionByIndex(which);
                    if (TextUtils.isEmpty(name)) {
                        toast("全局动作执行失败。");
                    } else {
                        toast("已执行: " + name);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
