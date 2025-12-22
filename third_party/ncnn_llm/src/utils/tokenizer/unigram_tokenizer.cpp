#include "unigram_tokenizer.h"
#include <fstream>
#include <sstream>
#include <algorithm>
#include <cctype>
#include <limits>
#include <stdexcept>

// --------- helpers ----------
static std::string Trim(const std::string& s) {
    size_t b = 0, e = s.size();
    while (b < e && std::isspace(static_cast<unsigned char>(s[b]))) ++b;
    while (e > b && std::isspace(static_cast<unsigned char>(s[e-1]))) --e;
    return s.substr(b, e - b);
}

// 解析一行：将最后一个空白之后视为 logprob，其余为 token
static bool ParseTokenAndScore(const std::string& line, std::string& token, double& score) {
    std::string s = Trim(line);
    if (s.empty()) return false;
    // 找到最后一个空白
    size_t pos = s.find_last_of(" \t");
    if (pos == std::string::npos)
        return false;
    std::string tok = s.substr(0, pos);
    std::string num = s.substr(pos + 1);
    tok = Trim(tok);
    num = Trim(num);
    if (tok.empty() || num.empty())
        return false;
    try {
        score = std::stod(num);
    } catch (...) {
        return false;
    }
    token = tok;
    return true;
}

// --------- UTF-8 ----------
bool UnigramTokenizer::IsAsciiSpace(unsigned char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\v';
}

bool UnigramTokenizer::IsUnicodeSpace(uint32_t cp) {
    if (cp <= 0x7F) return IsAsciiSpace(static_cast<unsigned char>(cp));
    switch (cp) {
        case 0x00A0: case 0x1680:
        case 0x2000: case 0x2001: case 0x2002: case 0x2003:
        case 0x2004: case 0x2005: case 0x2006: case 0x2007:
        case 0x2008: case 0x2009: case 0x200A:
        case 0x2028: case 0x2029:
        case 0x202F: case 0x205F: case 0x3000:
            return true;
        default:
            return false;
    }
}

bool UnigramTokenizer::NextUtf8(const std::string& s, size_t& i, uint32_t& cp, size_t& cp_len) {
    if (i >= s.size()) return false;
    unsigned char c0 = static_cast<unsigned char>(s[i]);
    if (c0 < 0x80) { cp = c0; cp_len = 1; ++i; return true; }
    else if ((c0 >> 5) == 0x6) {
        if (i + 1 >= s.size()) return false;
        unsigned char c1 = static_cast<unsigned char>(s[i+1]);
        if ((c1 & 0xC0) != 0x80) return false;
        cp = ((c0 & 0x1F) << 6) | (c1 & 0x3F); cp_len = 2; i += 2; return true;
    } else if ((c0 >> 4) == 0xE) {
        if (i + 2 >= s.size()) return false;
        unsigned char c1 = static_cast<unsigned char>(s[i+1]);
        unsigned char c2 = static_cast<unsigned char>(s[i+2]);
        if (((c1 & 0xC0) != 0x80) || ((c2 & 0xC0) != 0x80)) return false;
        cp = ((c0 & 0x0F) << 12) | ((c1 & 0x3F) << 6) | (c2 & 0x3F);
        cp_len = 3; i += 3; return true;
    } else if ((c0 >> 3) == 0x1E) {
        if (i + 3 >= s.size()) return false;
        unsigned char c1 = static_cast<unsigned char>(s[i+1]);
        unsigned char c2 = static_cast<unsigned char>(s[i+2]);
        unsigned char c3 = static_cast<unsigned char>(s[i+3]);
        if (((c1 & 0xC0) != 0x80) || ((c2 & 0xC0) != 0x80) || ((c3 & 0xC0) != 0x80)) return false;
        cp = ((c0 & 0x07) << 18) | ((c1 & 0x3F) << 12) | ((c2 & 0x3F) << 6) | (c3 & 0x3F);
        cp_len = 4; i += 4; return true;
    } else { return false; }
}

// --------- Pretokenizer ----------
std::vector<std::string> UnigramTokenizer::PretokenizeSentencePiece(const std::string& text) {
    static const std::string ws_mark = "▁";
    std::vector<std::string> out;
    std::string curr;
    curr.reserve(text.size());

    size_t i = 0;
    while (i < text.size()) {
        uint32_t cp; size_t len;
        if (!NextUtf8(text, i, cp, len)) break;
        if (IsUnicodeSpace(cp)) {
            if (!curr.empty()) {
                out.emplace_back(ws_mark + curr);
                curr.clear();
            }
        } else {
            curr.append(text, i - len, len);
        }
    }
    if (!curr.empty()) out.emplace_back(ws_mark + curr);
    return out;
}

