#include "utils/tokenizer/unigram_tokenizer.h"
#include <iostream>

int main(int argc, char** argv) {
    if (argc < 2) {
        std::cerr << "Usage: " << argv[0] << " unigram_model.txt [text]\n";
        return 1;
    }
    std::string model_path = argv[1];
    std::string text = (argc >= 3) ? argv[2] : "Hello 世界! 안녕하세요 こんにちは ▁xxxx abc ";

    SpecialTokensConfig spec;
    spec.bos_token = "<s>";
    spec.eos_token = "</s>";
    spec.unk_token = "<unk>";
    spec.pad_token = "<pad>";
    spec.mask_token = "<mask>";

    auto tokenizer = UnigramTokenizer::LoadFromFile(model_path, spec,
                                                    /*add_special_if_missing=*/true,
                                                    /*fallback_to_chars=*/true,
                                                    /*unk_penalty=*/-10.0);

    auto ids = tokenizer.encode(text, /*add_bos=*/true, /*add_eos=*/true);

    std::cout << "Encoded IDs: ";
    for (size_t i = 0; i < ids.size(); ++i) {
        std::cout << ids[i] << (i + 1 == ids.size() ? '\n' : ' ');
    }

    std::string detok = tokenizer.decode(ids, /*skip_special_tokens=*/true);
    std::cout << "Decoded: " << detok << "\n";
    return 0;
}