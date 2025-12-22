#include <jni.h>
#include <string>
#include <thread>
#include <atomic>
#include <mutex>
#include <exception>
#include <android/log.h>

#include "ncnn_llm_gpt.h"
#include "openai_server.h"
#include "options.h"
#include "tools.h"
#include "mcp.h"

namespace {
std::atomic<bool> g_server_running(false);
const char* kTag = "ncnn_llm_jni";
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_ncnn_1llm_1ctl_NcnnLlmBridge_hello(JNIEnv* env, jclass clazz) {
    (void)clazz;
    return env->NewStringUTF("ncnn_llm_jni loaded");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_ncnn_1llm_1ctl_NcnnLlmBridge_startOpenAiServer(
        JNIEnv* env, jclass clazz, jstring modelPath, jint port, jboolean useVulkan) {
    (void)clazz;
    if (modelPath == nullptr) {
        return JNI_FALSE;
    }

    const char* model_path_c = env->GetStringUTFChars(modelPath, nullptr);
    std::string model_path = model_path_c ? model_path_c : "";
    env->ReleaseStringUTFChars(modelPath, model_path_c);

    if (model_path.empty()) {
        return JNI_FALSE;
    }

    bool expected = false;
    if (!g_server_running.compare_exchange_strong(expected, true)) {
        return JNI_FALSE;
    }

    int server_port = port > 0 ? port : 8080;
    bool vulkan = (useVulkan == JNI_TRUE);

    std::thread([model_path, server_port, vulkan]() {
        try {
            Options opt;
            opt.mode = RunMode::OpenAI;
            opt.model_path = model_path;
            opt.use_vulkan = vulkan;
            opt.port = server_port;
            opt.enable_builtin_tools = true;
            opt.mcp_server_cmdline.clear();

            McpState mcp = init_mcp(opt);
            ncnn_llm_gpt model(opt.model_path, opt.use_vulkan);
            std::vector<json> builtin_tools = opt.enable_builtin_tools ? make_builtin_tools() : std::vector<json>();
            auto builtin_router = make_builtin_router();
            std::mutex mcp_mutex;

            run_openai_server(opt, model, builtin_tools, builtin_router, mcp, mcp_mutex);
        } catch (const std::exception& e) {
            __android_log_print(ANDROID_LOG_ERROR, kTag, "Server init failed: %s", e.what());
        } catch (...) {
            __android_log_print(ANDROID_LOG_ERROR, kTag, "Server init failed: unknown error");
        }
        g_server_running.store(false);
    }).detach();

    return JNI_TRUE;
}
