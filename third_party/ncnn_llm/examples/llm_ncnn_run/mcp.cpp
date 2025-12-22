#include "mcp.h"

#include <iostream>

#ifdef __ANDROID__
#include <android/log.h>
static void mcpi(const std::string& s) { __android_log_print(ANDROID_LOG_INFO, "ncnn_llm_mcp", "%s", s.c_str()); }
static void mcpw(const std::string& s) { __android_log_print(ANDROID_LOG_WARN, "ncnn_llm_mcp", "%s", s.c_str()); }
#else
static void mcpi(const std::string& s) { std::cerr << s << "\n"; }
static void mcpw(const std::string& s) { std::cerr << s << "\n"; }
#endif

McpState init_mcp(const Options& opt) {
    McpState mcp;
    if (opt.mcp_server_cmdline.empty()) return mcp;

    mcp.client = std::make_shared<McpStdioClient>();
    mcp.client->set_timeout_ms(opt.mcp_timeout_ms);
    mcp.client->set_debug(opt.mcp_debug);
    mcp.client->set_transport(opt.mcp_transport == "jsonl" ? McpStdioClient::Transport::Jsonl : McpStdioClient::Transport::Lsp);

    std::string err;
    mcpi("[MCP] launching stdio server...");
    if (!mcp.client->start(opt.mcp_server_cmdline, &err)) {
        mcpw(std::string("Warning: failed to initialize MCP server: ") + err);
        mcp.client.reset();
        return mcp;
    }

    mcpi("[MCP] connected; listing tools...");
    std::string list_err;
    json tools = mcp.client->list_tools(&list_err);
    if (!list_err.empty()) {
        mcpw(std::string("Warning: MCP tools/list failed: ") + list_err);
        return mcp;
    }
    if (!tools.is_array()) return mcp;

    for (const auto& t : tools) {
        if (!t.is_object()) continue;
        std::string name = t.value("name", "");
        if (name.empty()) continue;
        mcp.tool_names.insert(name);
        json openai_tool = {
            {"type", "function"},
            {"function", {
                {"name", name},
                {"description", t.value("description", "")},
                {"parameters", t.value("inputSchema", json::object())}
            }}
        };
        mcp.openai_tools.push_back(std::move(openai_tool));
    }
    mcpi("Loaded " + std::to_string(mcp.openai_tools.size()) + " MCP tool(s) from stdio server.");
    return mcp;
}
