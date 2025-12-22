package com.example.ncnn_llm_ctl;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedOutputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class ChatClient {
    private ChatClient() {
    }

    public static final class ChatResult {
        public final String content;
        public final String toolTrace;

        ChatResult(String content, String toolTrace) {
            this.content = content;
            this.toolTrace = toolTrace;
        }
    }

    public interface StreamListener {
        void onDelta(String text);

        void onToolTrace(String toolTrace);

        void onDone();

        void onError(String message);
    }

    public static ChatResult chatCompletions(String baseUrl, String model, List<JSONObject> messages) throws IOException {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IOException("baseUrl 为空");
        }
        if (model == null || model.trim().isEmpty()) {
            throw new IOException("model 为空");
        }
        if (messages == null || messages.isEmpty()) {
            throw new IOException("messages 为空");
        }

        JSONObject body = new JSONObject();
        try {
            body.put("model", model);
            body.put("messages", new JSONArray(messages));
            body.put("stream", false);
            body.put("enable_thinking", false);
        } catch (org.json.JSONException e) {
            throw new IOException("请求JSON组装失败: " + e.getMessage(), e);
        }

        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + "/v1/chat/completions");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(0);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Length", String.valueOf(payload.length));

            try (BufferedOutputStream out = new BufferedOutputStream(conn.getOutputStream())) {
                out.write(payload);
                out.flush();
            }

            int code = conn.getResponseCode();
            byte[] resp = readAll(code >= 200 && code < 300
                    ? new BufferedInputStream(conn.getInputStream())
                    : new BufferedInputStream(conn.getErrorStream()));
            String text = new String(resp, StandardCharsets.UTF_8);
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + ": " + text);
            }

            JSONObject json = new JSONObject(text);
            JSONArray choices = json.optJSONArray("choices");
            String toolTrace = "";
            JSONArray traceArr = json.optJSONArray("tool_trace");
            if (traceArr != null && traceArr.length() > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < traceArr.length(); i++) {
                    String line = traceArr.optString(i, "");
                    if (TextUtils.isEmpty(line)) {
                        continue;
                    }
                    sb.append(line).append('\n');
                }
                toolTrace = sb.toString();
            }

            if (choices == null || choices.length() == 0) {
                return new ChatResult(text, toolTrace);
            }
            JSONObject c0 = choices.optJSONObject(0);
            if (c0 == null) {
                return new ChatResult(text, toolTrace);
            }
            JSONObject msg = c0.optJSONObject("message");
            if (msg == null) {
                return new ChatResult(text, toolTrace);
            }
            return new ChatResult(msg.optString("content", text), toolTrace);
        } catch (org.json.JSONException e) {
            throw new IOException("解析响应失败: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public static void chatCompletionsStream(String baseUrl,
                                             String model,
                                             List<JSONObject> messages,
                                             StreamListener listener) throws IOException {
        if (listener == null) {
            throw new IOException("listener 为空");
        }
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IOException("baseUrl 为空");
        }
        if (model == null || model.trim().isEmpty()) {
            throw new IOException("model 为空");
        }
        if (messages == null || messages.isEmpty()) {
            throw new IOException("messages 为空");
        }

        JSONObject body = new JSONObject();
        try {
            body.put("model", model);
            body.put("messages", new JSONArray(messages));
            body.put("stream", true);
            body.put("enable_thinking", false);
        } catch (org.json.JSONException e) {
            throw new IOException("请求JSON组装失败: " + e.getMessage(), e);
        }

        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + "/v1/chat/completions");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(0);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.setRequestProperty("Connection", "keep-alive");
            conn.setRequestProperty("Content-Length", String.valueOf(payload.length));

            try (BufferedOutputStream out = new BufferedOutputStream(conn.getOutputStream())) {
                out.write(payload);
                out.flush();
            }

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                byte[] err = readAll(new BufferedInputStream(conn.getErrorStream()));
                listener.onError("HTTP " + code + ": " + new String(err, StandardCharsets.UTF_8));
                return;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                StringBuilder event = new StringBuilder();
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (event.length() > 0) {
                            handleSseEvent(event.toString(), listener);
                            event.setLength(0);
                        }
                        continue;
                    }
                    if (line.startsWith("data:")) {
                        String data = line.substring(5).trim();
                        if (event.length() > 0) event.append('\n');
                        event.append(data);
                    }
                }
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static void handleSseEvent(String data, StreamListener listener) {
        if (listener == null) {
            return;
        }
        if (TextUtils.isEmpty(data)) {
            return;
        }
        if ("[DONE]".equals(data)) {
            listener.onDone();
            return;
        }
        try {
            JSONObject json = new JSONObject(data);

            String toolLine = json.optString("tool_trace_line", "");
            if (!TextUtils.isEmpty(toolLine)) {
                listener.onToolTrace(toolLine);
            }

            // delta token
            JSONArray choices = json.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject c0 = choices.optJSONObject(0);
                if (c0 != null) {
                    JSONObject delta = c0.optJSONObject("delta");
                    if (delta != null) {
                        String content = delta.optString("content", "");
                        if (!TextUtils.isEmpty(content)) {
                            listener.onDelta(content);
                        }
                    }
                }
            }

            // tool trace (usually at final chunk)
            JSONArray traceArr = json.optJSONArray("tool_trace");
            if (traceArr != null && traceArr.length() > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < traceArr.length(); i++) {
                    String line = traceArr.optString(i, "");
                    if (TextUtils.isEmpty(line)) continue;
                    sb.append(line).append('\n');
                }
                String trace = sb.toString().trim();
                if (!TextUtils.isEmpty(trace)) {
                    listener.onToolTrace(trace);
                }
            }
        } catch (Exception e) {
            listener.onError("解析SSE失败: " + e.getMessage());
        }
    }

    private static byte[] readAll(BufferedInputStream in) throws IOException {
        if (in == null) {
            return new byte[0];
        }
        try (BufferedInputStream input = in) {
            byte[] buf = new byte[8192];
            int n;
            java.io.ByteArrayOutputStream bout = new java.io.ByteArrayOutputStream();
            while ((n = input.read(buf)) >= 0) {
                bout.write(buf, 0, n);
            }
            return bout.toByteArray();
        }
    }
}
