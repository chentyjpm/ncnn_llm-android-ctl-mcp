package com.example.ncnn_llm_ctl;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
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
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class AccessCtlService extends AccessibilityService {

    private static volatile AccessCtlService instance;
    private AccessibilityNodeInfo lastEditable;
    private WindowManager windowManager;
    private View bubbleView;
    private View panelView;
    private WindowManager.LayoutParams bubbleParams;
    private WindowManager.LayoutParams panelParams;

    public static AccessCtlService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
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
        instance = null;
        super.onDestroy();
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
            sb.append("Window #").append(i);
            sb.append(" type=").append(window.getType());
            sb.append(" active=").append(window.isActive());
            sb.append(" focused=").append(window.isFocused());
            sb.append("\n");
            if (root != null) {
                dumpNode(root, 0, sb);
                root.recycle();
            } else {
                sb.append("  <no root>\n");
            }
            sb.append("\n");
        }
        return sb.toString();
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

        bubbleParams = new WindowManager.LayoutParams(
                dp(56),
                dp(56),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.START | Gravity.TOP;
        bubbleParams.x = 0;
        bubbleParams.y = dp(120);

        panelParams = new WindowManager.LayoutParams(
                dp(320),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.START | Gravity.TOP;
        panelParams.x = dp(16);
        panelParams.y = dp(100);

        bubbleView.setOnClickListener(v -> showPanel());
        bubbleView.setOnTouchListener(new DragTouchListener(bubbleParams));

        Button btnDumpUi = panelView.findViewById(R.id.btnOverlayDumpUi);
        Button btnClose = panelView.findViewById(R.id.btnOverlayClose);
        TextView outputText = panelView.findViewById(R.id.overlayOutputText);

        btnDumpUi.setOnClickListener(v -> {
            String dump = getCurrentUiDump();
            if (TextUtils.isEmpty(dump)) {
                outputText.setText("未获取到屏幕内容。");
            } else {
                outputText.setText(dump);
            }
            View scroll = panelView.findViewById(R.id.overlayOutputScroll);
            if (scroll instanceof android.widget.ScrollView) {
                android.widget.ScrollView scrollView = (android.widget.ScrollView) scroll;
                scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
            }
        });

        btnClose.setOnClickListener(v -> hidePanel());

        windowManager.addView(bubbleView, bubbleParams);
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