// --------- Model load ----------
void UnigramTokenizer::LoadModel(const std::string& model_path,
                                 std::vector<std::string>& tokens,
                                 std::vector<double>& scores) {
    std::ifstream fin(model_path);
    if (!fin) throw std::runtime_error("Failed to open unigram model file: " + model_path);
    tokens.clear(); scores.clear();
    tokens.reserve(100000); scores.reserve(100000);
    std::string line;
    std::string tok; double sc;
    while (std::getline(fin, line)) {
        if (!ParseTokenAndScore(line, tok, sc)) continue;
        tokens.push_back(tok);
        scores.push_back(sc);
    }
    if (tokens.empty()) throw std::runtime_error("Unigram model is empty: " + model_path);
}

std::unordered_map<std::string, int> UnigramTokenizer::BuildTokenToId(const std::vector<std::string>& id_to_token) {
    std::unordered_map<std::string, int> m;
    m.reserve(id_to_token.size() * 2);
    for (int i = 0; i < static_cast<int>(id_to_token.size()); ++i) {
        m.emplace(id_to_token[i], i);
    }
    return m;
}

void UnigramTokenizer::EnsureSpecialTokens(const SpecialTokensConfig& spec, bool add_if_missing) {
    auto ensure = [&](const std::optional<std::string>& name, int& id_slot) {
        if (!name.has_value()) { id_slot = -1; return; }
        auto it = token_to_id_.find(*name);
        if (it != token_to_id_.end()) {
            id_slot = it->second;
            return;
        }
        if (!add_if_missing) { id_slot = -1; return; }
        id_slot = static_cast<int>(id_to_token_.size());
        id_to_token_.push_back(*name);
        token_to_id_.emplace(*name, id_slot);
        token_logprob_.push_back(-1e9); // 特殊 token 给个极低分，避免影响分词
        // 追加到 trie 与否无所谓（一般不会在正文中出现）；这里追加也没问题
        AddToTrie(*name, id_slot);
    };

    ensure(spec.bos_token, special_ids_.bos_id);
    ensure(spec.eos_token, special_ids_.eos_id);
    ensure(spec.unk_token, special_ids_.unk_id);
    ensure(spec.sep_token, special_ids_.sep_id);
    ensure(spec.pad_token, special_ids_.pad_id);
    ensure(spec.cls_token, special_ids_.cls_id);
    ensure(spec.mask_token, special_ids_.mask_id);
}

// --------- Trie ----------
void UnigramTokenizer::BuildTrie() {
    trie_.clear();
    trie_.emplace_back(); // root
    for (int id = 0; id < static_cast<int>(id_to_token_.size()); ++id) {
        AddToTrie(id_to_token_[id], id);
    }
}

void UnigramTokenizer::AddToTrie(const std::string& token, int token_id) {
    int node = 0;
    for (unsigned char c : token) {
        int nxt = trie_[node].next[c];
        if (nxt == -1) {
            nxt = static_cast<int>(trie_.size());
            trie_[node].next[c] = nxt;
            trie_.emplace_back();
        }
        node = nxt;
    }
    trie_[node].token_id = token_id; // 唯一 token
}

void UnigramTokenizer::MatchAt(const std::string& s, size_t pos, std::vector<std::pair<int,int>>& out_matches) const {
    out_matches.clear();
    int node = 0;
    for (size_t i = pos; i < s.size(); ++i) {
        unsigned char c = static_cast<unsigned char>(s[i]);
        int nxt = trie_[node].next[c];
        if (nxt == -1) break;
        node = nxt;
        if (trie_[node].token_id >= 0) {
            int tid = trie_[node].token_id;
            int len = static_cast<int>(i + 1 - pos);
            out_matches.emplace_back(tid, len);
        }
    }
}

// --------- Move semantics ----------
UnigramTokenizer::UnigramTokenizer(UnigramTokenizer&& other) noexcept
    : id_to_token_(std::move(other.id_to_token_)),
      token_to_id_(std::move(other.token_to_id_)),
      token_logprob_(std::move(other.token_logprob_)),
      trie_(std::move(other.trie_)),
      special_ids_(other.special_ids_),
      fallback_to_chars_(other.fallback_to_chars_),
      unk_penalty_(other.unk_penalty_) {
    std::lock_guard<std::mutex> lk(other.cache_mu_);
    piece_cache_ = std::move(other.piece_cache_);
}

