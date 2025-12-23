package com.example.ncnn_llm_ctl;

import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

public final class JavaMcpTools {
    private static final String TAG = "JavaMcpTools";
    private static final String SYSTEM_MARKER = "[工具说明]";

    private JavaMcpTools() {
    }

    public static final String TOOL_MODE_EMIT = "emit";

    public static JSONArray buildOpenAiTools() {
        JSONArray tools = new JSONArray();

        tools.put(functionTool(
                "dump_ui",
                "获取当前屏幕 UI 结构（系统/其他应用）",
                params0()
        ));

        tools.put(functionTool(
                "global_action",
                "执行系统全局动作（返回/桌面/通知栏等）",
                params1("name", "动作名称（中文，例如：返回、桌面、通知栏）")
        ));

        tools.put(functionTool(
                "click_view_id",
                "通过 viewIdResourceName 点击控件",
                params1("view_id", "例如：com.xxx:id/btn_ok")
        ));

        tools.put(functionTool(
                "set_text_view_id",
                "通过 viewIdResourceName 向输入框设置文本",
                params2("view_id", "例如：com.xxx:id/et_input", "text", "要输入的文本（允许为空字符串）")
        ));

        return tools;
    }

    public static void ensureToolSystemMessage(List<JSONObject> messages) {
        if (messages == null) return;
        try {
            if (!messages.isEmpty()) {
                JSONObject first = messages.get(0);
                if (first != null && "system".equals(first.optString("role", ""))) {
                    String c = first.optString("content", "");
                    if (!TextUtils.isEmpty(c) && c.contains(SYSTEM_MARKER)) {
                        return;
                    }
                }
            }
            JSONObject sys = new JSONObject();
            put(sys, "role", "system");
            put(sys, "content", buildToolSystemPrompt());
            messages.add(0, sys);
        } catch (Exception ignore) {
        }
    }

    public static String buildToolSystemPrompt() {
        JSONArray tools = buildOpenAiTools();
        StringBuilder sb = new StringBuilder();
        sb.append("You are a helpful assistant.\n");
        sb.append(SYSTEM_MARKER).append("\n");
        sb.append("你可以调用工具来完成任务。工具定义如下（OpenAI tools JSON）：\n");
        sb.append("<tools>\n");
        sb.append(tools.toString());
        sb.append("\n</tools>\n");
        sb.append("当你需要调用工具时，请返回 tool_calls（而不是把工具调用写进普通文本）。\n");
        sb.append("优先用 click_view_id / set_text_view_id，通过 dump_ui 找到目标控件的 view_id。\n");
        return sb.toString();
    }

    private static JSONObject functionTool(String name, String description, JSONObject parameters) {
        JSONObject fn = new JSONObject();
        put(fn, "name", name);
        put(fn, "description", description);
        put(fn, "parameters", parameters);

        JSONObject tool = new JSONObject();
        put(tool, "type", "function");
        put(tool, "function", fn);
        return tool;
    }

    private static JSONObject params0() {
        JSONObject p = new JSONObject();
        put(p, "type", "object");
        put(p, "properties", new JSONObject());
        put(p, "required", new JSONArray());
        return p;
    }

    private static JSONObject params1(String key, String desc) {
        JSONObject p = new JSONObject();
        put(p, "type", "object");
        JSONObject props = new JSONObject();
        JSONObject v = new JSONObject();
        put(v, "type", "string");
        put(v, "description", desc);
        put(props, key, v);
        put(p, "properties", props);
        JSONArray req = new JSONArray();
        req.put(key);
        put(p, "required", req);
        return p;
    }

    private static JSONObject params2(String key1, String desc1, String key2, String desc2) {
        JSONObject p = new JSONObject();
        put(p, "type", "object");
        JSONObject props = new JSONObject();
        JSONObject v1 = new JSONObject();
        put(v1, "type", "string");
        put(v1, "description", desc1);
        put(props, key1, v1);
        JSONObject v2 = new JSONObject();
        put(v2, "type", "string");
        put(v2, "description", desc2);
        put(props, key2, v2);
        put(p, "properties", props);
        JSONArray req = new JSONArray();
        req.put(key1);
        req.put(key2);
        put(p, "required", req);
        return p;
    }

    private static void put(JSONObject obj, String key, Object value) {
        try {
            obj.put(key, value);
        } catch (Exception ignore) {
        }
    }

    public static String toolNamesOnly(JSONArray toolCalls) {
        if (toolCalls == null || toolCalls.length() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < toolCalls.length(); i++) {
            JSONObject tc = toolCalls.optJSONObject(i);
            if (tc == null) continue;
            JSONObject fn = tc.optJSONObject("function");
            if (fn == null) continue;
            String name = fn.optString("name", "");
            if (TextUtils.isEmpty(name)) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(name);
        }
        return sb.toString();
    }

    public static JSONObject executeToolCall(JSONObject toolCall, AccessibilityToolBridge bridge) {
        long t0 = System.currentTimeMillis();
        try {
            JSONObject fn = toolCall == null ? null : toolCall.optJSONObject("function");
            String name = fn == null ? "" : fn.optString("name", "");
            JSONObject args = fn == null ? null : fn.optJSONObject("arguments");
            if (args == null) args = new JSONObject();

            if (TextUtils.isEmpty(name)) {
                return new JSONObject().put("ok", false).put("error", "missing tool name");
            }
            if (bridge == null) {
                return new JSONObject().put("ok", false).put("error", "bridge is null").put("name", name);
            }

            JSONObject out = new JSONObject();
            out.put("name", name);

            switch (name) {
                case "dump_ui": {
                    String dump = bridge.dumpUi();
                    boolean ok = !TextUtils.isEmpty(dump);
                    out.put("ok", ok);
                    if (ok) {
                        out.put("dump", truncate(dump, 20000));
                    } else {
                        out.put("error", "empty dump (service disabled or no active window?)");
                    }
                    break;
                }
                case "global_action": {
                    String actionName = args.optString("name", "");
                    boolean ok = bridge.globalActionByName(actionName);
                    out.put("ok", ok);
                    out.put("action", actionName);
                    if (!ok) out.put("error", "global action failed or unsupported");
                    break;
                }
                case "click_view_id": {
                    String viewId = args.optString("view_id", "");
                    boolean ok = bridge.clickByViewId(viewId);
                    out.put("ok", ok);
                    out.put("view_id", viewId);
                    if (!ok) out.put("error", "click failed (not found or not clickable)");
                    break;
                }
                case "set_text_view_id": {
                    String viewId = args.optString("view_id", "");
                    String text = args.optString("text", "");
                    boolean ok = bridge.setTextByViewId(viewId, text);
                    out.put("ok", ok);
                    out.put("view_id", viewId);
                    out.put("text_len", text == null ? 0 : text.length());
                    if (!ok) out.put("error", "setText failed (not found or not editable)");
                    break;
                }
                default: {
                    out.put("ok", false);
                    out.put("error", "unknown tool: " + name);
                    out.put("args", args);
                }
            }

            out.put("cost_ms", System.currentTimeMillis() - t0);
            Log.i(TAG, "tool " + name + " ok=" + out.optBoolean("ok", false) + " costMs=" + out.optLong("cost_ms", -1));
            return out;
        } catch (Exception e) {
            JSONObject err = new JSONObject();
            try {
                err.put("ok", false);
                err.put("error", "exception: " + e.getMessage());
                err.put("cost_ms", System.currentTimeMillis() - t0);
            } catch (Exception ignore) {
            }
            Log.e(TAG, "tool exception: " + e.getMessage(), e);
            return err;
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...(截断,len=" + s.length() + ")";
    }
}
