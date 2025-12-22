#include "android_tool_bridge.h"

#include <android/log.h>
#include <mutex>

namespace {
JavaVM* g_vm = nullptr;
std::mutex g_mu;
jobject g_bridge = nullptr; // global ref
jclass g_bridge_class = nullptr;

jmethodID g_dump_ui = nullptr;
jmethodID g_global_action_by_name = nullptr;
jmethodID g_click_by_view_id = nullptr;
jmethodID g_click_by_text = nullptr;
jmethodID g_set_text_by_view_id = nullptr;

const char* kTag = "AndroidToolBridge";

JNIEnv* get_env(bool* did_attach) {
    *did_attach = false;
    if (!g_vm) return nullptr;
    JNIEnv* env = nullptr;
    jint rc = g_vm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (rc == JNI_OK) return env;
    if (rc == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            *did_attach = true;
            return env;
        }
    }
    return nullptr;
}

void detach_if_needed(bool did_attach) {
    if (did_attach && g_vm) {
        g_vm->DetachCurrentThread();
    }
}

json err(const std::string& message) {
    return json{{"ok", false}, {"error", message}};
}

bool ensure_methods(JNIEnv* env) {
    if (!env) return false;
    if (!g_bridge || !g_bridge_class) return false;
    if (g_dump_ui && g_global_action_by_name && g_click_by_view_id && g_click_by_text && g_set_text_by_view_id) {
        return true;
    }

    g_dump_ui = env->GetMethodID(g_bridge_class, "dumpUi", "()Ljava/lang/String;");
    g_global_action_by_name = env->GetMethodID(g_bridge_class, "globalActionByName", "(Ljava/lang/String;)Z");
    g_click_by_view_id = env->GetMethodID(g_bridge_class, "clickByViewId", "(Ljava/lang/String;)Z");
    g_click_by_text = env->GetMethodID(g_bridge_class, "clickByText", "(Ljava/lang/String;Z)Z");
    g_set_text_by_view_id = env->GetMethodID(g_bridge_class, "setTextByViewId", "(Ljava/lang/String;Ljava/lang/String;)Z");
    return g_dump_ui && g_global_action_by_name && g_click_by_view_id && g_click_by_text && g_set_text_by_view_id;
}

json tool_dump_ui(const json&) {
    std::lock_guard<std::mutex> lock(g_mu);
    bool did_attach = false;
    JNIEnv* env = get_env(&did_attach);
    if (!env) return err("JNI env not available");
    if (!ensure_methods(env)) {
        detach_if_needed(did_attach);
        return err("tool bridge not registered");
    }

    __android_log_print(ANDROID_LOG_INFO, kTag, "tool dump_ui()");
    auto jstr = (jstring)env->CallObjectMethod(g_bridge, g_dump_ui);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        detach_if_needed(did_attach);
        return err("dumpUi exception");
    }
    std::string out;
    if (jstr) {
        const char* c = env->GetStringUTFChars(jstr, nullptr);
        if (c) out = c;
        env->ReleaseStringUTFChars(jstr, c);
        env->DeleteLocalRef(jstr);
    }
    detach_if_needed(did_attach);
    __android_log_print(ANDROID_LOG_INFO, kTag, "tool dump_ui ok bytes=%d", (int)out.size());
    return json{{"ok", true}, {"dump", out}};
}

json tool_global_action(const json& args) {
    std::string name = args.value("name", "");
    if (name.empty()) return err("missing name");

    std::lock_guard<std::mutex> lock(g_mu);
    bool did_attach = false;
    JNIEnv* env = get_env(&did_attach);
    if (!env) return err("JNI env not available");
    if (!ensure_methods(env)) {
        detach_if_needed(did_attach);
        return err("tool bridge not registered");
    }

    __android_log_print(ANDROID_LOG_INFO, kTag, "tool global_action name=%s", name.c_str());
    jstring jname = env->NewStringUTF(name.c_str());
    jboolean ok = env->CallBooleanMethod(g_bridge, g_global_action_by_name, jname);
    env->DeleteLocalRef(jname);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        detach_if_needed(did_attach);
        return err("globalActionByName exception");
    }
    detach_if_needed(did_attach);
    __android_log_print(ANDROID_LOG_INFO, kTag, "tool global_action done ok=%d", ok ? 1 : 0);
    return json{{"ok", (bool)ok}, {"name", name}};
}

json tool_click_view_id(const json& args) {
    std::string view_id = args.value("view_id", "");
    if (view_id.empty()) return err("missing view_id");

    std::lock_guard<std::mutex> lock(g_mu);
    bool did_attach = false;
    JNIEnv* env = get_env(&did_attach);
    if (!env) return err("JNI env not available");
    if (!ensure_methods(env)) {
        detach_if_needed(did_attach);
        return err("tool bridge not registered");
    }

    __android_log_print(ANDROID_LOG_INFO, kTag, "tool click_view_id view_id=%s", view_id.c_str());
    jstring jv = env->NewStringUTF(view_id.c_str());
    jboolean ok = env->CallBooleanMethod(g_bridge, g_click_by_view_id, jv);
    env->DeleteLocalRef(jv);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        detach_if_needed(did_attach);
        return err("clickByViewId exception");
    }
    detach_if_needed(did_attach);
    __android_log_print(ANDROID_LOG_INFO, kTag, "tool click_view_id done ok=%d", ok ? 1 : 0);
    return json{{"ok", (bool)ok}, {"view_id", view_id}};
}

