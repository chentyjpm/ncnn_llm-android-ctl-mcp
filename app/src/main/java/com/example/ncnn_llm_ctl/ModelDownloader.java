package com.example.ncnn_llm_ctl;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

public final class ModelDownloader {
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

        long totalBytes = 0;
        long downloadedBytes = 0;
        for (String rel : files) {
            File out = new File(modelDir, rel);
            if (out.exists() && out.length() > 0) {
                continue;
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
                continue;
            }
            tryDownloadOptional(baseUrl + rel, out, listener, rel);
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

    private static boolean isLikelyReady(File modelDir) {
        File modelJson = new File(modelDir, "model.json");
        return modelJson.exists() && modelJson.length() > 0;
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
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " for " + url);
            }
            total = conn.getContentLengthLong();
            try (BufferedInputStream in = new BufferedInputStream(conn.getInputStream());
                 BufferedOutputStream outStream = new BufferedOutputStream(new FileOutputStream(out))) {
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
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return new long[] {downloaded, total};
    }

    private static void tryDownloadOptional(String url, File out, ProgressListener listener, String name) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code == 404) {
                return;
            }
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " for " + url);
            }
            try (BufferedInputStream in = new BufferedInputStream(conn.getInputStream());
                 BufferedOutputStream outStream = new BufferedOutputStream(new FileOutputStream(out))) {
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
        } catch (IOException e) {
            if (out.exists() && out.length() == 0) {
                // Clean up partial optional downloads.
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
            throw e;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
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
