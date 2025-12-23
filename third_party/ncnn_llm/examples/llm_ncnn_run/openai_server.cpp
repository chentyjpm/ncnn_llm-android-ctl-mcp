#include "openai_server.h"

#include "json_utils.h"
#include "tools.h"
#include "util.h"

#include <filesystem>
#include <httplib.h>
#include <iostream>
#include <optional>
#include <unordered_set>

namespace {
#ifdef __ANDROID__
#include <android/log.h>
static void logi(const std::string& s) { __android_log_print(ANDROID_LOG_INFO, "ncnn_llm_openai", "%s", s.c_str()); }
static void logw(const std::string& s) { __android_log_print(ANDROID_LOG_WARN, "ncnn_llm_openai", "%s", s.c_str()); }
static void loge(const std::string& s) { __android_log_print(ANDROID_LOG_ERROR, "ncnn_llm_openai", "%s", s.c_str()); }
#else
static void logi(const std::string& s) { std::cerr << s << "\n"; }
static void logw(const std::string& s) { std::cerr << s << "\n"; }
static void loge(const std::string& s) { std::cerr << s << "\n"; }
#endif

static std::string truncate_for_log(const std::string& s, size_t max_bytes) {
    if (s.size() <= max_bytes) return s;
    return s.substr(0, max_bytes) + "...(truncated,len=" + std::to_string(s.size()) + ")";
}

static json format_tool_calls(const std::string& resp_id, const std::vector<json>& calls) {
    json out = json::array();
    for (size_t i = 0; i < calls.size(); ++i) {
        const json& call = calls[i];
        std::string name = call.value("name", "");
        json args = call.value("arguments", json::object());
        json tc = {
            {"id", std::string("call-") + resp_id + "-" + std::to_string(i)},
            {"type", "function"},
            {"function", {
                {"name", name},
                {"arguments", args}
            }}
        };
        out.push_back(std::move(tc));
    }
    return out;
}
} // namespace

