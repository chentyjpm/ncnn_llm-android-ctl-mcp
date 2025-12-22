package com.example.ncnn_llm_ctl;

import android.text.TextUtils;
import android.util.Log;

public final class AccessibilityToolBridge {
    private static final String TAG = "AccToolBridge";

    public AccessibilityToolBridge() {
    }

    public String dumpUi() {
        AccessCtlService service = AccessCtlService.getInstance();
        if (service == null) {
            return "";
        }
        return service.getCurrentUiDump();
    }

    public boolean globalActionByName(String name) {
        AccessCtlService service = AccessCtlService.getInstance();
        if (service == null) {
            return false;
        }
        if (TextUtils.isEmpty(name)) {
            return false;
        }
        java.util.List<String> names = service.getGlobalActionNames();
        if (names == null) {
            return false;
        }
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            if (name.equals(n)) {
                return service.performGlobalActionByIndex(i) != null;
            }
        }
        return false;
    }

    public boolean clickByViewId(String viewId) {
        AccessCtlService service = AccessCtlService.getInstance();
        if (service == null) {
            return false;
        }
        if (TextUtils.isEmpty(viewId)) {
            return false;
        }
        return service.clickByViewId(viewId);
    }

    public boolean clickByText(String text, boolean contains) {
        AccessCtlService service = AccessCtlService.getInstance();
        if (service == null) {
            return false;
        }
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        try {
            return service.clickByText(text, contains);
        } catch (Throwable t) {
            Log.w(TAG, "clickByText failed: " + t.getMessage());
            return false;
        }
    }

    public boolean setTextByViewId(String viewId, String text) {
        AccessCtlService service = AccessCtlService.getInstance();
        if (service == null) {
            return false;
        }
        if (TextUtils.isEmpty(viewId)) {
            return false;
        }
        if (text == null) {
            text = "";
        }
        return service.setTextByViewId(viewId, text);
    }
}

