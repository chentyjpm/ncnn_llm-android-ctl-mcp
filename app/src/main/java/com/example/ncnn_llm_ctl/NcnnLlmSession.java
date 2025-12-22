package com.example.ncnn_llm_ctl;

import java.io.Closeable;

/**
 * Convenience wrapper for {@link NcnnLlmLocal}.
 *
 * Usage:
 * <pre>
 * try (NcnnLlmSession s = NcnnLlmSession.open(modelDir, false)) {
 *   String out = s.generate("你好", 128, 0.3f, 0.9f, 50);
 * }
 * </pre>
 */
public final class NcnnLlmSession implements Closeable {
    private long handle;

    private NcnnLlmSession(long handle) {
        this.handle = handle;
    }

    public static NcnnLlmSession open(String modelPath, boolean useVulkan) {
        long handle = NcnnLlmLocal.create(modelPath, useVulkan);
        if (handle == 0) {
            throw new RuntimeException("Failed to create session");
        }
        return new NcnnLlmSession(handle);
    }

    public synchronized String generate(String prompt,
                                        int maxNewTokens,
                                        float temperature,
                                        float topP,
                                        int topK) {
        if (handle == 0) {
            throw new IllegalStateException("Session is closed");
        }
        return NcnnLlmLocal.generate(handle, prompt, maxNewTokens, temperature, topP, topK);
    }

    @Override
    public synchronized void close() {
        if (handle != 0) {
            NcnnLlmLocal.destroy(handle);
            handle = 0;
        }
    }
}

