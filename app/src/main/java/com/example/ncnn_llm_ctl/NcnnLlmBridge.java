package com.example.ncnn_llm_ctl;

import android.util.Log;

public final class NcnnLlmBridge {
    private static final String TAG = "NcnnLlmBridge";

    static {
        System.loadLibrary("ncnn_llm_jni");
    }

    private NcnnLlmBridge() {
    }

    public static native String hello();

    public static native boolean startOpenAiServer(String modelPath, int port, boolean useVulkan);

    public static native boolean startOpenAiServerWithWebRoot(String modelPath, int port, boolean useVulkan, String webRootPath);

    public static native void registerAccessibilityToolBridge(AccessibilityToolBridge bridge);

    public static boolean startOpenAiServerAutoDownload(android.content.Context context,
                                                        String modelName,
                                                        int port,
                                                        boolean useVulkan) throws java.io.IOException {
        Log.i(TAG, "startOpenAiServerAutoDownload modelName=" + modelName + " port=" + port + " useVulkan=" + useVulkan);
        java.io.File modelDir = ModelDownloader.ensureModel(context, modelName);
        Log.i(TAG, "startOpenAiServerAutoDownload modelDir=" + modelDir.getAbsolutePath());
        return startOpenAiServer(modelDir.getAbsolutePath(), port, useVulkan);
    }
}
