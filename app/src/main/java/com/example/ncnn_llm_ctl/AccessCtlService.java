package com.example.ncnn_llm_ctl;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class AccessCtlService extends AccessibilityService {

    private static volatile AccessCtlService instance;
    private static final String LOCAL_SERVER_BASE_URL = "http://127.0.0.1:18080";
    private static final String DEFAULT_MODEL = "qwen3_0.6b";

    private AccessibilityNodeInfo lastEditable;
    private WindowManager windowManager;
    private View bubbleView;
    private View panelView;
    private WindowManager.LayoutParams bubbleParams;
    private WindowManager.LayoutParams panelParams;
    private TextView bubbleInfoText;
    private ScrollView bubbleInfoScroll;
    private TextView panelFixedText;
    private TextView panelOutputText;
    private ScrollView panelOutputScroll;
    private EditText panelChatInput;
    private Button panelChatSend;
    private final StringBuilder overlayLlmBuffer = new StringBuilder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean overlayChatInFlight = new AtomicBoolean(false);
    private final List<JSONObject> overlayChatMessages = new ArrayList<>();

    public static AccessCtlService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
                info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
                setServiceInfo(info);
            }
        } catch (Throwable ignore) {
        }
        initOverlay();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_VIEW_FOCUSED
                || type == AccessibilityEvent.TYPE_VIEW_CLICKED
                || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                if (source.isEditable()) {
                    if (lastEditable != null) {
                        lastEditable.recycle();
                    }
                    lastEditable = AccessibilityNodeInfo.obtain(source);
                }
                source.recycle();
            }
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (lastEditable != null) {
            lastEditable.recycle();
            lastEditable = null;
        }
        removeOverlay();
        bubbleInfoText = null;
        bubbleInfoScroll = null;
        panelFixedText = null;
        panelOutputText = null;
        panelOutputScroll = null;
        instance = null;
        super.onDestroy();
    }

    public void clearOverlayLlmText() {
        mainHandler.post(() -> {
            overlayLlmBuffer.setLength(0);
            setOverlayTexts("LLM：", "");
        });
    }

    public void appendOverlayLlmText(String text) {
        if (text == null) {
            return;
        }
        mainHandler.post(() -> {
            overlayLlmBuffer.append(text);
            trimOverlayBufferIfNeeded();
            setOverlayTexts("LLM：", overlayLlmBuffer.toString());
        });
    }

    public void appendOverlayLogLine(String line) {
        if (line == null) {
            return;
        }
        mainHandler.post(() -> {
            overlayLlmBuffer.append('\n').append(line);
            trimOverlayBufferIfNeeded();
            setOverlayTexts("LLM：", overlayLlmBuffer.toString());
        });
    }

    private void trimOverlayBufferIfNeeded() {
        // Keep last ~1200 chars to avoid UI lag.
        int max = 1200;
        if (overlayLlmBuffer.length() <= max) {
            return;
        }
        int start = overlayLlmBuffer.length() - max;
        overlayLlmBuffer.delete(0, Math.max(0, start));
    }

    private void setOverlayTexts(String fixed, String body) {
        if (bubbleInfoText != null) {
            bubbleInfoText.setText(body);
            if (bubbleInfoScroll != null) {
                bubbleInfoScroll.post(() -> bubbleInfoScroll.fullScroll(View.FOCUS_DOWN));
            }
        }
        if (panelFixedText != null) {
            panelFixedText.setText(fixed);
        }
        if (panelOutputText != null) {
            panelOutputText.setText(body);
            if (panelOutputScroll != null) {
                panelOutputScroll.post(() -> panelOutputScroll.fullScroll(View.FOCUS_DOWN));
            }
        }
    }

    public boolean clickAt(int x, int y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 50);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        return dispatchGesture(gesture, null, null);
    }

    public boolean inputText(String text) {
        AccessibilityNodeInfo target = getTargetEditable();
        if (target == null) {
            return false;
        }
        if (!target.isFocused()) {
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        }
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (!ok) {
            ok = pasteText(target, text);
        }
        target.recycle();
        return ok;
    }

    public String getCurrentUiDump() {
        StringBuilder sb = new StringBuilder();

        AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
        if (activeRoot != null) {
            CharSequence pkg = activeRoot.getPackageName();
            String pkgStr = pkg == null ? "" : pkg.toString();
            if (!isNoisyPackage(pkgStr)) {
                sb.append("Window: active pkg=").append(pkgStr).append('\n');
                dumpNode(activeRoot, 0, sb);
                activeRoot.recycle();
                return sb.toString();
            }
            activeRoot.recycle();
        }

        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null || windows.isEmpty()) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                return "";
            }
            sb.append("Window: active\n");
            dumpNode(root, 0, sb);
            root.recycle();
            return sb.toString();
        }
        for (int i = 0; i < windows.size(); i++) {
            AccessibilityWindowInfo window = windows.get(i);
            if (window == null) {
                continue;
            }
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null) {
                CharSequence pkg = root.getPackageName();
                String pkgStr = pkg == null ? "" : pkg.toString();
                if (!isNoisyPackage(pkgStr) && window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    sb.append("Window: best#").append(i);
                    sb.append(" type=").append(window.getType());
                    sb.append(" active=").append(window.isActive());
                    sb.append(" focused=").append(window.isFocused());
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        CharSequence title = window.getTitle();
                        if (title != null) sb.append(" title=").append(title);
                    }
                    sb.append(" pkg=").append(pkgStr);
                    sb.append("\n");
                    dumpNode(root, 0, sb);
                    root.recycle();
                    return sb.toString();
                }
            }
            sb.append("Window #").append(i);
            sb.append(" type=").append(window.getType());
            sb.append(" active=").append(window.isActive());
            sb.append(" focused=").append(window.isFocused());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                CharSequence title = window.getTitle();
                if (title != null) sb.append(" title=").append(title);
            }
            sb.append("\n");
            if (root != null) {
                CharSequence pkg = root.getPackageName();
                if (pkg != null) sb.append("  pkg=").append(pkg).append("\n");
                dumpNode(root, 0, sb);
                root.recycle();
            } else {
                sb.append("  <no root>\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private boolean isNoisyPackage(String pkg) {
        if (TextUtils.isEmpty(pkg)) return false;
        if (pkg.equals(getPackageName())) return true;
        return "com.android.systemui".equals(pkg);
    }

    public boolean clickByViewId(String viewId) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return false;
        }
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(viewId);
        root.recycle();
        if (nodes == null || nodes.isEmpty()) {
            return false;
        }
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null) {
                continue;
            }
            boolean ok = performClickOnNode(node);
            node.recycle();
            if (ok) {
                return true;
            }
        }
        return false;
    }

    public boolean clickByText(String text, boolean contains) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return false;
        }
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        root.recycle();
        if (nodes == null || nodes.isEmpty()) {
            return false;
        }
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null) {
                continue;
            }
            CharSequence t = node.getText();
            boolean match;
            if (t == null) {
                match = false;
            } else if (contains) {
                match = t.toString().contains(text);
            } else {
                match = text.contentEquals(t);
            }
            boolean ok = false;
            if (match) {
                ok = performClickOnNode(node);
            }
            node.recycle();
            if (ok) {
                return true;
            }
        }
        return false;
    }

    public boolean setTextByViewId(String viewId, String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return false;
        }
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(viewId);
        root.recycle();
        if (nodes == null || nodes.isEmpty()) {
            return false;
        }
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null) {
                continue;
            }
            boolean ok;
            try {
                if (!node.isFocused()) {
                    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                }
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                if (!ok) {
                    ok = pasteText(node, text);
                }
            } finally {
                node.recycle();
            }
            if (ok) {
                return true;
            }
        }
        return false;
    }

    public List<String> getGlobalActionNames() {
        return GlobalActionRegistry.getNames();
    }

    public String performGlobalActionByIndex(int index) {
        int action = GlobalActionRegistry.getAction(index);
        if (action < 0) {
            return null;
        }
        boolean ok = performGlobalAction(action);
        return ok ? GlobalActionRegistry.getName(index) : null;
    }

    private static class GlobalActionRegistry {
        private static final int[] ACTIONS;
        private static final String[] NAMES;

        static {
            int size = 6;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                size += 1;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                size += 3;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                size += 1;
            }

            ACTIONS = new int[size];
            NAMES = new String[size];
            int i = 0;

            ACTIONS[i] = GLOBAL_ACTION_BACK;
            NAMES[i++] = "返回";
            ACTIONS[i] = GLOBAL_ACTION_HOME;
            NAMES[i++] = "桌面";
            ACTIONS[i] = GLOBAL_ACTION_RECENTS;
            NAMES[i++] = "最近任务";
            ACTIONS[i] = GLOBAL_ACTION_NOTIFICATIONS;
            NAMES[i++] = "通知栏";
            ACTIONS[i] = GLOBAL_ACTION_QUICK_SETTINGS;
            NAMES[i++] = "快捷设置";
            ACTIONS[i] = GLOBAL_ACTION_POWER_DIALOG;
            NAMES[i++] = "电源菜单";

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ACTIONS[i] = GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN;
                NAMES[i++] = "分屏切换";
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ACTIONS[i] = GLOBAL_ACTION_LOCK_SCREEN;
                NAMES[i++] = "锁屏";
                ACTIONS[i] = GLOBAL_ACTION_TAKE_SCREENSHOT;
                NAMES[i++] = "系统截屏";
                ACTIONS[i] = GLOBAL_ACTION_ACCESSIBILITY_BUTTON;
                NAMES[i++] = "无障碍按钮";
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ACTIONS[i] = GLOBAL_ACTION_ACCESSIBILITY_BUTTON_CHOOSER;
                NAMES[i] = "无障碍按钮选择器";
            }
        }

        private static List<String> getNames() {
            return java.util.Arrays.asList(NAMES);
        }

        private static int getAction(int index) {
            if (index < 0 || index >= ACTIONS.length) {
                return -1;
            }
            return ACTIONS[index];
        }

        private static String getName(int index) {
            if (index < 0 || index >= NAMES.length) {
                return null;
            }
            return NAMES[index];
        }
    }

    private boolean pasteText(AccessibilityNodeInfo target, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) {
            return false;
        }
        cm.setPrimaryClip(ClipData.newPlainText("input", text));
        return target.performAction(AccessibilityNodeInfo.ACTION_PASTE);
    }

    private AccessibilityNodeInfo getTargetEditable() {
        if (lastEditable != null) {
            return AccessibilityNodeInfo.obtain(lastEditable);
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return null;
        }
        AccessibilityNodeInfo focused = findFocusedEditable(root);
        if (focused != null) {
            root.recycle();
            return focused;
        }
        AccessibilityNodeInfo first = findFirstEditable(root);
        root.recycle();
        return first;
    }

    private void initOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        bubbleView = inflater.inflate(R.layout.overlay_bubble, null);
        panelView = inflater.inflate(R.layout.overlay_panel, null);

        bubbleInfoText = bubbleView.findViewById(R.id.overlayBubbleInfo);
        bubbleInfoScroll = bubbleView.findViewById(R.id.overlayBubbleScroll);
        panelFixedText = panelView.findViewById(R.id.overlayFixedText);
        panelOutputText = panelView.findViewById(R.id.overlayOutputText);
        panelOutputScroll = panelView.findViewById(R.id.overlayOutputScroll);
        panelChatInput = panelView.findViewById(R.id.overlayChatInput);
        panelChatSend = panelView.findViewById(R.id.btnOverlaySend);
        setOverlayTexts("LLM：", "等待中…");

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int bubbleTextWidth = Math.round(screenWidth * 2f / 3f);
        int bubbleWidth = dp(56) + dp(8) + bubbleTextWidth;

        bubbleParams = new WindowManager.LayoutParams(
                bubbleWidth,
                dp(56),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.START | Gravity.TOP;
        bubbleParams.x = 0;
        bubbleParams.y = dp(120);

        panelParams = new WindowManager.LayoutParams(
                screenWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.START | Gravity.TOP;
        panelParams.x = 0;
        panelParams.y = dp(100);

        bubbleView.setOnClickListener(v -> showPanel());
        bubbleView.setOnTouchListener(new DragTouchListener(bubbleParams));

        panelView.setClickable(true);
        panelView.setOnClickListener(v -> hidePanel());

        if (panelChatSend != null) {
            panelChatSend.setOnClickListener(v -> sendOverlayChat());
        }

        windowManager.addView(bubbleView, bubbleParams);
    }

    private void sendOverlayChat() {
        if (panelChatInput == null) {
            return;
        }
        String text = panelChatInput.getText() == null ? "" : panelChatInput.getText().toString();
        if (TextUtils.isEmpty(text.trim())) {
            showToast("请输入内容。");
            return;
        }
        if (!overlayChatInFlight.compareAndSet(false, true)) {
            showToast("正在请求中…");
            return;
        }
        panelChatInput.setText("");

        clearOverlayLlmText();
        appendOverlayLogLine("[我] " + text);

        try {
            JSONObject msg = new JSONObject();
            msg.put("role", "user");
            msg.put("content", text);
            overlayChatMessages.add(msg);
            JavaMcpTools.ensureToolSystemMessage(overlayChatMessages);
        } catch (Exception e) {
            overlayChatInFlight.set(false);
            appendOverlayLogLine("[系统] JSON 组装失败：" + e.getMessage());
            return;
        }

        new Thread(() -> {
            final JSONArray tools = JavaMcpTools.buildOpenAiTools();
            final AccessibilityToolBridge toolBridge = new AccessibilityToolBridge();
            final int maxSteps = 8;
            boolean legacy = false;

            if (!legacy) {
                try {
                    runOverlayToolLoop(text, tools, toolBridge, maxSteps);
                } catch (Exception e) {
                    overlayChatInFlight.set(false);
                    appendOverlayLogLine("[系统] 请求失败：" + e);
                }
                return;
            }
            StringBuilder reply = new StringBuilder();
            final JSONArray[] toolHistoryHolder = new JSONArray[1];
            try {
                ChatClient.chatCompletionsStream(LOCAL_SERVER_BASE_URL, DEFAULT_MODEL, overlayChatMessages, new ChatClient.StreamListener() {
                    @Override
                    public void onDelta(String t) {
                        if (TextUtils.isEmpty(t)) return;
                        reply.append(t);
                        appendOverlayLlmText(t);
                    }

                    @Override
                    public void onToolTrace(String toolTrace) {
                        String namesOnly = ToolTraceFormatter.toolNamesOnly(toolTrace);
                        if (TextUtils.isEmpty(namesOnly)) return;
                        appendOverlayLogLine("[工具] " + namesOnly);
                    }

                    @Override
                    public void onToolHistory(JSONArray toolHistory) {
                        toolHistoryHolder[0] = toolHistory;
                    }

                    @Override
                    public void onToolCalls(JSONArray toolCalls) {
                    }

                    @Override
                    public void onFinishReason(String finishReason) {
                    }

                    @Override
                    public void onDone() {
                        try {
                            appendToolHistoryToMessages(toolHistoryHolder[0], overlayChatMessages);
                            JSONObject assistant = new JSONObject();
                            assistant.put("role", "assistant");
                            assistant.put("content", reply.toString());
                            overlayChatMessages.add(assistant);
                        } catch (Exception ignore) {
                        }
                        overlayChatInFlight.set(false);
                        appendOverlayLogLine("[系统] 完成");
                    }

                    @Override
                    public void onError(String message) {
                        overlayChatInFlight.set(false);
                        appendOverlayLogLine("[系统] 流式请求失败：" + message);
                    }
                });
            } catch (Exception e) {
                overlayChatInFlight.set(false);
                appendOverlayLogLine("[系统] 请求失败：" + e.getMessage());
            }
        }).start();
    }

    private void runOverlayToolLoop(String firstUserText,
                                    JSONArray tools,
                                    AccessibilityToolBridge toolBridge,
                                    int maxSteps) throws Exception {
        if (!ChatClient.ping(LOCAL_SERVER_BASE_URL, 800)) {
            overlayChatInFlight.set(false);
            String nativeErr = "";
            try {
                nativeErr = NcnnLlmBridge.getLastServerError();
            } catch (Throwable ignore) {
            }
            appendOverlayLogLine("[系统] 本地模型服务未启动，请先在主界面点击“启动模型服务”。 " + nativeErr);
            return;
        }
        for (int step = 0; step < maxSteps; step++) {
            StringBuilder reply = new StringBuilder();
            final JSONArray[] toolCallsHolder = new JSONArray[1];
            final String[] errorHolder = new String[1];

            appendOverlayLogLine("[系统] 请求中…（" + DEFAULT_MODEL + "）");
            JSONArray toolsForThisRequest = (step == 0) ? tools : null;
            ChatClient.chatCompletionsStream(
                    LOCAL_SERVER_BASE_URL,
                    DEFAULT_MODEL,
                    overlayChatMessages,
                    toolsForThisRequest,
                    JavaMcpTools.TOOL_MODE_EMIT,
                    new ChatClient.StreamListener() {
                        @Override
                        public void onDelta(String t) {
                            if (TextUtils.isEmpty(t)) return;
                            reply.append(t);
                            appendOverlayLlmText(t);
                        }

                        @Override
                        public void onToolTrace(String toolTrace) {
                            String namesOnly = ToolTraceFormatter.toolNamesOnly(toolTrace);
                            if (TextUtils.isEmpty(namesOnly)) return;
                            appendOverlayLogLine("[工具] " + namesOnly);
                        }

                        @Override
                        public void onToolHistory(JSONArray toolHistory) {
                        }

                        @Override
                        public void onToolCalls(JSONArray toolCalls) {
                            toolCallsHolder[0] = toolCalls;
                            String names = JavaMcpTools.toolNamesOnly(toolCalls);
                            if (!TextUtils.isEmpty(names)) {
                                appendOverlayLogLine("[需要工具] " + names);
                            }
                        }

                        @Override
                        public void onFinishReason(String finishReason) {
                        }

                        @Override
                        public void onDone() {
                        }

                        @Override
                        public void onError(String message) {
                            errorHolder[0] = message;
                        }
                    });

            if (!TextUtils.isEmpty(errorHolder[0])) {
                overlayChatInFlight.set(false);
                appendOverlayLogLine("[系统] 请求失败：" + errorHolder[0]);
                return;
            }

            JSONArray toolCalls = toolCallsHolder[0];
            if (toolCalls != null && toolCalls.length() > 0) {
                try {
                    JSONObject assistant = new JSONObject();
                    assistant.put("role", "assistant");
                    assistant.put("content", reply.toString());
                    assistant.put("tool_calls", toolCalls);
                    overlayChatMessages.add(assistant);
                } catch (Exception ignore) {
                }

                for (int i = 0; i < toolCalls.length(); i++) {
                    JSONObject tc = toolCalls.optJSONObject(i);
                    JSONObject result = JavaMcpTools.executeToolCall(tc, toolBridge);
                    try {
                        JSONObject toolMsg = new JSONObject();
                        toolMsg.put("role", "tool");
                        toolMsg.put("content", result.toString());
                        overlayChatMessages.add(toolMsg);
                    } catch (Exception ignore) {
                    }

                    String name = "";
                    try {
                        JSONObject fn = tc == null ? null : tc.optJSONObject("function");
                        name = fn == null ? "" : fn.optString("name", "");
                    } catch (Exception ignore) {
                    }
                    appendOverlayLogLine("[工具结果] " + name + " ok=" + result.optBoolean("ok", false));
                }

                clearOverlayLlmText();
                if (step == 0) {
                    appendOverlayLogLine("[我] " + firstUserText);
                }
                appendOverlayLogLine("[系统] 工具执行完成，继续推理…");
                continue;
            }

            try {
                JSONObject assistant = new JSONObject();
                assistant.put("role", "assistant");
                assistant.put("content", reply.toString());
                overlayChatMessages.add(assistant);
            } catch (Exception ignore) {
            }

            overlayChatInFlight.set(false);
            appendOverlayLogLine("[系统] 完成");
            return;
        }

        overlayChatInFlight.set(false);
        appendOverlayLogLine("[系统] 超过最大工具循环次数，已停止。");
    }

    private static void appendToolHistoryToMessages(JSONArray toolHistory, List<JSONObject> messages) {
        if (toolHistory == null || messages == null || toolHistory.length() == 0) {
            return;
        }
        for (int i = 0; i < toolHistory.length(); i++) {
            JSONObject item = toolHistory.optJSONObject(i);
            if (item == null) continue;

            String name = item.optString("name", "");
            boolean ok = item.optBoolean("ok", false);
            long costMs = item.optLong("cost_ms", -1L);
            String error = item.optString("error", "");
            String result = item.optString("result", "");

            if (result.length() > 2000) {
                result = result.substring(0, 2000) + "...(截断)";
            }

            StringBuilder content = new StringBuilder();
            content.append("[工具结果] ").append(name);
            content.append("\nok=").append(ok);
            if (costMs >= 0) content.append(" cost_ms=").append(costMs);
            if (!TextUtils.isEmpty(error)) content.append("\nerror=").append(error);
            if (!TextUtils.isEmpty(result)) content.append("\n").append(result);

            try {
                JSONObject msg = new JSONObject();
                msg.put("role", "system");
                msg.put("content", content.toString());
                messages.add(msg);
            } catch (Exception ignore) {
            }
        }
    }

    private void removeOverlay() {
        if (windowManager == null) {
            return;
        }
        if (panelView != null && panelView.getWindowToken() != null) {
            windowManager.removeView(panelView);
        }
        if (bubbleView != null && bubbleView.getWindowToken() != null) {
            windowManager.removeView(bubbleView);
        }
        panelView = null;
        bubbleView = null;
    }

    private void showPanel() {
        if (windowManager == null || panelView == null) {
            return;
        }
        if (panelView.getWindowToken() == null) {
            windowManager.addView(panelView, panelParams);
        }
        if (bubbleView != null && bubbleView.getWindowToken() != null) {
            windowManager.removeView(bubbleView);
        }
    }

    private void hidePanel() {
        if (windowManager == null || bubbleView == null) {
            return;
        }
        if (bubbleView.getWindowToken() == null) {
            windowManager.addView(bubbleView, bubbleParams);
        }
        if (panelView != null && panelView.getWindowToken() != null) {
            windowManager.removeView(panelView);
        }
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private class DragTouchListener implements View.OnTouchListener {
        private final WindowManager.LayoutParams params;
        private int lastX;
        private int lastY;

        DragTouchListener(WindowManager.LayoutParams params) {
            this.params = params;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = (int) event.getRawX();
                    lastY = (int) event.getRawY();
                    return false;
                case MotionEvent.ACTION_MOVE:
                    int nowX = (int) event.getRawX();
                    int nowY = (int) event.getRawY();
                    int dx = nowX - lastX;
                    int dy = nowY - lastY;
                    params.x += dx;
                    params.y += dy;
                    lastX = nowX;
                    lastY = nowY;
                    if (windowManager != null) {
                        windowManager.updateViewLayout(v.getRootView(), params);
                    }
                    return true;
                default:
                    return false;
            }
        }
    }

    private boolean performClickOnNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        while (current != null) {
            if (current.isClickable() && current.isEnabled()) {
                boolean ok = current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (current != node) {
                    current.recycle();
                }
                return ok;
            }
            AccessibilityNodeInfo parent = current.getParent();
            if (current != node) {
                current.recycle();
            }
            current = parent;
        }
        return false;
    }

    private void dumpNode(AccessibilityNodeInfo node, int depth, StringBuilder sb) {
        if (node == null) {
            return;
        }
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        sb.append(node.getClassName());
        if (node.getViewIdResourceName() != null) {
            sb.append(" id=").append(node.getViewIdResourceName());
        }
        if (node.getText() != null) {
            sb.append(" text=").append(node.getText());
        }
        if (node.getContentDescription() != null) {
            sb.append(" desc=").append(node.getContentDescription());
        }
        sb.append(" clickable=").append(node.isClickable());
        sb.append(" enabled=").append(node.isEnabled());
        sb.append("\n");

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                dumpNode(child, depth + 1, sb);
                child.recycle();
            }
        }
    }

    private AccessibilityNodeInfo findFocusedEditable(AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }
        if (node.isEditable() && (node.isFocused() || node.isAccessibilityFocused())) {
            return AccessibilityNodeInfo.obtain(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo result = findFocusedEditable(child);
            if (child != null) {
                child.recycle();
            }
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findFirstEditable(AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }
        if (node.isEditable()) {
            return AccessibilityNodeInfo.obtain(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo result = findFirstEditable(child);
            if (child != null) {
                child.recycle();
            }
            if (result != null) {
                return result;
            }
        }
        return null;
    }
}
