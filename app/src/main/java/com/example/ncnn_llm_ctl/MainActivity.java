package com.example.ncnn_llm_ctl;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String LOCAL_SERVER_BASE_URL = "http://127.0.0.1:18080";

    private TextView outputText;
    private Spinner modelSpinner;
    private ProgressBar downloadProgress;
    private TextView downloadStatus;
    private TextView downloadSpeed;
    private EditText chatInput;

    private final List<JSONObject> chatMessages = new ArrayList<>();
    private final AtomicBoolean serverStarting = new AtomicBoolean(false);
    private boolean serverStarted = false;

    private Button btnOpenSettings;
    private Button btnStartServer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        outputText = findViewById(R.id.outputText);
        modelSpinner = findViewById(R.id.modelSpinner);
        downloadProgress = findViewById(R.id.downloadProgress);
        downloadStatus = findViewById(R.id.downloadStatus);
        downloadSpeed = findViewById(R.id.downloadSpeed);
        chatInput = findViewById(R.id.chatInput);

        btnOpenSettings = findViewById(R.id.btnOpenSettings);
        Button btnDumpUi = findViewById(R.id.btnDumpUi);
        btnStartServer = findViewById(R.id.btnStartServer);
        Button btnGlobalAction = findViewById(R.id.btnGlobalAction);
        Button btnSendChat = findViewById(R.id.btnSendChat);

        setupModelSpinner();
        setDownloadViewsVisible(false);
        refreshAccessibilityButton();
        refreshServerButton();

        if (chatInput != null && TextUtils.isEmpty(chatInput.getText())) {
            chatInput.setText("1. 调用工具先抓取当前屏幕的UI\n2. 在UI里找到发送按钮并点击\n3. 把调用工具的过程打印在下面");
        }

        btnOpenSettings.setOnClickListener(v -> {
            if (isServiceEnabled()) {
                refreshAccessibilityButton();
                return;
            }
            openAccessibilitySettings();
        });

        btnDumpUi.setOnClickListener(v -> {
            AccessCtlService service = requireService();
            if (service == null) {
                return;
            }
            Log.i(TAG, "Dump UI requested");
            String dump = service.getCurrentUiDump();
            if (TextUtils.isEmpty(dump)) {
                toast("未获取到屏幕内容。");
            } else {
                outputText.setText(dump);
            }
        });

        btnStartServer.setOnClickListener(v -> startServerWithDownload());

        btnGlobalAction.setOnClickListener(v -> {
            AccessCtlService service = requireService();
            if (service == null) {
                return;
            }
            showGlobalActionDialog(service);
        });

        btnSendChat.setOnClickListener(v -> sendChatOnce());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAccessibilityButton();
    }

    private void refreshAccessibilityButton() {
        if (btnOpenSettings == null) {
            return;
        }
        if (isServiceEnabled()) {
            btnOpenSettings.setText("无障碍已开启");
            btnOpenSettings.setEnabled(false);
        } else {
            btnOpenSettings.setText("开启无障碍");
            btnOpenSettings.setEnabled(true);
        }
    }

    private void refreshServerButton() {
        if (btnStartServer == null) {
            return;
        }
        if (serverStarted) {
            btnStartServer.setText("模型服务已启动");
            btnStartServer.setEnabled(false);
        } else {
            btnStartServer.setText("启动模型服务");
            btnStartServer.setEnabled(true);
        }
    }

    private void setDownloadViewsVisible(boolean visible) {
        int v = visible ? android.view.View.VISIBLE : android.view.View.GONE;
        if (downloadProgress != null) downloadProgress.setVisibility(v);
        if (downloadStatus != null) downloadStatus.setVisibility(v);
        if (downloadSpeed != null) downloadSpeed.setVisibility(v);
    }

    private void startServerWithDownload() {
        if (serverStarted) {
            toast("模型服务已启动。");
            refreshServerButton();
            return;
        }
        if (!serverStarting.compareAndSet(false, true)) {
            toast("正在启动中…");
            return;
        }

        String modelName = (String) modelSpinner.getSelectedItem();
        Log.i(TAG, "Start server clicked, modelName=" + modelName);
        if (TextUtils.isEmpty(modelName)) {
            toast("请选择模型。");
            serverStarting.set(false);
            return;
        }

        toast("开始下载并启动服务…");
        btnStartServer.setEnabled(false);
        btnStartServer.setText("启动中…");
        setDownloadViewsVisible(true);
        downloadProgress.setIndeterminate(true);
        downloadProgress.setProgress(0);
        downloadStatus.setText("下载进度：0%");
        downloadSpeed.setText("下载速度：0 KB/s");

        new Thread(() -> {
            try {
                ModelDownloader.ProgressListener listener = (file, downloaded, total, speed) ->
                        runOnUiThread(() -> updateDownloadUi(file, downloaded, total, speed));

                java.io.File modelDir = ModelDownloader.ensureModel(getApplicationContext(), modelName, listener);
                Log.i(TAG, "Model ready at " + modelDir.getAbsolutePath());
                runOnUiThread(() -> setDownloadViewsVisible(false));

                java.io.File webRoot = WebAssetDeployer.ensureWebRoot(getApplicationContext());
                Log.i(TAG, "Web root ready at " + webRoot.getAbsolutePath());

                NcnnLlmBridge.registerAccessibilityToolBridge(new AccessibilityToolBridge());

                boolean ok = NcnnLlmBridge.startOpenAiServerWithWebRoot(
                        modelDir.getAbsolutePath(), 18080, false, webRoot.getAbsolutePath());
                Log.i(TAG, "startOpenAiServer returned " + ok);

                runOnUiThread(() -> {
                    if (ok) {
                        serverStarted = true;
                        toast("模型服务已启动（18080）。");
                        refreshServerButton();
                    } else {
                        toast("服务启动失败或已在运行。");
                        btnStartServer.setEnabled(true);
                        btnStartServer.setText("启动模型服务");
                    }
                    serverStarting.set(false);
                });
            } catch (Exception e) {
                Log.e(TAG, "Start server failed: " + e.getMessage(), e);
                runOnUiThread(() -> {
                    toast("启动失败：" + e.getMessage());
                    setDownloadViewsVisible(false);
                    btnStartServer.setEnabled(true);
                    btnStartServer.setText("启动模型服务");
                    serverStarting.set(false);
                });
            }
        }).start();
    }

    private void sendChatOnce() {
        String text = chatInput.getText() == null ? "" : chatInput.getText().toString();
        if (TextUtils.isEmpty(text.trim())) {
            toast("请输入内容。");
            return;
        }
        chatInput.setText("");

        appendChatLine("我", text);
        try {
            JSONObject msg = new JSONObject();
            msg.put("role", "user");
            msg.put("content", text);
            chatMessages.add(msg);
        } catch (org.json.JSONException e) {
            appendChatLine("系统", "JSON 组装失败：" + e.getMessage());
            return;
        }

        String modelName = (String) modelSpinner.getSelectedItem();
        if (TextUtils.isEmpty(modelName)) {
            modelName = "qwen3_0.6b";
        }
        final String modelNameFinal = modelName;

        appendChatLine("系统", "请求中…（" + modelNameFinal + "）");
        new Thread(() -> {
            try {
                final StringBuilder reply = new StringBuilder();
                final boolean[] toolHeaderShown = new boolean[]{false};
                final String[] basePrefixHolder = new String[1];
                CountDownLatch latch = new CountDownLatch(1);
                runOnUiThread(() -> {
                    String base = outputText.getText() == null ? "" : outputText.getText().toString();
                    if (!TextUtils.isEmpty(base) && base.charAt(base.length() - 1) != '\n') {
                        base += "\n";
                    }
                    basePrefixHolder[0] = base + "[模型] ";
                    outputText.setText(basePrefixHolder[0]);
                    latch.countDown();
                });
                latch.await();

                AccessCtlService service = AccessCtlService.getInstance();
                if (service != null) {
                    service.clearOverlayLlmText();
                    service.appendOverlayLogLine("[我] " + text);
                    service.appendOverlayLogLine("[系统] 请求中…（" + modelNameFinal + "）");
                }

                ChatClient.chatCompletionsStream(LOCAL_SERVER_BASE_URL, modelNameFinal, chatMessages, new ChatClient.StreamListener() {
                    @Override
                    public void onDelta(String text) {
                        if (TextUtils.isEmpty(text)) return;
                        reply.append(text);
                        runOnUiThread(() -> {
                            String basePrefix = basePrefixHolder[0] == null ? "" : basePrefixHolder[0];
                            outputText.setText(basePrefix + reply);
                        });
                        AccessCtlService s = AccessCtlService.getInstance();
                        if (s != null) {
                            s.appendOverlayLlmText(text);
                        }
                    }

                    @Override
                    public void onToolTrace(String toolTrace) {
                        String namesOnly = ToolTraceFormatter.toolNamesOnly(toolTrace);
                        if (TextUtils.isEmpty(namesOnly)) {
                            return;
                        }
                        runOnUiThread(() -> {
                            if (!toolHeaderShown[0]) {
                                appendChatLine("工具调用", namesOnly);
                                toolHeaderShown[0] = true;
                            } else {
                                appendChatLine("工具", namesOnly);
                            }
                        });
                        AccessCtlService s = AccessCtlService.getInstance();
                        if (s != null) {
                            s.appendOverlayLogLine("[工具] " + namesOnly);
                        }
                    }

                    @Override
                    public void onDone() {
                        try {
                            JSONObject assistant = new JSONObject();
                            assistant.put("role", "assistant");
                            assistant.put("content", reply.toString());
                            chatMessages.add(assistant);
                        } catch (Exception ignore) {
                        }
                        runOnUiThread(() -> appendChatLine("系统", "完成"));
                        AccessCtlService s = AccessCtlService.getInstance();
                        if (s != null) {
                            s.appendOverlayLogLine("[系统] 完成");
                        }
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> appendChatLine("系统", "流式请求失败：" + message));
                        AccessCtlService s = AccessCtlService.getInstance();
                        if (s != null) {
                            s.appendOverlayLogLine("[系统] 流式请求失败：" + message);
                        }
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> appendChatLine("系统", "请求失败：" + e.getMessage()));
                AccessCtlService s = AccessCtlService.getInstance();
                if (s != null) {
                    s.appendOverlayLogLine("[系统] 请求失败：" + e.getMessage());
                }
            }
        }).start();
    }

    private void appendChatLine(String who, String text) {
        String prev = outputText.getText() == null ? "" : outputText.getText().toString();
        StringBuilder sb = new StringBuilder(prev);
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
        sb.append('[').append(who).append("] ").append(text).append('\n');
        outputText.setText(sb.toString());
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
        List<AccessibilityServiceInfo> list = manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
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
                    toast(TextUtils.isEmpty(name) ? "执行失败。" : ("已执行：" + name));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void setupModelSpinner() {
        String[] models = new String[]{
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
