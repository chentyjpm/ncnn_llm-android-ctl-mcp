package com.example.ncnn_llm_ctl;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ModelDownloader {
    private static final String TAG = "ModelDownloader";
    private static final String DEFAULT_BASE_URL = "https://mirrors.sdu.edu.cn/ncnn_modelzoo/";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;

    private ModelDownloader() {
    }

    public interface ProgressListener {
        void onProgress(String file, long downloadedBytes, long totalBytes, long speedBytesPerSec);
    }

    public static File ensureModel(Context context, String modelName) throws IOException {
        return ensureModel(context, modelName, null);
    }

    public static File ensureModel(Context context, String modelName, ProgressListener listener) throws IOException {
        if (context == null) {
            throw new IOException("Context is null");
        }
        if (modelName == null || modelName.trim().isEmpty()) {
            throw new IOException("Model name is empty");
        }

        File modelDir = resolveModelDir(context, modelName);
        if (modelDir == null) {
            throw new IOException("Failed to resolve model dir");
        }
        if (!modelDir.exists() && !modelDir.mkdirs()) {
            throw new IOException("Failed to create model dir: " + modelDir.getAbsolutePath());
        }

        String baseUrl = DEFAULT_BASE_URL + modelName + "/";
        Log.i(TAG, "ensureModel modelName=" + modelName + " modelDir=" + modelDir.getAbsolutePath() + " baseUrl=" + baseUrl);

        File modelJson = new File(modelDir, "model.json");
        if (!modelJson.exists() || modelJson.length() == 0) {
            downloadUrlToFile(baseUrl + "model.json", modelJson, listener, "model.json");
        }

        Set<String> files = new HashSet<>();
        JSONObject json;
        try {
            json = new JSONObject(readAll(modelJson));
        } catch (org.json.JSONException e) {
            throw new IOException("Invalid model.json", e);
        }
        collectFileRefs(json, files);
        files.add("model.json");

        String preCheck = checkFiles(modelDir, files);
        if (preCheck == null) {
            Log.i(TAG, "Model already complete, skip download. modelDir=" + modelDir.getAbsolutePath());
            logModelSummary(modelDir, files);
            return modelDir;
        }
        Log.w(TAG, "Model incomplete before download: " + preCheck);

        long totalBytes = 0;
        long downloadedBytes = 0;
        for (String rel : files) {
            File out = new File(modelDir, rel);
            if (out.exists() && out.length() > 0) {
                long localSize = out.length();
                Long remoteSize = tryGetRemoteSize(baseUrl + rel);
                if (remoteSize != null && remoteSize > 0 && localSize != remoteSize) {
                    Log.w(TAG, "Size mismatch, will re-download: " + rel + " local=" + localSize + " remote=" + remoteSize);
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                } else {
                    Log.i(TAG, "Skip existing file: " + rel + " size=" + localSize + (remoteSize != null ? " remote=" + remoteSize : ""));
                    continue;
                }
            }
            File parent = out.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Failed to create dir: " + parent.getAbsolutePath());
            }
            long[] result = downloadUrlToFile(baseUrl + rel, out, listener, rel);
            if (result[1] > 0) {
                totalBytes += result[1];
            }
            downloadedBytes += result[0];
            if (listener != null) {
                listener.onProgress(rel, downloadedBytes, totalBytes, 0);
            }
        }

        // Optional common files some models expect but may not be listed in model.json.
        String[] optional = new String[] {
                "vocab.txt",
                "vocab.json",
                "tokenizer.json",
                "tokenizer.model",
                "merges.txt"
        };
        for (String rel : optional) {
            File out = new File(modelDir, rel);
            if (out.exists() && out.length() > 0) {
                Log.i(TAG, "Optional exists: " + rel + " size=" + out.length());
                continue;
            }
            tryDownloadOptional(baseUrl + rel, out, listener, rel);
        }

        // Final check: prevent native crash due to missing/empty files.
        String finalCheck = checkModelLikelyRunnable(modelDir, files, modelName);
        logModelSummary(modelDir, files);
        if (finalCheck != null) {
            Log.e(TAG, "Model still incomplete after download: " + finalCheck);
            throw new IOException(finalCheck);
        }

        return modelDir;
    }

    private static File resolveModelDir(Context context, String modelName) {
        File modelFile = new File(modelName);
        if (modelFile.isAbsolute()) {
            return modelFile;
        }
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            return null;
        }
        return new File(base, "ncnn_models/" + modelName);
    }

    private static String checkFiles(File modelDir, Set<String> expected) {
        if (modelDir == null) {
            return "模型目录为空";
        }
        if (expected == null || expected.isEmpty()) {
            return "期望文件列表为空";
        }
        List<String> missing = new ArrayList<>();
        List<String> empty = new ArrayList<>();
        for (String rel : expected) {
            if (rel == null || rel.trim().isEmpty()) {
                continue;
            }
            File f = new File(modelDir, rel);
            if (!f.exists()) {
                missing.add(rel);
            } else if (f.length() <= 0) {
                empty.add(rel);
            }
        }
        if (missing.isEmpty() && empty.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("模型文件不完整 ");
        if (!missing.isEmpty()) {
            sb.append("缺失=").append(missing);
        }
        if (!empty.isEmpty()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append("空文件=").append(empty);
        }
        return sb.toString();
    }

    private static String checkModelLikelyRunnable(File modelDir, Set<String> expected, String modelName) {
        String fileCheck = checkFiles(modelDir, expected);
        if (fileCheck != null) {
            return fileCheck;
        }

        int paramCount = 0;
        int binCount = 0;
        for (String rel : expected) {
            if (rel == null) continue;
            String lower = rel.toLowerCase();
            if (lower.endsWith(".param")) paramCount++;
            if (lower.endsWith(".bin")) binCount++;
        }
        if (paramCount == 0 || binCount == 0) {
            return "模型缺少权重文件（需要至少包含 .param 和 .bin），param=" + paramCount + " bin=" + binCount;
        }

        // Tokenizer/vocab sanity check: at least one should exist.
        String[] tokenizerCandidates = new String[] {
                "vocab.txt",
                "vocab.json",
                "tokenizer.json",
                "tokenizer.model",
                "merges.txt"
        };
        boolean hasTokenizer = false;
        for (String rel : tokenizerCandidates) {
            File f = new File(modelDir, rel);
            if (f.exists() && f.length() > 0) {
                hasTokenizer = true;
                break;
            }
        }
        if (!hasTokenizer) {
            return "模型缺少分词/词表文件（未找到 vocab/tokenizer/merges），请检查镜像是否包含这些文件。model=" + modelName;
        }

        // Lightweight .param text magic check (ncnn text param starts with 7767517)
        for (String rel : expected) {
            if (rel == null) continue;
            String lower = rel.toLowerCase();
            if (!lower.endsWith(".param")) continue;
            File f = new File(modelDir, rel);
            String head = readHeadAsciiSafe(f, 16);
            if (head != null && !head.contains("7767517")) {
                Log.w(TAG, "Param file header unexpected: file=" + rel + " head=\"" + head + "\"");
            }
        }

        return null;
    }

    private static String readHeadAsciiSafe(File file, int maxBytes) {
        if (file == null || !file.exists() || file.length() <= 0) {
            return null;
        }
        int len = (int) Math.min(file.length(), maxBytes);
        byte[] data = new byte[len];
        int read = 0;
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            while (read < len) {
                int n = in.read(data, read, len - read);
                if (n < 0) break;
                read += n;
            }
        } catch (IOException e) {
            return null;
        }
        try {
            return new String(data, 0, read, java.nio.charset.StandardCharsets.US_ASCII)
                    .replace("\r", "")
                    .replace("\n", "");
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean looksLikeHtmlOrError(String head) {
        if (head == null) {
            return false;
        }
        String h = head.trim().toLowerCase();
        if (h.isEmpty()) {
            return false;
        }
        if (h.startsWith("<!doctype") || h.startsWith("<html") || h.contains("<html") || h.contains("doctype html")) {
            return true;
        }
        if (h.startsWith("{") && (h.contains("\"error\"") || h.contains("\"message\""))) {
            return true;
        }
        if (h.contains("access denied") || h.contains("forbidden") || h.contains("not found") || h.contains("error")) {
            int printable = 0;
            for (int i = 0; i < head.length(); i++) {
                char c = head.charAt(i);
                if (c >= 0x20 && c <= 0x7e) printable++;
            }
            return printable > (head.length() * 3 / 4);
        }
        return false;
    }

    private static void logModelSummary(File modelDir, Set<String> expected) {
        if (modelDir == null || expected == null) {
            return;
        }
        List<String> lines = new ArrayList<>();
        long total = 0;
        for (String rel : expected) {
            if (rel == null) continue;
            File f = new File(modelDir, rel);
            long size = f.exists() ? f.length() : -1;
            if (size > 0) total += size;
            lines.add(rel + "=" + size);
        }
        Log.i(TAG, "Model summary dir=" + modelDir.getAbsolutePath() + " files=" + lines.size() + " totalBytes=" + total);
        int limit = Math.min(lines.size(), 40);
        for (int i = 0; i < limit; i++) {
            Log.i(TAG, "  " + lines.get(i));
        }
        if (lines.size() > limit) {
            Log.i(TAG, "  ... (" + (lines.size() - limit) + " more)");
        }
    }

    private static void collectFileRefs(Object value, Set<String> out) {
        if (value == null) {
            return;
        }
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            java.util.Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                String key = it.next();
                collectFileRefs(obj.opt(key), out);
            }
        } else if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            for (int i = 0; i < arr.length(); i++) {
                collectFileRefs(arr.opt(i), out);
            }
        } else if (value instanceof String) {
            String s = (String) value;
            if (isFileRef(s)) {
                out.add(s);
            }
        }
    }

    private static boolean isFileRef(String s) {
        if (s == null) {
            return false;
        }
        String lower = s.toLowerCase();
        return lower.endsWith(".param")
                || lower.endsWith(".bin")
                || lower.endsWith(".txt")
                || lower.endsWith(".json");
    }

    private static long[] downloadUrlToFile(String url, File out, ProgressListener listener, String name) throws IOException {
        HttpURLConnection conn = null;
        long total = -1;
        long downloaded = 0;
        long startMs = System.currentTimeMillis();
        Log.i(TAG, "Download start: " + name + " url=" + url + " -> " + out.getAbsolutePath());
        File tmp = new File(out.getAbsolutePath() + ".part");
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " for " + url);
            }
            Log.i(TAG, "HTTP " + code + " contentType=" + conn.getContentType() + " contentLen=" + conn.getContentLengthLong() + " finalUrl=" + conn.getURL());
            total = conn.getContentLengthLong();
            try (BufferedInputStream in = new BufferedInputStream(conn.getInputStream());
                 BufferedOutputStream outStream = new BufferedOutputStream(new FileOutputStream(tmp))) {
                byte[] buf = new byte[8192];
                int n;
                long lastTime = System.currentTimeMillis();
                long lastBytes = 0;
                while ((n = in.read(buf)) >= 0) {
                    outStream.write(buf, 0, n);
                    downloaded += n;
                    if (listener != null) {
                        long now = System.currentTimeMillis();
                        if (now - lastTime >= 300) {
                            long deltaBytes = downloaded - lastBytes;
                            long speed = (deltaBytes * 1000L) / Math.max(1L, now - lastTime);
                            listener.onProgress(name, downloaded, total, speed);
                            lastTime = now;
                            lastBytes = downloaded;
                        }
                    }
                }
                if (listener != null) {
                    listener.onProgress(name, downloaded, total, 0);
                }
            }
            if (total > 0 && downloaded != total) {
                throw new IOException("Download incomplete: " + name + " downloaded=" + downloaded + " expected=" + total);
            }

            if (name != null && name.toLowerCase().endsWith(".bin")) {
                String head = readHeadAsciiSafe(tmp, 256);
                if (looksLikeHtmlOrError(head)) {
                    throw new IOException("Downloaded .bin looks like HTML/text error: " + name + " head=" + head);
                }
            }

            if (!tmp.renameTo(out)) {
                // fallback copy then delete
                copyFile(tmp, out);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (IOException e) {
            // Clean up partial downloads.
            if (tmp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            if (out.exists() && out.length() == 0) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
            Log.e(TAG, "Download failed: " + name + " url=" + url + " err=" + e.getMessage());
            throw e;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        long cost = Math.max(1, System.currentTimeMillis() - startMs);
        Log.i(TAG, "Download done: " + name + " bytes=" + downloaded + "/" + total + " costMs=" + cost + " -> " + out.getAbsolutePath());
        return new long[] {downloaded, total};
    }

    private static void tryDownloadOptional(String url, File out, ProgressListener listener, String name) throws IOException {
        HttpURLConnection conn = null;
        File tmp = new File(out.getAbsolutePath() + ".part");
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code == 404) {
                Log.i(TAG, "Optional not found (404): " + name + " url=" + url);
                return;
            }
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " for " + url);
            }
            Log.i(TAG, "HTTP " + code + " contentType=" + conn.getContentType() + " contentLen=" + conn.getContentLengthLong() + " finalUrl=" + conn.getURL());
            Log.i(TAG, "Optional download start: " + name + " url=" + url + " -> " + out.getAbsolutePath());
            try (BufferedInputStream in = new BufferedInputStream(conn.getInputStream());
                 BufferedOutputStream outStream = new BufferedOutputStream(new FileOutputStream(tmp))) {
                byte[] buf = new byte[8192];
                int n;
                long downloaded = 0;
                long total = conn.getContentLengthLong();
                long lastTime = System.currentTimeMillis();
                long lastBytes = 0;
                while ((n = in.read(buf)) >= 0) {
                    outStream.write(buf, 0, n);
                    downloaded += n;
                    if (listener != null) {
                        long now = System.currentTimeMillis();
                        if (now - lastTime >= 300) {
                            long deltaBytes = downloaded - lastBytes;
                            long speed = (deltaBytes * 1000L) / Math.max(1L, now - lastTime);
                            listener.onProgress(name, downloaded, total, speed);
                            lastTime = now;
                            lastBytes = downloaded;
                        }
                    }
                }
                if (listener != null) {
                    listener.onProgress(name, downloaded, total, 0);
                }
            }

            // For optional files, if server provides length, enforce it too.
            long total = conn.getContentLengthLong();
            if (total > 0 && tmp.length() != total) {
                throw new IOException("Optional download incomplete: " + name + " downloaded=" + tmp.length() + " expected=" + total);
            }

            if (name != null && name.toLowerCase().endsWith(".bin")) {
                String head = readHeadAsciiSafe(tmp, 256);
                if (looksLikeHtmlOrError(head)) {
                    throw new IOException("Optional .bin looks like HTML/text error: " + name + " head=" + head);
                }
            }

            if (!tmp.renameTo(out)) {
                copyFile(tmp, out);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            Log.i(TAG, "Optional download done: " + name + " size=" + out.length());
        } catch (IOException e) {
            // Clean up partial optional downloads.
            if (tmp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            if (out.exists() && out.length() == 0) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
            Log.w(TAG, "Optional download failed: " + name + " url=" + url + " err=" + e.getMessage());
            throw e;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static Long tryGetRemoteSize(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("HEAD");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            long len = conn.getContentLengthLong();
            return len > 0 ? len : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        if (src == null || dst == null) {
            throw new IOException("copyFile: null");
        }
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst, false)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
    }

    private static String readAll(File file) throws IOException {
        byte[] data = new byte[(int) file.length()];
        int read = 0;
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
        }
        return new String(data, 0, read, java.nio.charset.StandardCharsets.UTF_8);
    }
}
