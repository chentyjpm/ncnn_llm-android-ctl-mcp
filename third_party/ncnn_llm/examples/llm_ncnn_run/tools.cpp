#include "tools.h"

#include "ncnn_llm_gpt.h"
#include "android_tool_bridge.h"

#include <algorithm>
#include <unordered_set>

std::string tool_name_from_openai_tool(const json& tool) {
    if (!tool.is_object()) return {};
    if (!tool.contains("function") || !tool["function"].is_object()) return {};
    return tool["function"].value("name", "");
}

std::vector<json> merge_tools_by_name(const std::vector<json>& base, const std::vector<json>& extra) {
    std::vector<json> out;
    out.reserve(base.size() + extra.size());
    std::unordered_set<std::string> seen;
    for (const auto& t : base) {
        std::string name = tool_name_from_openai_tool(t);
        if (!name.empty()) seen.insert(name);
        out.push_back(t);
    }
    for (const auto& t : extra) {
        std::string name = tool_name_from_openai_tool(t);
        if (!name.empty()) {
            if (seen.insert(name).second) out.push_back(t);
        } else {
            out.push_back(t);
        }
    }
    return out;
}

std::vector<json> make_builtin_tools() {
    auto android_tools = make_android_tools();
    return android_tools;
}

std::unordered_map<std::string, std::function<json(const json&)>> make_builtin_router() {
    std::unordered_map<std::string, std::function<json(const json&)>> tool_router;
    tool_router["random"] = [](const json& args) {
        int lo = args.value("floor", 0);
        int hi = args.value("ceiling", 1);
        if (lo > hi) std::swap(lo, hi);
        int val = lo + (rand() % (hi - lo + 1));
        return json{{"value", val}};
    };
    tool_router["add"] = [](const json& args) {
        int a = args.value("a", 0);
        int b = args.value("b", 0);
        return json{{"value", a + b}};
    };

    auto android_router = make_android_router();
    tool_router.insert(android_router.begin(), android_router.end());
    return tool_router;
}
