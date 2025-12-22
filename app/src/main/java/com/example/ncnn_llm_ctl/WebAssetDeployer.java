package com.example.ncnn_llm_ctl;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class WebAssetDeployer {
    private static final String TAG = "WebAssetDeployer";

    private WebAssetDeployer() {
    }

    public static File ensureWebRoot(Context context) throws IOException {
        if (context == null) {
            throw new IOException("Context is null");
        }

        File webRoot = new File(context.getFilesDir(), "web");
        if (!webRoot.exists() && !webRoot.mkdirs()) {
            throw new IOException("Failed to create web root: " + webRoot.getAbsolutePath());
        }

        // From assets (packaged via app/build.gradle sourceSets assets.srcDirs).
        copyAssetIfMissing(context.getAssets(), "index.html", new File(webRoot, "index.html"));
        patchPortHintIfNeeded(new File(webRoot, "index.html"));
        return webRoot;
    }

    private static void copyAssetIfMissing(AssetManager assets, String assetPath, File outFile) throws IOException {
        if (outFile.exists() && outFile.length() > 0) {
            Log.i(TAG, "Web asset exists: " + outFile.getAbsolutePath() + " size=" + outFile.length());
            return;
        }

        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create dir: " + parent.getAbsolutePath());
        }

        Log.i(TAG, "Copy web asset: " + assetPath + " -> " + outFile.getAbsolutePath());
        try (InputStream in = assets.open(assetPath);
             FileOutputStream out = new FileOutputStream(outFile)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
        Log.i(TAG, "Copy done: " + outFile.getAbsolutePath() + " size=" + outFile.length());
    }

    private static void patchPortHintIfNeeded(File htmlFile) {
        try {
            if (htmlFile == null || !htmlFile.exists() || htmlFile.length() <= 0) {
                return;
            }
            byte[] data = new byte[(int) htmlFile.length()];
            int read = 0;
            try (FileInputStream in = new FileInputStream(htmlFile)) {
                while (read < data.length) {
                    int n = in.read(data, read, data.length - read);
                    if (n < 0) break;
                    read += n;
                }
            }
            String s = new String(data, 0, read, StandardCharsets.UTF_8);
            if (!s.contains("localhost:8080") && !s.contains(":8080")) {
                return;
            }
            String patched = s.replace("localhost:8080", "localhost:18080")
                    .replace(":8080", ":18080");
            if (patched.equals(s)) {
                return;
            }
            try (FileOutputStream out = new FileOutputStream(htmlFile, false)) {
                out.write(patched.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            Log.i(TAG, "Patched port hint in " + htmlFile.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "Patch port hint failed: " + e.getMessage());
        }
    }
}
