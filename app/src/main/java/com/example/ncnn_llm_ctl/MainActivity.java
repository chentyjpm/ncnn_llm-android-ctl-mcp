package com.example.ncnn_llm_ctl;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.text.DecimalFormat;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private TextView outputText;
    private Spinner modelSpinner;
    private ProgressBar downloadProgress;
    private TextView downloadStatus;
    private TextView downloadSpeed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        outputText = findViewById(R.id.outputText);
        modelSpinner = findViewById(R.id.modelSpinner);
        downloadProgress = findViewById(R.id.downloadProgress);
        downloadStatus = findViewById(R.id.downloadStatus);
        downloadSpeed = findViewById(R.id.downloadSpeed);

        Button btnOpenSettings = findViewById(R.id.btnOpenSettings);
        Button btnDumpUi = findViewById(R.id.btnDumpUi);
        Button btnStartServer = findViewById(R.id.btnStartServer);
        Button btnGlobalAction = findViewById(R.id.btnGlobalAction);

        setupModelSpinner();

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

        btnStartServer.setOnClickListener(v -> {
            String modelName = (String) modelSpinner.getSelectedItem();
            if (TextUtils.isEmpty(modelName)) {
                toast("请输入模型名称。");
                return;
            }
            toast("开始下载并启动服务…");
            btnStartServer.setEnabled(false);
            downloadProgress.setIndeterminate(true);
            downloadProgress.setProgress(0);
            downloadStatus.setText("下载进度：0%");
            downloadSpeed.setText("下载速度：0 KB/s");
            new Thread(() -> {
                try {
                    ModelDownloader.ProgressListener listener = (file, downloaded, total, speed) -> {
                        runOnUiThread(() -> updateDownloadUi(file, downloaded, total, speed));
                    };
                    java.io.File modelDir = ModelDownloader.ensureModel(
                            getApplicationContext(), modelName, listener);
                    boolean ok = NcnnLlmBridge.startOpenAiServer(
                            modelDir.getAbsolutePath(), 8080, false);
                    runOnUiThread(() -> {
                        if (ok) {
                            toast("服务已启动，端口 8080。");
                        } else {
                            toast("服务启动失败或已在运行。");
                        }
                        btnStartServer.setEnabled(true);
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        toast("启动失败: " + e.getMessage());
                        btnStartServer.setEnabled(true);
                    });
                }
            }).start();
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

    private void setupModelSpinner() {
        String[] models = new String[] {
                "qwen3_0.6b",
                "qwen2.5_0.5b",
                "minicpm4_0.5b",
                "nllb_600m"
        };
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, models);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modelSpinner.setAdapter(adapter);
        modelSpinner.setSelection(0);
    }

    private void updateDownloadUi(String file, long downloaded, long total, long speed) {
        if (total > 0) {
            downloadProgress.setIndeterminate(false);
            int progress = (int) Math.min(1000, (downloaded * 1000L) / total);
            downloadProgress.setProgress(progress);
            downloadStatus.setText("下载中：" + file + " " + (progress / 10.0f) + "%");
        } else {
            downloadProgress.setIndeterminate(true);
            downloadStatus.setText("下载中：" + file);
        }
        downloadSpeed.setText("下载速度：" + formatSpeed(speed));
    }

    private String formatSpeed(long bytesPerSec) {
        if (bytesPerSec <= 0) {
            return "0 KB/s";
        }
        double kb = bytesPerSec / 1024.0;
        if (kb < 1024) {
            return new DecimalFormat("0.0").format(kb) + " KB/s";
        }
        double mb = kb / 1024.0;
        return new DecimalFormat("0.0").format(mb) + " MB/s";
    }
}