UnigramTokenizer& UnigramTokenizer::operator=(UnigramTokenizer&& other) noexcept {
    if (this != &other) {
        std::scoped_lock lk(cache_mu_, other.cache_mu_);
        id_to_token_ = std::move(other.id_to_token_);
        token_to_id_ = std::move(other.token_to_id_);
        token_logprob_ = std::move(other.token_logprob_);
        trie_ = std::move(other.trie_);
        special_ids_ = other.special_ids_;
        fallback_to_chars_ = other.fallback_to_chars_;
        unk_penalty_ = other.unk_penalty_;
        piece_cache_ = std::move(other.piece_cache_);
    }
    return *this;
}

// --------- Load ----------
UnigramTokenizer UnigramTokenizer::LoadFromFile(const std::string& model_path,
                                                const SpecialTokensConfig& spec,
                                                bool add_special_if_missing,
                                                bool fallback_to_chars,
                                                double unk_penalty) {
    UnigramTokenizer tok;
    std::vector<std::string> toks;
    std::vector<double> scores;
    LoadModel(model_path, toks, scores);

    tok.id_to_token_ = std::move(toks);
    tok.token_logprob_ = std::move(scores);
    tok.token_to_id_ = BuildTokenToId(tok.id_to_token_);
    tok.fallback_to_chars_ = fallback_to_chars;
    tok.unk_penalty_ = unk_penalty;

    tok.BuildTrie();
    tok.EnsureSpecialTokens(spec, add_special_if_missing);
    // 显式 move，避免编译器尝试拷贝（MSVC）
    return std::move(tok);
}

// --------- Viterbi segmentation for one piece ----------
const std::vector<std::string>& UnigramTokenizer::SegmentPieceCached(const std::string& piece) const {
    {
        std::lock_guard<std::mutex> g(cache_mu_);
        auto it = piece_cache_.find(piece);
        if (it != piece_cache_.end()) return it->second;
    }
    auto seg = SegmentPiece(piece);
    {
        std::lock_guard<std::mutex> g(cache_mu_);
        auto [it, _] = piece_cache_.emplace(piece, std::move(seg));
        return it->second;
    }
}

std::vector<std::string> UnigramTokenizer::SegmentPiece(const std::string& piece) const {
    const int n = static_cast<int>(piece.size());
    if (n == 0) return {};

    // dp[i]: 从 i 开始的最佳 log 概率；back_len[i]：选择的 token 长度；back_tid[i]：token id（<0 表示未知回退）
    std::vector<double> dp(n + 1, -std::numeric_limits<double>::infinity());
    std::vector<int> back_len(n + 1, 0);
    std::vector<int> back_tid(n + 1, -1);
    dp[n] = 0.0;

    std::vector<std::pair<int,int>> matches; // (token_id, len)

    for (int i = n - 1; i >= 0; --i) {
        // 收集匹配
        MatchAt(piece, static_cast<size_t>(i), matches);

        if (!matches.empty()) {
            for (const auto& m : matches) {
                int tid = m.first;
                int len = m.second;
                double cand = token_logprob_[tid] + dp[i + len];
                if (cand > dp[i]) {
                    dp[i] = cand;
                    back_len[i] = len;
                    back_tid[i] = tid;
                }
            }
        } else {
            // 没有任何匹配：回退到单个 UTF-8 字符
            size_t pos = static_cast<size_t>(i);
            uint32_t cp; size_t cplen = 1;
            if (!NextUtf8(piece, pos, cp, cplen)) {
                // 字节异常，作为单字节处理
                cplen = 1;
            }
            std::string ch = piece.substr(i, static_cast<size_t>(cplen));
            auto itc = token_to_id_.find(ch);
            if (itc != token_to_id_.end()) {
                int tid = itc->second;
                double cand = token_logprob_[tid] + dp[i + static_cast<int>(cplen)];
                if (cand > dp[i]) {
                    dp[i] = cand;
                    back_len[i] = static_cast<int>(cplen);
                    back_tid[i] = tid;
                }
            } else {
                // 使用 UNK 罚分路径
                double cand = unk_penalty_ + dp[i + static_cast<int>(cplen)];
                if (cand > dp[i]) {
                    dp[i] = cand;
                    back_len[i] = static_cast<int>(cplen);
                    back_tid[i] = -1; // 未知
                }
            }
        }
    }

    // 回溯
    std::vector<std::string> tokens;
    tokens.reserve(n / 2 + 4);
    int i = 0;
    while (i < n) {
        int len = back_len[i];
        if (len <= 0) {
            // 安全兜底：前进一个 UTF-8 字符或字节
            size_t pos = static_cast<size_t>(i);
            uint32_t cp; size_t cplen = 1;
            if (!NextUtf8(piece, pos, cp, cplen)) cplen = 1;
            tokens.emplace_back(piece.substr(i, cplen));
            i += static_cast<int>(cplen);
            continue;
        }
        tokens.emplace_back(piece.substr(i, len));
        i += len;
    }
    return tokens;
}

