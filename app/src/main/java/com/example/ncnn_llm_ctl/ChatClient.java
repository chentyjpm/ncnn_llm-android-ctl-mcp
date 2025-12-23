package com.example.ncnn_llm_ctl;

import android.text.TextUtils;
import android.util.Log;

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
import java.util.concurrent.atomic.AtomicLong;

public final class ChatClient {
    private ChatClient() {
    }

    private static final String TAG = "ChatClient";
    private static final AtomicLong REQ_SEQ = new AtomicLong(1);
    private static final int LOG_BODY_MAX = 2000;

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...(截断,len=" + s.length() + ")";
    }

    private static String safeJson(Object o) {
        if (o == null) return "null";
        try {
            return String.valueOf(o);
        } catch (Exception e) {
            return "<toString failed: " + e + ">";
        }
    }

    public static boolean ping(String baseUrl, int timeoutMs) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            return false;
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + "/health");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(Math.max(200, timeoutMs));
            conn.setReadTimeout(Math.max(200, timeoutMs));
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception ignore) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public static final class ChatResult {
        public final String content;
        public final String toolTrace;
        public final JSONArray toolHistory;
        public final JSONArray toolCalls;
        public final String finishReason;

        ChatResult(String content, String toolTrace, JSONArray toolHistory, JSONArray toolCalls, String finishReason) {
            this.content = content;
            this.toolTrace = toolTrace;
            this.toolHistory = toolHistory;
            this.toolCalls = toolCalls;
            this.finishReason = finishReason;
        }
    }

    public interface StreamListener {
        void onDelta(String text);

        void onToolTrace(String toolTrace);

        void onToolHistory(JSONArray toolHistory);

        void onToolCalls(JSONArray toolCalls);

        void onFinishReason(String finishReason);

        void onDone();

        void onError(String message);
    }

    public static ChatResult chatCompletions(String baseUrl, String model, List<JSONObject> messages) throws IOException {
        return chatCompletions(baseUrl, model, messages, null, "execute");
    }

    public static ChatResult chatCompletions(String baseUrl,
                                            String model,
                                            List<JSONObject> messages,
                                            JSONArray tools,
                                            String toolMode) throws IOException {
        final long reqId = REQ_SEQ.getAndIncrement();
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
            if (tools != null) {
                body.put("tools", tools);
            }
            if (!TextUtils.isEmpty(toolMode)) {
                body.put("tool_mode", toolMode);
            }
        } catch (org.json.JSONException e) {
            throw new IOException("请求JSON组装失败: " + e.getMessage(), e);
        }

        Log.i(TAG, "#" + reqId + " POST " + baseUrl + "/v1/chat/completions stream=false model=" + model
                + " messages=" + messages.size()
                + " tools=" + (tools == null ? 0 : tools.length())
                + " toolMode=" + toolMode
                + " body=" + truncate(body.toString(), LOG_BODY_MAX));

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
            Log.i(TAG, "#" + reqId + " HTTP " + code + " respBytes=" + resp.length + " resp=" + truncate(text, LOG_BODY_MAX));
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
            JSONArray toolHistory = json.optJSONArray("tool_history");
            JSONArray toolCalls = json.optJSONArray("tool_calls");
            String finishReason = "";

            if (choices == null || choices.length() == 0) {
                return new ChatResult(text, toolTrace, toolHistory, toolCalls, finishReason);
            }
            JSONObject c0 = choices.optJSONObject(0);
            if (c0 == null) {
                return new ChatResult(text, toolTrace, toolHistory, toolCalls, finishReason);
            }
            finishReason = c0.optString("finish_reason", "");
            JSONObject msg = c0.optJSONObject("message");
            if (msg == null) {
                return new ChatResult(text, toolTrace, toolHistory, toolCalls, finishReason);
            }
            JSONArray msgToolCalls = msg.optJSONArray("tool_calls");
            if (msgToolCalls != null && msgToolCalls.length() > 0) {
                toolCalls = msgToolCalls;
            }
            Log.i(TAG, "#" + reqId + " finishReason=" + finishReason
                    + " contentLen=" + msg.optString("content", "").length()
                    + " toolCalls=" + (toolCalls == null ? 0 : toolCalls.length()));
            return new ChatResult(msg.optString("content", text), toolTrace, toolHistory, toolCalls, finishReason);
        } catch (org.json.JSONException e) {
            throw new IOException("解析响应失败: " + e.getMessage(), e);
        } catch (IOException e) {
            Log.e(TAG, "#" + reqId + " chatCompletions failed: " + e, e);
            throw e;
        } catch (Exception e) {
            Log.e(TAG, "#" + reqId + " chatCompletions failed: " + e, e);
            throw new IOException("请求异常: " + e, e);
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
        chatCompletionsStream(baseUrl, model, messages, null, "execute", listener);
    }

    public static void chatCompletionsStream(String baseUrl,
                                             String model,
                                             List<JSONObject> messages,
                                             JSONArray tools,
                                             String toolMode,
                                             StreamListener listener) throws IOException {
        final long reqId = REQ_SEQ.getAndIncrement();
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
            if (tools != null) {
                body.put("tools", tools);
            }
            if (!TextUtils.isEmpty(toolMode)) {
                body.put("tool_mode", toolMode);
            }
        } catch (org.json.JSONException e) {
            throw new IOException("请求JSON组装失败: " + e.getMessage(), e);
        }

        Log.i(TAG, "#" + reqId + " POST " + baseUrl + "/v1/chat/completions stream=true model=" + model
                + " messages=" + messages.size()
                + " tools=" + (tools == null ? 0 : tools.length())
                + " toolMode=" + toolMode
                + " body=" + truncate(body.toString(), LOG_BODY_MAX));

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
                String errText = new String(err, StandardCharsets.UTF_8);
                Log.e(TAG, "#" + reqId + " HTTP " + code + " error=" + truncate(errText, LOG_BODY_MAX));
                listener.onError("HTTP " + code + ": " + errText);
                return;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                StringBuilder event = new StringBuilder();
                long eventCount = 0;
                boolean doneSeen = false;
                try {
                    while ((line = reader.readLine()) != null) {
                        if (line.isEmpty()) {
                            if (event.length() > 0) {
                                String ev = event.toString();
                                eventCount++;
                                Log.i(TAG, "#" + reqId + " SSE event#" + eventCount + " bytes=" + ev.length() + " data=" + truncate(ev, 400));

                                if ("[DONE]".equals(ev)) {
                                    doneSeen = true;
                                    listener.onDone();
                                    break;
                                }

                                handleSseEvent(ev, listener);
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
                } catch (java.io.EOFException eof) {
                    // Some Android HttpURLConnection (OkHttp) implementations may throw EOFException
                    // even after the server has sent "data: [DONE]". Treat it as normal completion.
                    if (!doneSeen) {
                        throw eof;
                    }
                    Log.w(TAG, "#" + reqId + " SSE EOF after [DONE], ignored: " + eof);
                }
                Log.i(TAG, "#" + reqId + " SSE stream ended, pendingBytes=" + event.length());
            }
        } catch (IOException e) {
            Log.e(TAG, "#" + reqId + " chatCompletionsStream failed: " + e, e);
            throw e;
        } catch (Exception e) {
            Log.e(TAG, "#" + reqId + " chatCompletionsStream failed: " + e, e);
            throw new IOException("请求异常: " + e, e);
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
                    String finishReason = c0.optString("finish_reason", "");
                    if (!TextUtils.isEmpty(finishReason) && !"null".equals(finishReason)) {
                        listener.onFinishReason(finishReason);
                    }
                    JSONObject delta = c0.optJSONObject("delta");
                    if (delta != null) {
                        String content = delta.optString("content", "");
                        if (!TextUtils.isEmpty(content)) {
                            listener.onDelta(content);
                        }
                        JSONArray toolCalls = delta.optJSONArray("tool_calls");
                        if (toolCalls != null && toolCalls.length() > 0) {
                            listener.onToolCalls(toolCalls);
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

            // tool history (final chunk)
            JSONArray historyArr = json.optJSONArray("tool_history");
            if (historyArr != null && historyArr.length() > 0) {
                listener.onToolHistory(historyArr);
            }

            // tool calls (final chunk - our server includes it at top-level)
            JSONArray toolCallsTop = json.optJSONArray("tool_calls");
            if (toolCallsTop != null && toolCallsTop.length() > 0) {
                listener.onToolCalls(toolCallsTop);
            }
        } catch (Exception e) {
            listener.onError("解析SSE失败: " + e);
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
