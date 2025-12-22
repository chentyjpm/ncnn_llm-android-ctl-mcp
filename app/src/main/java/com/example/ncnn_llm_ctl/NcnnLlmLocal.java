package com.example.ncnn_llm_ctl;

/**
 * Direct JNI inference (no HTTP).
 *
 * Important: call from a background thread (model load/inference is heavy).
 */
public final class NcnnLlmLocal {
    static {
        System.loadLibrary("ncnn_llm_jni");
    }

    private NcnnLlmLocal() {
    }

    public static native long create(String modelPath, boolean useVulkan);

    public static native void destroy(long handle);

    public static native String generate(long handle,
                                        String prompt,
                                        int maxNewTokens,
                                        float temperature,
                                        float topP,
                                        int topK);
}