// --------- Tokens -> IDs ----------
void UnigramTokenizer::TokensToIds(const std::vector<std::string>& tokens, std::vector<int>& out) const {
    out.reserve(out.size() + tokens.size());
    for (const auto& t : tokens) {
        auto it = token_to_id_.find(t);
        if (it != token_to_id_.end()) {
            out.push_back(it->second);
        } else if (fallback_to_chars_) {
            // t 可能是单个 UTF-8 字符（SegmentPiece 在 OOV 时按字符推进）
            size_t pos = 0; uint32_t cp; size_t cplen;
            if (!NextUtf8(t, pos, cp, cplen)) {
                cplen = t.size();
            }
            if (cplen == t.size()) {
                auto itc = token_to_id_.find(t);
                if (itc != token_to_id_.end()) {
                    out.push_back(itc->second);
                } else if (special_ids_.unk_id >= 0) {
                    out.push_back(special_ids_.unk_id);
                }
            } else {
                // 多字符的 OOV：逐字符分解
                size_t i = 0;
                while (i < t.size()) {
                    size_t j = i;
                    if (!NextUtf8(t, j, cp, cplen)) cplen = 1;
                    std::string ch = t.substr(i, cplen);
                    auto itc = token_to_id_.find(ch);
                    if (itc != token_to_id_.end()) out.push_back(itc->second);
                    else if (special_ids_.unk_id >= 0) out.push_back(special_ids_.unk_id);
                    i += cplen;
                }
            }
        } else if (special_ids_.unk_id >= 0) {
            out.push_back(special_ids_.unk_id);
        }
    }
}

// --------- Public API ----------
std::vector<int> UnigramTokenizer::encode(const std::string& text,
                                          bool add_bos,
                                          bool add_eos,
                                          bool add_cls,
                                          bool add_sep) const {
    std::vector<int> ids;
    ids.reserve(text.size() / 2 + 8);

    if (add_cls && special_ids_.cls_id >= 0) ids.push_back(special_ids_.cls_id);
    if (add_bos && special_ids_.bos_id >= 0) ids.push_back(special_ids_.bos_id);

    auto pieces = PretokenizeSentencePiece(text);
    for (const auto& p : pieces) {
        const auto& toks = SegmentPieceCached(p);
        TokensToIds(toks, ids);
    }

    if (add_sep && special_ids_.sep_id >= 0) ids.push_back(special_ids_.sep_id);
    if (add_eos && special_ids_.eos_id >= 0) ids.push_back(special_ids_.eos_id);
    return ids;
}

std::string UnigramTokenizer::decode(const std::vector<int>& ids, bool skip_special_tokens) const {
    std::string s;
    s.reserve(ids.size() * 3);
    for (int id : ids) {
        if (id < 0 || id >= static_cast<int>(id_to_token_.size())) continue;
        const std::string& tok = id_to_token_[id];

        bool is_special =
            id == special_ids_.bos_id || id == special_ids_.eos_id ||
            id == special_ids_.unk_id || id == special_ids_.sep_id ||
            id == special_ids_.pad_id || id == special_ids_.cls_id ||
            id == special_ids_.mask_id;

        if (skip_special_tokens && is_special) continue;

        s += tok;
    }

    // 将 "▁" 替换为 ' ' 并去掉开头空格
    if (!s.empty()) {
        std::string out;
        out.reserve(s.size());
        size_t i = 0;
        while (i < s.size()) {
            uint32_t cp; size_t len;
            if (!NextUtf8(s, i, cp, len)) break;
            if (cp == 0x2581) out.push_back(' ');
            else out.append(s, i - len, len);
        }
        size_t b = 0;
        while (b < out.size() && out[b] == ' ') ++b;
        if (b > 0) out.erase(0, b);
        return out;
    }
    return s;
}