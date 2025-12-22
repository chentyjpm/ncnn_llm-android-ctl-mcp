#pragma once

#include <functional>
#include <string>
#include <unordered_map>
#include <vector>

#include <jni.h>
#include <nlohmann/json.hpp>

using nlohmann::json;

// Initialize with JavaVM from JNI_OnLoad.
void android_tool_bridge_init(JavaVM* vm);

// Set the Java bridge object (global ref is kept).
void android_tool_bridge_set(JNIEnv* env, jobject bridge);

// OpenAI function tools exposed to the model.
std::vector<json> make_android_tools();

// Router for tool execution.
std::unordered_map<std::string, std::function<json(const json&)>> make_android_router();

