#include <iostream>
#include <string>
#include <vector>
#include <functional>
#include <algorithm>
#include <filesystem>

#include "nllb_600m.h"

namespace fs = std::filesystem;

struct Args {
    std::string model_dir = "./assets/nllb_600m";
    std::string source_lang = "eng_Latn";
    std::string target_lang = "zho_Hans";
    std::string text = "ncnn is the best edge-side neural network inference framework";
    bool use_vulkan = false;
};

void print_usage(const char* prog) {
    std::cout << "Usage: " << prog << " [--model-dir DIR] [--vocab FILE] [--merges FILE]\n"
              << "                 [--src TOKEN] [--tgt TOKEN] [--text TEXT] [--vulkan]\n\n"
              << "Defaults:\n"
              << "  --model-dir    ./assets/nllb_600m\n"
              << "  --src          eng_Latn\n"
              << "  --tgt          zho_Hans\n"
              << "  --text         \"ncnn is the best edge-side neural network inference framework\"\n"
              << "  --vulkan       disabled by default\n";
}

Args parse_args(int argc, char** argv) {
    Args a;
    for (int i = 1; i < argc; ++i) {
        std::string k = argv[i];
        auto need_val = [&](const char* flag) {
            if (i + 1 >= argc) {
                std::cerr << flag << " requires a value\n";
                print_usage(argv[0]);
                std::exit(1);
            }
        };
        if (k == "--model-dir") {
            need_val("--model-dir");
            a.model_dir = argv[++i];
        } else if (k == "--src") {
            need_val("--src");
            a.source_lang = argv[++i];
        } else if (k == "--tgt") {
            need_val("--tgt");
            a.target_lang = argv[++i];
        } else if (k == "--text") {
            need_val("--text");
            a.text = argv[++i];
        } else if (k == "--vulkan") {
            a.use_vulkan = true;
        } else if (k == "--help" || k == "-h") {
            print_usage(argv[0]);
            std::exit(0);
        } else {
            std::cerr << "Unknown arg: " << k << "\n";
            print_usage(argv[0]);
            std::exit(1);
        }
    }
    return a;
}

int main(int argc, char** argv) {
    Args args = parse_args(argc, argv);

    // Build model file paths
    const fs::path model_dir(args.model_dir);
    const std::string embed_param   = (model_dir / "embed.ncnn.param").string();
    const std::string embed_bin     = (model_dir / "embed.ncnn.bin").string();
    const std::string encoder_param = (model_dir / "encoder_noembed.ncnn.param").string();
    const std::string encoder_bin   = (model_dir / "encoder_noembed.ncnn.bin").string();
    const std::string decoder_param = (model_dir / "decoder_noembed.ncnn.param").string();
    const std::string decoder_bin   = (model_dir / "decoder_noembed.ncnn.bin").string();
    const std::string vocab_file    = (model_dir / "vocab.txt").string();
    const std::string merges_file   = (model_dir / "merges.txt").string();

    std::cout << "Model files:\n"
              << "  embed_param   = " << embed_param << "\n"
              << "  embed_bin     = " << embed_bin << "\n"
              << "  encoder_param = " << encoder_param << "\n"
              << "  encoder_bin   = " << encoder_bin << "\n"
              << "  decoder_param = " << decoder_param << "\n"
              << "  decoder_bin   = " << decoder_bin << "\n"
              << "Tokenizer files:\n"
              << "  vocab         = " << vocab_file << "\n"
              << "  merges        = " << merges_file << "\n"
              << "Settings:\n"
              << "  src_lang      = " << args.source_lang << "\n"
              << "  tgt_lang      = " << args.target_lang << "\n"
              << "  use_vulkan    = " << (args.use_vulkan ? "true" : "false") << "\n\n";

    try {
        // Construct translator
        nllb_600m translator(embed_param,
                             embed_bin,
                             encoder_param,
                             encoder_bin,
                             decoder_param,
                             decoder_bin,
                             vocab_file,
                             merges_file,
                             args.use_vulkan);

        // 1) Synchronous translation
        std::cout << "[Sync] Input: " << args.text << "\n";
        std::string out = translator.translate(args.text, args.source_lang, args.target_lang);
        std::cout << "[Sync] Output: " << out << "\n\n";

        // 2) Streaming translation
        std::cout << "[Stream] Input: " << args.text << "\n";
        std::cout << "[Stream] Output: ";
        bool ok = translator.translate(args.text, args.source_lang, args.target_lang,
            [](const std::string& chunk) {
                std::cout << chunk << std::flush;
            });
        std::cout << "\n[Stream] Status: " << (ok ? "success" : "failed") << "\n";

    } catch (const std::exception& e) {
        std::cerr << "Exception: " << e.what() << "\n";
        return 2;
    }

    return 0;
}