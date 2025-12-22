#include <jni.h>
#include <string>
#include <thread>
#include <atomic>
#include <mutex>
#include <exception>
#include <cstring>
#include <dirent.h>
#include <sys/stat.h>
#include <android/log.h>

#include "ncnn_llm_gpt.h"
#include "openai_server.h"
#include "options.h"
#include "tools.h"
#include "mcp.h"

namespace {
std::atomic<bool> g_server_running(false);
const char* kTag = "ncnn_llm_jni";

static bool file_exists_and_nonempty(const std::string& path) {
    struct stat st;
    if (stat(path.c_str(), &st) != 0) return false;
    return S_ISREG(st.st_mode) && st.st_size > 0;
}

static bool dir_exists(const std::string& path) {
    struct stat st;
    if (stat(path.c_str(), &st) != 0) return false;
    return S_ISDIR(st.st_mode);
}

static void log_model_dir_summary(const std::string& model_dir) {
    DIR* dir = opendir(model_dir.c_str());
    if (!dir) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "opendir failed: %s", model_dir.c_str());
        return;
    }

    int total = 0;
    int param = 0;
    int bin = 0;
    int txt = 0;
    int json = 0;
    int logged = 0;

    while (dirent* ent = readdir(dir)) {
        const char* name = ent->d_name;
        if (!name) continue;
        if (strcmp(name, ".") == 0 || strcmp(name, "..") == 0) continue;

        total++;
        std::string n(name);
        auto ends_with = [&](const char* suf) {
            size_t sl = strlen(suf);
            if (n.size() < sl) return false;
            return n.compare(n.size() - sl, sl, suf) == 0;
        };

        if (ends_with(".param")) param++;
        if (ends_with(".bin")) bin++;
        if (ends_with(".txt")) txt++;
        if (ends_with(".json")) json++;

        if (logged < 40) {
            std::string p = model_dir + "/" + n;
            struct stat st;
            long long size = -1;
            if (stat(p.c_str(), &st) == 0) size = (long long)st.st_size;
            __android_log_print(ANDROID_LOG_INFO, kTag, "  file: %s size=%lld", p.c_str(), size);
            logged++;
        }
    }
    closedir(dir);

    __android_log_print(ANDROID_LOG_INFO, kTag,
                        "Model dir summary: dir=%s total=%d param=%d bin=%d txt=%d json=%d",
                        model_dir.c_str(), total, param, bin, txt, json);
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_ncnn_1llm_1ctl_NcnnLlmBridge_startOpenAiServerWithWebRoot(
        JNIEnv* env, jclass clazz, jstring modelPath, jint port, jboolean useVulkan, jstring webRootPath);

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

    // Backwards-compatible entry (no web root).
    jstring empty = env->NewStringUTF("");
    jboolean ok = Java_com_example_ncnn_1llm_1ctl_NcnnLlmBridge_startOpenAiServerWithWebRoot(
            env, clazz, modelPath, port, useVulkan, empty);
    env->DeleteLocalRef(empty);
    return ok;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_ncnn_1llm_1ctl_NcnnLlmBridge_startOpenAiServerWithWebRoot(
        JNIEnv* env, jclass clazz, jstring modelPath, jint port, jboolean useVulkan, jstring webRootPath) {
    (void)clazz;
    if (modelPath == nullptr) {
        return JNI_FALSE;
    }

    const char* model_path_c = env->GetStringUTFChars(modelPath, nullptr);
    std::string model_path = model_path_c ? model_path_c : "";
    env->ReleaseStringUTFChars(modelPath, model_path_c);

    std::string web_root;
    if (webRootPath != nullptr) {
        const char* web_root_c = env->GetStringUTFChars(webRootPath, nullptr);
        web_root = web_root_c ? web_root_c : "";
        env->ReleaseStringUTFChars(webRootPath, web_root_c);
    }

    if (model_path.empty()) {
        return JNI_FALSE;
    }

    __android_log_print(ANDROID_LOG_INFO, kTag,
                        "startOpenAiServer modelPath=%s port=%d useVulkan=%d webRoot=%s",
                        model_path.c_str(), (int)port, (useVulkan == JNI_TRUE ? 1 : 0), web_root.c_str());

    bool expected = false;
    if (!g_server_running.compare_exchange_strong(expected, true)) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "Server already running");
        return JNI_FALSE;
    }

    int server_port = port > 0 ? port : 18080;
    bool vulkan = (useVulkan == JNI_TRUE);

    std::thread([model_path, server_port, vulkan, web_root]() {
        try {
            if (!dir_exists(model_path)) {
                __android_log_print(ANDROID_LOG_ERROR, kTag, "Model dir not exists: %s", model_path.c_str());
                throw std::runtime_error("Model dir not exists");
            }
            log_model_dir_summary(model_path);
            if (!file_exists_and_nonempty(model_path + "/model.json")) {
                __android_log_print(ANDROID_LOG_WARN, kTag, "model.json missing/empty");
            }
            if (!web_root.empty()) {
                __android_log_print(ANDROID_LOG_INFO, kTag, "Using web root: %s", web_root.c_str());
            }

            Options opt;
            opt.mode = RunMode::OpenAI;
            opt.model_path = model_path;
            opt.use_vulkan = vulkan;
            opt.port = server_port;
            opt.enable_builtin_tools = true;
            opt.mcp_server_cmdline.clear();
            opt.web_root = web_root;

            McpState mcp = init_mcp(opt);
            __android_log_print(ANDROID_LOG_INFO, kTag, "Initializing model... useVulkan=%d", vulkan ? 1 : 0);
            ncnn_llm_gpt model(opt.model_path, opt.use_vulkan);
            __android_log_print(ANDROID_LOG_INFO, kTag, "Model initialized, starting HTTP server on %d", server_port);
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
