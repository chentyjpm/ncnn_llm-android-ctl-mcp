package com.example.ncnn_llm_ctl;

public final class NcnnLlmBridge {
    static {
        System.loadLibrary("ncnn_llm_jni");
    }

    private NcnnLlmBridge() {
    }

    public static native String hello();

    public static native boolean startOpenAiServer(String modelPath, int port, boolean useVulkan);

    public static boolean startOpenAiServerAutoDownload(android.content.Context context,
                                                        String modelName,
                                                        int port,
                                                        boolean useVulkan) throws java.io.IOException {
        java.io.File modelDir = ModelDownloader.ensureModel(context, modelName);
        return startOpenAiServer(modelDir.getAbsolutePath(), port, useVulkan);
    }
}