int run_openai_server(const Options& opt,
                      ncnn_llm_gpt& model,
                      const std::vector<json>& builtin_tools,
                      const std::unordered_map<std::string, std::function<json(const json&)>>& builtin_router,
                      const McpState& mcp,
                      std::mutex& mcp_mutex) {
    std::mutex model_mutex;
    httplib::Server server;

    const std::string web_root = opt.web_root.empty() ? std::string("./examples/web") : opt.web_root;
    if (!server.set_mount_point("/", web_root)) {
        logw("Failed to mount web root: " + web_root);
    }

    // Ensure opening "/" shows the demo page.
    server.Get("/", [&](const httplib::Request&, httplib::Response& res) {
        res.set_redirect("/index.html");
    });

    server.Get("/health", [&](const httplib::Request&, httplib::Response& res) {
        json j = {{"ok", true}};
        res.set_content(j.dump(), "application/json");
    });

    server.Post("/v1/chat/completions", [&](const httplib::Request& req, httplib::Response& res) {
        logi("HTTP /v1/chat/completions from=" + req.remote_addr + " bodyBytes=" + std::to_string(req.body.size()));
        json body;
        try {
            body = json::parse(req.body);
        } catch (const std::exception& e) {
            res.status = 400;
            res.set_content(make_error(400, std::string("Invalid JSON: ") + e.what()).dump(), "application/json");
            return;
        }

        if (!body.contains("messages") || !body["messages"].is_array()) {
            res.status = 400;
            res.set_content(make_error(400, "`messages` must be an array").dump(), "application/json");
            return;
        }

        auto messages = parse_messages(body["messages"]);
        if (messages.empty() || messages[0].role != "system") {
            messages.insert(messages.begin(), Message{"system", "You are a helpful assistant."});
        }

        std::vector<json> tools;
        if (body.contains("tools") && body["tools"].is_array()) {
            for (const auto& t : body["tools"]) {
                if (t.is_object()) tools.push_back(t);
            }
        }

        if (!builtin_tools.empty()) {
            tools = merge_tools_by_name(tools, builtin_tools);
        }

        if (!mcp.openai_tools.empty() && opt.mcp_merge_tools) {
            tools = merge_tools_by_name(tools, mcp.openai_tools);
        } else if (!mcp.openai_tools.empty() && tools.empty()) {
            tools = mcp.openai_tools;
        }

        std::unordered_set<std::string> mcp_tools_in_prompt;
        if (!mcp.openai_tools.empty()) {
            mcp_tools_in_prompt.reserve(tools.size());
            for (const auto& t : tools) {
                std::string name = tool_name_from_openai_tool(t);
                if (!name.empty() && mcp.tool_names.find(name) != mcp.tool_names.end()) {
                    mcp_tools_in_prompt.insert(std::move(name));
                }
            }
        }

        {
            std::string model_name = body.value("model", std::string("qwen3-0.6b"));
            bool stream = body.value("stream", false);
            bool enable_thinking = body.value("enable_thinking", false);
            logi("Request model=" + model_name +
                 " stream=" + std::string(stream ? "true" : "false") +
                 " thinking=" + std::string(enable_thinking ? "true" : "false") +
                 " tools=" + std::to_string(tools.size()) +
                 " mcpToolsInPrompt=" + std::to_string(mcp_tools_in_prompt.size()));
        }

        GenerateConfig cfg;
        cfg.max_new_tokens = body.value("max_tokens", cfg.max_new_tokens);
        cfg.temperature = body.value("temperature", cfg.temperature);
        cfg.top_p = body.value("top_p", cfg.top_p);
        cfg.top_k = body.value("top_k", cfg.top_k);
        cfg.repetition_penalty = body.value("repetition_penalty", cfg.repetition_penalty);
        cfg.beam_size = body.value("beam_size", cfg.beam_size);
        cfg.debug = body.value("debug", false);
        if (body.contains("do_sample") && body["do_sample"].is_boolean()) {
            cfg.do_sample = body["do_sample"].get<bool>() ? 1 : 0;
        } else if (cfg.temperature <= 0.0f) {
            cfg.do_sample = 0;
        }

        auto artifacts_out = std::make_shared<std::vector<json>>();
        auto artifacts_seen = std::make_shared<std::unordered_set<std::string>>();
        auto tool_trace = std::make_shared<std::vector<std::string>>();
        auto tool_history = std::make_shared<std::vector<json>>();
        auto tool_calls_out = std::make_shared<std::vector<json>>();
        std::string tool_mode = body.value("tool_mode", std::string("execute")); // execute | emit
        bool emit_tool_calls = (tool_mode == "emit");
        std::string mcp_image_delivery = body.value("mcp_image_delivery", std::string("base64"));
        if (mcp_image_delivery != "file" && mcp_image_delivery != "base64" && mcp_image_delivery != "both") {
            mcp_image_delivery = "file";
        }

        if (!tools.empty() && emit_tool_calls) {
            cfg.return_tool_calls = true;
            cfg.on_tool_call = [tool_trace, tool_calls_out](const json& call) {
                std::string name = call.value("name", "");
                if (!name.empty()) tool_trace->push_back(name);
                tool_calls_out->push_back(call);
            };
        } else if (!tools.empty()) {
            auto mcp_client = mcp.client;
            const size_t max_tool_string_bytes = opt.mcp_max_string_bytes_in_prompt;
            auto allowed = std::make_shared<std::unordered_set<std::string>>(mcp_tools_in_prompt);
            cfg.tool_callback = [&, mcp_client, allowed, max_tool_string_bytes, artifacts_out, artifacts_seen, tool_trace, tool_history, mcp_image_delivery](
                                    const json& call) -> json {
                std::string name = call.value("name", "");
                json args = call.value("arguments", json::object());
                if (name.empty()) {
                    return json{{"error", "missing tool name"}, {"call", call}};
                }

                {
                    std::string arg_preview = truncate_for_log(args.dump(), 800);
                    logi("Tool call name=" + name + " args=" + arg_preview);
                    tool_trace->push_back(name);
                    tool_history->push_back(json{{"name", name}, {"arguments", args}});
                }

                if (!builtin_tools.empty()) {
                    if (auto it = builtin_router.find(name); it != builtin_router.end()) {
                        int64_t t0 = now_ms_epoch();
                        json result = it->second(args);
                        int64_t t1 = now_ms_epoch();
                        logi("Tool done (builtin) name=" + name + " ok=true costMs=" + std::to_string(t1 - t0) +
                             " result=" + truncate_for_log(result.dump(), 800));
                        tool_history->back()["ok"] = true;
                        tool_history->back()["result"] = result;
                        tool_history->back()["cost_ms"] = t1 - t0;
                        return json{{"result", result}, {"call", call}};
                    }
                }

                if (!mcp_client || allowed->find(name) == allowed->end()) {
                    logw("Tool rejected/unavailable name=" + name);
                    tool_history->back()["ok"] = false;
                    tool_history->back()["error"] = "tool not available";
                    return json{{"error", "tool not available"}, {"name", name}, {"call", call}};
                }

                json artifact_summaries = json::array();
                std::optional<std::string> forced_image_url;
                std::optional<std::string> forced_image_path;
                if (name == "sd_txt2img") {
                    if (mcp_image_delivery == "file" || mcp_image_delivery == "both") {
                        try {
                            std::filesystem::path outdir = std::filesystem::path(web_root) / "generated";
                            std::error_code ec;
                            std::filesystem::create_directories(outdir, ec);

                            std::string filename = "sd_txt2img_" + std::to_string(now_ms_epoch()) + ".png";
                            std::filesystem::path outpath = outdir / filename;
                            args["output"] = mcp_image_delivery;
                            args["out_path"] = outpath.string();
                            forced_image_url = std::string("/generated/") + filename;
                            forced_image_path = outpath.string();
                        } catch (...) {
                            args["output"] = mcp_image_delivery;
                        }
                    } else {
                        args["output"] = "base64";
                        args.erase("out_path");
                    }
                }

                std::string err;
                json result;
                {
                    std::lock_guard<std::mutex> lock(mcp_mutex);
                    int64_t t0 = now_ms_epoch();
                    result = mcp_client->call_tool(name, args, &err);
                    int64_t t1 = now_ms_epoch();
                    logi("Tool done (mcp) name=" + name + " costMs=" + std::to_string(t1 - t0) +
                         " err=" + truncate_for_log(err, 400) +
                         " result=" + truncate_for_log(result.is_null() ? "null" : result.dump(), 800));
                    tool_history->back()["cost_ms"] = t1 - t0;
                    tool_history->back()["ok"] = err.empty() && !result.is_null();
                    if (!err.empty()) tool_history->back()["error"] = err;
                    if (!result.is_null()) tool_history->back()["result"] = result;
                }
                if (!err.empty() || result.is_null()) {
                    return json{{"error", "mcp tools/call failed"}, {"detail", err}, {"call", call}};
                }

                if (forced_image_url) {
                    json a = {{"kind", "image"},
                              {"mime_type", "image/png"},
                              {"tool", name},
                              {"url", *forced_image_url}};
                    if (forced_image_path) a["path"] = *forced_image_path;
                    std::string k = image_artifact_key(a);
                    if (k.empty() || artifacts_seen->insert(k).second) {
                        artifacts_out->push_back(a);
                        artifact_summaries.push_back(json{{"kind", "image"}, {"url", *forced_image_url}});
                    }
                }

                std::vector<json> images;
                std::unordered_set<size_t> seen_b64;
                collect_mcp_image_artifacts(result, images, seen_b64);
                for (auto& img : images) {
                    img["tool"] = name;
                    if (forced_image_url && !img.contains("url")) img["url"] = *forced_image_url;
                    std::string k = image_artifact_key(img);
                    if (k.empty() || artifacts_seen->insert(k).second) {
                        artifacts_out->push_back(img);

                        json summary = {{"kind", "image"}};
                        if (img.contains("url")) summary["url"] = img["url"];
                        artifact_summaries.push_back(std::move(summary));
                    }
                }

                json safe_result = strip_image_payloads(result);
                safe_result = truncate_large_strings(safe_result, max_tool_string_bytes);
                json resp = json{{"result", safe_result}, {"call", call}};
                if (!artifact_summaries.empty()) resp["artifacts"] = artifact_summaries;
                return resp;
            };
        }

        bool stream = body.value("stream", false);
        bool enable_thinking = body.value("enable_thinking", false);
        std::string model_name = body.value("model", std::string("qwen3-0.6b"));
        std::string prompt = apply_chat_template(messages, tools, true, enable_thinking);
        logi("Prompt bytes=" + std::to_string(prompt.size()) + " preview=" + truncate_for_log(prompt, 300));
        std::string resp_id = make_response_id();

        if (stream) {
            res.set_header("Content-Type", "text/event-stream");
            res.set_header("Cache-Control", "no-cache");
            res.set_header("Connection", "keep-alive");

            res.set_chunked_content_provider(
                "text/event-stream",
                [&, prompt, cfg, resp_id, model_name, artifacts_out, tool_trace, tool_history, tool_calls_out](size_t, httplib::DataSink& sink) mutable {
                    std::lock_guard<std::mutex> lock(model_mutex);

                    auto send_tool_trace_line = [&](const std::string& line) {
                        json chunk = {
                            {"id", resp_id},
                            {"object", "chat.completion.chunk"},
                            {"model", model_name},
                            {"choices", json::array({
                                json{
                                    {"index", 0},
                                    {"delta", json::object()},
                                    {"finish_reason", nullptr}
                                }
                            })},
                            {"tool_trace_line", line}
                        };
                        std::string data = "data: " + chunk.dump() + "\n\n";
                        sink.write(data.data(), data.size());
                    };

                    // Wrap tool callback to emit tool trace lines immediately in streaming mode.
                    if (cfg.tool_callback) {
                        auto orig = cfg.tool_callback;
                        cfg.tool_callback = [orig, send_tool_trace_line](const json& call) -> json {
                            try {
                                std::string name = call.value("name", "");
                                if (!name.empty()) {
                                    send_tool_trace_line(name);
                                }
                                json r = orig(call);
                                return r;
                            } catch (const std::exception& e) {
                                send_tool_trace_line(std::string("<= tool_callback exception: ") + e.what());
                                throw;
                            }
                        };
                    }

                    if (cfg.return_tool_calls) {
                        auto orig = cfg.on_tool_call;
                        cfg.on_tool_call = [orig, send_tool_trace_line](const json& call) {
                            try {
                                std::string name = call.value("name", "");
                                if (!name.empty()) {
                                    send_tool_trace_line(name);
                                }
                                if (orig) orig(call);
                            } catch (const std::exception& e) {
                                send_tool_trace_line(std::string("<= on_tool_call exception: ") + e.what());
                                throw;
                            }
                        };
                    }

                    auto ctx = model.prefill(prompt);
                    model.generate(ctx, cfg, [&](const std::string& token) {
                        std::string safe_token = sanitize_utf8(token);
                        json chunk = {
                            {"id", resp_id},
                            {"object", "chat.completion.chunk"},
                            {"model", model_name},
                            {"choices", json::array({
                                json{
                                    {"index", 0},
                                    {"delta", {{"role", "assistant"}, {"content", safe_token}}},
                                    {"finish_reason", nullptr}
                                }
                            })}
                        };
                        std::string data = "data: " + chunk.dump() + "\n\n";
                        sink.write(data.data(), data.size());
                    });

                    json done_chunk = {
                        {"id", resp_id},
                        {"object", "chat.completion.chunk"},
                        {"model", model_name},
                        {"choices", json::array({
                            json{
                                {"index", 0},
                                {"delta", json::object()},
                                {"finish_reason", tool_calls_out->empty() ? "stop" : "tool_calls"}
                            }
                        })}
                    };
                    if (!tool_calls_out->empty()) {
                        done_chunk["tool_calls"] = format_tool_calls(resp_id, *tool_calls_out);
                    }
                    if (!artifacts_out->empty()) {
                        done_chunk["artifacts"] = *artifacts_out;
                    }
                    if (!tool_history->empty()) {
                        done_chunk["tool_history"] = *tool_history;
                    }
                    std::string end_data = "data: " + done_chunk.dump() + "\n\n";
                    sink.write(end_data.data(), end_data.size());

                    const char done[] = "data: [DONE]\n\n";
                    sink.write(done, sizeof(done) - 1);
                    return false;
                },
                [](bool) {});
            return;
        }

        std::string generated;
        {
            std::lock_guard<std::mutex> lock(model_mutex);
            auto ctx = model.prefill(prompt);
            model.generate(ctx, cfg, [&](const std::string& token) {
                generated += sanitize_utf8(token);
            });
        }

        if (!tool_calls_out->empty()) {
            json tool_calls = format_tool_calls(resp_id, *tool_calls_out);
            json resp = {
                {"id", resp_id},
                {"object", "chat.completion"},
                {"model", model_name},
                {"choices", json::array({
                    json{
                        {"index", 0},
                        {"message", {{"role", "assistant"}, {"content", ""}, {"tool_calls", tool_calls}}},
                        {"finish_reason", "tool_calls"}
                    }
                })},
                {"tool_calls", tool_calls},
                {"usage", {{"prompt_tokens", 0}, {"completion_tokens", 0}}}
            };
            if (!tool_trace->empty()) {
                resp["tool_trace"] = *tool_trace;
            }
            res.set_content(resp.dump(), "application/json");
            return;
        }

        json resp = {
            {"id", resp_id},
            {"object", "chat.completion"},
            {"model", model_name},
            {"choices", json::array({
                json{
                    {"index", 0},
                    {"message", {{"role", "assistant"}, {"content", generated}}},
                    {"finish_reason", "stop"}
                }
            })},
            {"usage", {{"prompt_tokens", 0}, {"completion_tokens", 0}}}
        };
        if (!artifacts_out->empty()) {
            resp["artifacts"] = *artifacts_out;
        }
        if (!tool_trace->empty()) {
            resp["tool_trace"] = *tool_trace;
        }
        if (!tool_history->empty()) {
            resp["tool_history"] = *tool_history;
        }

        res.set_content(resp.dump(), "application/json");
    });

    const int port = opt.port;
    std::cout << "llm_ncnn_run OpenAI-style API server listening on http://0.0.0.0:" << port << std::endl;
    std::cout << "POST /v1/chat/completions with OpenAI-format payloads." << std::endl;
    server.listen("0.0.0.0", port);

    return 0;
}
