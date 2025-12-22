#pragma once
#include <string>
#include <vector>
#include <unordered_map>
#include <optional>
#include <cstdint>
#include <mutex>

struct SpecialTokensConfig {
    std::optional<std::string> bos_token;
    std::optional<std::string> eos_token;
    std::optional<std::string> unk_token;
    std::optional<std::string> sep_token;
    std::optional<std::string> pad_token;
    std::optional<std::string> cls_token;
    std::optional<std::string> mask_token;
};

struct SpecialTokenIds {
    int bos_id = -1;
    int eos_id = -1;
    int unk_id = -1;
    int sep_id = -1;
    int pad_id = -1;
    int cls_id = -1;
    int mask_id = -1;
};

class UnigramTokenizer {
public:
    // 从 unigram 模型文件加载：每行 "token log_prob"
    // add_special_if_missing: 若特殊 token 不在模型中，则追加到 vocab 末尾
    // fallback_to_chars: 当没有任何词匹配时，回退到逐字符（UTF-8 codepoint）；若字符也不在词表，则使用 unk_id
    // unk_penalty: 当使用 UNK 回退时在 DP 中使用的罚分（log 概率）
    static UnigramTokenizer LoadFromFile(const std::string& model_path,
                                         const SpecialTokensConfig& spec,
                                         bool add_special_if_missing = true,
                                         bool fallback_to_chars = true,
                                         double unk_penalty = -10.0);

    std::vector<int> encode(const std::string& text,
                            bool add_bos = false,
                            bool add_eos = false,
                            bool add_cls = false,
                            bool add_sep = false) const;

    std::string decode(const std::vector<int>& ids, bool skip_special_tokens = true) const;

    // 属性访问
    size_t vocab_size() const { return id_to_token_.size(); }
    const std::vector<std::string>& id_to_token() const { return id_to_token_; }
    const std::unordered_map<std::string, int>& token_to_id() const { return token_to_id_; }
    const SpecialTokenIds& special_ids() const { return special_ids_; }
    bool fallback_to_chars() const { return fallback_to_chars_; }
    double unk_penalty() const { return unk_penalty_; }

    // 非拷贝，支持移动（避免 mutex 造成的已删除拷贝构造）
    UnigramTokenizer(const UnigramTokenizer&) = delete;
    UnigramTokenizer& operator=(const UnigramTokenizer&) = delete;
    UnigramTokenizer(UnigramTokenizer&& other) noexcept;
    UnigramTokenizer& operator=(UnigramTokenizer&& other) noexcept;

private:
    UnigramTokenizer() = default;

    // 数据加载
    static void LoadModel(const std::string& model_path,
                          std::vector<std::string>& tokens,
                          std::vector<double>& scores);
    static std::unordered_map<std::string, int> BuildTokenToId(const std::vector<std::string>& id_to_token);

    void EnsureSpecialTokens(const SpecialTokensConfig& spec, bool add_if_missing);

    // 预分词（SentencePiece 空白标记风格）
    static std::vector<std::string> PretokenizeSentencePiece(const std::string& text);

    // UTF-8 工具
    static bool IsAsciiSpace(unsigned char c);
    static bool IsUnicodeSpace(uint32_t cp);
    static bool NextUtf8(const std::string& s, size_t& i, uint32_t& cp, size_t& cp_len);

    // Trie 构建与匹配（按字节）
    void BuildTrie();
    void AddToTrie(const std::string& token, int token_id);
    // 返回从 pos 开始所有匹配到的 token_id 以及对应长度（字节）
    void MatchAt(const std::string& s, size_t pos, std::vector<std::pair<int,int>>& out_matches) const;

    // 对单个 piece 做 Viterbi 分词，返回 token 字符串序列（带缓存）
    const std::vector<std::string>& SegmentPieceCached(const std::string& piece) const;
    std::vector<std::string> SegmentPiece(const std::string& piece) const;

    // 将 token 序列映射到 id（支持字符级回退）
    void TokensToIds(const std::vector<std::string>& tokens, std::vector<int>& out) const;

private:
    // Trie 节点（字节级），为了性能采用固定 256 路
    struct TrieNode {
        int next[256];
        int token_id; // 终止 token id；-1 表示非终止
        TrieNode() : token_id(-1) {
            for (int i = 0; i < 256; ++i) next[i] = -1;
        }
    };

    std::vector<std::string> id_to_token_;
    std::unordered_map<std::string, int> token_to_id_;
    std::vector<double> token_logprob_; // 与 id_to_token_ 对齐

    // Trie
    std::vector<TrieNode> trie_;

    SpecialTokenIds special_ids_;
    bool fallback_to_chars_ = true;
    double unk_penalty_ = -10.0;

    // 简单线程安全缓存：piece -> segmentation tokens
    mutable std::unordered_map<std::string, std::vector<std::string>> piece_cache_;
    mutable std::mutex cache_mu_;
};