package com.example.ncnn_llm_ctl;

import android.text.TextUtils;

public final class ToolTraceFormatter {
    private ToolTraceFormatter() {
    }

    public static String toolNamesOnly(String toolTrace) {
        if (TextUtils.isEmpty(toolTrace)) {
            return "";
        }
        String[] lines = toolTrace.split("\\r?\\n");
        StringBuilder sb = new StringBuilder();
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("=>")) {
                line = line.substring(2).trim();
            } else if (line.startsWith("<=")) {
                line = line.substring(2).trim();
            }
            int space = line.indexOf(' ');
            String name = space > 0 ? line.substring(0, space).trim() : line;
            if (name.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(name);
        }
        return sb.toString();
    }
}