json tool_click_text(const json& args) {
    std::string text = args.value("text", "");
    bool contains = args.value("contains", true);
    if (text.empty()) return err("missing text");

    std::lock_guard<std::mutex> lock(g_mu);
    bool did_attach = false;
    JNIEnv* env = get_env(&did_attach);
    if (!env) return err("JNI env not available");
    if (!ensure_methods(env)) {
        detach_if_needed(did_attach);
        return err("tool bridge not registered");
    }

    __android_log_print(ANDROID_LOG_INFO, kTag, "tool click_text text=%s contains=%d", text.c_str(), contains ? 1 : 0);
    jstring jt = env->NewStringUTF(text.c_str());
    jboolean ok = env->CallBooleanMethod(g_bridge, g_click_by_text, jt, contains ? JNI_TRUE : JNI_FALSE);
    env->DeleteLocalRef(jt);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        detach_if_needed(did_attach);
        return err("clickByText exception");
    }
    detach_if_needed(did_attach);
    __android_log_print(ANDROID_LOG_INFO, kTag, "tool click_text done ok=%d", ok ? 1 : 0);
    return json{{"ok", (bool)ok}, {"text", text}, {"contains", contains}};
}

json tool_set_text_view_id(const json& args) {
    std::string view_id = args.value("view_id", "");
    std::string text = args.value("text", "");
    if (view_id.empty()) return err("missing view_id");

    std::lock_guard<std::mutex> lock(g_mu);
    bool did_attach = false;
    JNIEnv* env = get_env(&did_attach);
    if (!env) return err("JNI env not available");
    if (!ensure_methods(env)) {
        detach_if_needed(did_attach);
        return err("tool bridge not registered");
    }

    __android_log_print(ANDROID_LOG_INFO, kTag, "tool set_text_view_id view_id=%s textBytes=%d", view_id.c_str(), (int)text.size());
    jstring jv = env->NewStringUTF(view_id.c_str());
    jstring jt = env->NewStringUTF(text.c_str());
    jboolean ok = env->CallBooleanMethod(g_bridge, g_set_text_by_view_id, jv, jt);
    env->DeleteLocalRef(jv);
    env->DeleteLocalRef(jt);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        detach_if_needed(did_attach);
        return err("setTextByViewId exception");
    }
    detach_if_needed(did_attach);
    __android_log_print(ANDROID_LOG_INFO, kTag, "tool set_text_view_id done ok=%d", ok ? 1 : 0);
    return json{{"ok", (bool)ok}, {"view_id", view_id}};
}
} // namespace

void android_tool_bridge_init(JavaVM* vm) {
    g_vm = vm;
}

void android_tool_bridge_set(JNIEnv* env, jobject bridge) {
    if (!env) return;
    std::lock_guard<std::mutex> lock(g_mu);

    if (g_bridge) {
        env->DeleteGlobalRef(g_bridge);
        g_bridge = nullptr;
    }
    if (g_bridge_class) {
        env->DeleteGlobalRef(g_bridge_class);
        g_bridge_class = nullptr;
    }

    g_dump_ui = nullptr;
    g_global_action_by_name = nullptr;
    g_click_by_view_id = nullptr;
    g_click_by_text = nullptr;
    g_set_text_by_view_id = nullptr;

    if (!bridge) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "Bridge cleared");
        return;
    }

    g_bridge = env->NewGlobalRef(bridge);
    jclass local = env->GetObjectClass(bridge);
    g_bridge_class = (jclass)env->NewGlobalRef(local);
    env->DeleteLocalRef(local);

    bool ok = ensure_methods(env);
    __android_log_print(ANDROID_LOG_INFO, kTag, "Bridge set ok=%d", ok ? 1 : 0);
}

std::vector<json> make_android_tools() {
    auto dump_ui = json{
        {"type", "function"},
        {"function", {
            {"name", "dump_ui"},
            {"description", "获取当前屏幕所有UI结构（无障碍窗口树）。"},
            {"parameters", {{"type", "object"}, {"properties", json::object()}, {"required", json::array()}}}
        }}
    };

    auto global_action = json{
        {"type", "function"},
        {"function", {
            {"name", "global_action"},
            {"description", "执行系统全局动作（返回/桌面/最近任务/通知栏/快捷设置/电源菜单等）。"},
            {"parameters", {
                {"type", "object"},
                {"properties", {{"name", {{"type", "string"}, {"description", "动作名称（中文）"}}}}},
                {"required", json::array({"name"})}
            }}
        }}
    };

    auto click_view_id = json{
        {"type", "function"},
        {"function", {
            {"name", "click_view_id"},
            {"description", "通过 viewIdResourceName 点击控件（如 com.xxx:id/btn_ok）。"},
            {"parameters", {
                {"type", "object"},
                {"properties", {{"view_id", {{"type", "string"}, {"description", "控件 viewIdResourceName"}}}}},
                {"required", json::array({"view_id"})}
            }}
        }}
    };

    auto set_text_view_id = json{
        {"type", "function"},
        {"function", {
            {"name", "set_text_view_id"},
            {"description", "通过 viewIdResourceName 向输入框设置文本。"},
            {"parameters", {
                {"type", "object"},
                {"properties", {
                    {"view_id", {{"type", "string"}, {"description", "控件 viewIdResourceName"}}},
                    {"text", {{"type", "string"}, {"description", "要输入的文本"}}}
                }},
                {"required", json::array({"view_id", "text"})}
            }}
        }}
    };

    return {dump_ui, global_action, click_view_id, set_text_view_id};
}

std::unordered_map<std::string, std::function<json(const json&)>> make_android_router() {
    std::unordered_map<std::string, std::function<json(const json&)>> r;
    r["dump_ui"] = tool_dump_ui;
    r["global_action"] = tool_global_action;
    r["click_view_id"] = tool_click_view_id;
    r["click_text"] = tool_click_text;
    r["set_text_view_id"] = tool_set_text_view_id;
    return r;
}
