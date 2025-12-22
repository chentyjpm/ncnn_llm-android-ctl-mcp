from __future__ import annotations

"""
TorchScript Export & Inference
- Exports token embedding + LN separately from positional
- Exports *learned* positional embedding (e.g., NLLB) if present
- **Sinusoidal positional embedding is NOT exported**, handled in Python
- Device-safe masks; script with trace fallback on target device
- **Decoder starts with target language token**, with **eos blocked for first N steps**
- **Automatic fallback**: if output is empty, retry with `<s>, <lang>` seed
- Greedy decode uses only the exported TS modules + Python sinusoidal

Exports modules:
- embed.pt (token embed + ln/dropout)
- encoder_noembed.pt
- decoder_noembed.pt
"""

import os
import math
from typing import Optional, Tuple

import torch
import torch.nn as nn
import torch.nn.functional as F
from transformers import AutoModelForSeq2SeqLM, AutoTokenizer, AutoConfig


# ==========================================================
# TS‑friendly token Embedding with explicit scale
# ==========================================================
class EmbeddingWithScale(nn.Module):
    def __init__(self, num_embeddings: int, embedding_dim: int, scale: float = 1.0):
        super().__init__()
        self.weight = nn.Parameter(torch.empty(num_embeddings, embedding_dim))
        nn.init.normal_(self.weight, mean=0.0, std=embedding_dim ** -0.5)
        self.register_buffer("_scale", torch.tensor(float(scale)))

    def forward(self, input_ids: torch.Tensor) -> torch.Tensor:
        return F.embedding(input_ids, self.weight) * self._scale


# ==========================================================
# Learned Positional Embedding (clone of HF table; TS-friendly)
# This module CAN be scripted and exported
# ==========================================================
class LearnedPositionalEmbeddingTS(nn.Module):
    def __init__(self, weight: torch.Tensor, padding_idx: int, offset: int = 2):
        super().__init__()
        self.weight = nn.Parameter(weight.clone(), requires_grad=False)
        self.padding_idx = int(padding_idx)
        self.offset = int(offset)

    def forward(self, attention_mask: Optional[torch.Tensor], seq_len: int, ref: torch.Tensor) -> torch.Tensor:
        device, dtype = ref.device, ref.dtype
        if attention_mask is None:
            bsz = ref.size(0)
            attention_mask = torch.ones(bsz, seq_len, dtype=torch.long, device=device)
        # HF convention: positions = cumsum(mask) + padding_idx + offset; paddings keep padding_idx
        pos = torch.cumsum(attention_mask, dim=1) + (self.padding_idx + self.offset)
        pos = pos * attention_mask + (1 - attention_mask) * self.padding_idx
        max_idx = self.weight.size(0) - 1
        if max_idx >= 0:
            pos = torch.clamp(pos, 0, max_idx)
        return F.embedding(pos, self.weight)


# ==========================================================
# Sinusoidal Positional (fallback if model lacks learned positions)
# This module is used directly in Python, NOT exported
# ==========================================================
class SinusoidalPositionalEmbeddingTS(nn.Module):
    def __init__(self, d_model: int):
        super().__init__()
        self.d_model = d_model

    def forward(self, attention_mask: Optional[torch.Tensor], seq_len: int, ref: torch.Tensor) -> torch.Tensor:
        device, dtype = ref.device, ref.dtype
        half_dim = self.d_model // 2
        inv = torch.exp(
            torch.arange(0, half_dim, device=device, dtype=dtype) * (-(math.log(10000.0) / max(1, half_dim))))
        if attention_mask is None:
            bsz = ref.size(0)
            pos = torch.arange(1, seq_len + 1, device=device).unsqueeze(0).expand(bsz, -1)
        else:
            pos = torch.cumsum(attention_mask, dim=1) * attention_mask
        x = pos.unsqueeze(-1) * inv
        emb = torch.cat([torch.sin(x), torch.cos(x)], dim=-1)
        if emb.size(-1) < self.d_model:
            pad = torch.zeros(emb.size(0), emb.size(1), self.d_model - emb.size(-1), device=device, dtype=dtype)
            emb = torch.cat([emb, pad], dim=-1)
        if attention_mask is not None:
            emb = emb * attention_mask.unsqueeze(-1)
        return emb


# ==========================================================
# EmbedTS — REFACTORED
# This module only handles token embedding + optional LN & dropout
# ==========================================================
class EmbedTS(nn.Module):
    def __init__(
            self,
            enc_tok: EmbeddingWithScale,
    ):
        super().__init__()
        self.enc_tok = enc_tok

    def forward(self, input_ids: torch.Tensor) -> torch.Tensor:
        x = self.enc_tok(input_ids)
        return x


# ==========================================================
# Encoder (no-embed) — device-safe key padding masks
# ==========================================================
class EncoderNoEmbedTS(nn.Module):
    def __init__(self, encoder: nn.Module):
        super().__init__()
        self.layers = encoder.layers
        self.layer_norm = encoder.layer_norm

    def forward(self, hidden_states: torch.Tensor) -> torch.Tensor:
        x = hidden_states
        for layer in self.layers:
            out = layer(x, attention_mask=None, layer_head_mask=None)
            x = out[0] if isinstance(out, tuple) else out
        if self.layer_norm is not None:
            x = self.layer_norm(x)
        return x


# ==========================================================
# Decoder (no-embed) — device-safe self/cross masks + lm_head
# ==========================================================
class DecoderNoEmbedTS(nn.Module):
    def __init__(self, decoder: nn.Module, lm_head: nn.Module):
        super().__init__()
        self.layers = decoder.layers
        self.layer_norm = decoder.layer_norm
        self.lm_head = lm_head

    def forward(self, hidden_states: torch.Tensor, encoder_hidden_states: torch.Tensor,
                self_attn_mask: torch.Tensor) -> torch.Tensor:
        B, T, _ = hidden_states.size()
        x = hidden_states
        for layer in self.layers:
            out = layer(
                x,
                attention_mask=self_attn_mask,
                encoder_hidden_states=encoder_hidden_states,
                encoder_attention_mask=None,
                layer_head_mask=None,
                cross_attn_layer_head_mask=None,
                use_cache=False,
            )
            x = out[0] if isinstance(out, tuple) else out
        if self.layer_norm is not None:
            x = self.layer_norm(x)
        return self.lm_head(x)


# ==========================================================
# Export helpers (script; fallback trace on target device)
# REFACTORED
# ==========================================================
@torch.no_grad()
def export_torchscript(
        model_id: str, out_dir: str = "./export_ts", device: Optional[str] = None
) -> Tuple[str, str, str, Optional[str], Optional[str]]:
    os.makedirs(out_dir, exist_ok=True)
    if device is None:
        device = "cuda" if torch.cuda.is_available() else "cpu"

    model = AutoModelForSeq2SeqLM.from_pretrained(model_id).to(device).eval()
    enc, dec = model.model.encoder, model.model.decoder
    d_model = enc.embed_tokens.embedding_dim

    def build_plain_emb(hf_emb: nn.Module) -> EmbeddingWithScale:
        num_embeddings = int(hf_emb.weight.size(0))
        embedding_dim = int(hf_emb.weight.size(1))
        scale = float(getattr(hf_emb, "embed_scale", 1.0))
        plain = EmbeddingWithScale(num_embeddings, embedding_dim, scale).to(device)
        with torch.no_grad():
            plain.weight.copy_(hf_emb.weight)
            plain.weight.requires_grad_(False)
        return plain

    enc_plain = build_plain_emb(enc.embed_tokens)

    # Create refactored EmbedTS (tokens + ln/dropout only)
    embed_ts = EmbedTS(
        enc_plain,
    ).to(device).eval()

    enc_pos_path: Optional[str] = None
    dec_pos_path: Optional[str] = None

    enc_ts = EncoderNoEmbedTS(enc).to(device).eval()
    dec_ts = DecoderNoEmbedTS(dec, model.lm_head).to(device).eval()

    # Script and save
    embed_jit = torch.jit.trace(embed_ts, torch.randint(0, 100, (1, 8), dtype=torch.long, device=device))

    ex_h = torch.randn(1, 16, d_model, device=device)
    enc_jit = torch.jit.trace(enc_ts, ex_h)

    ex_dec = torch.randn(1, 8, d_model, device=device)
    ex_mem = torch.randn(1, 16, d_model, device=device)
    ex_dec_mask = torch.triu(torch.ones(8, 8, device=device) * float("-inf"), 1).unsqueeze(0).unsqueeze(0)
    dec_jit = torch.jit.trace(dec_ts, (ex_dec, ex_mem, ex_dec_mask))

    embed_path = os.path.join(out_dir, "embed.pt")
    encoder_path = os.path.join(out_dir, "encoder_noembed.pt")
    decoder_path = os.path.join(out_dir, "decoder_noembed.pt")

    embed_jit.save(embed_path)
    enc_jit.save(encoder_path)
    dec_jit.save(decoder_path)

    print(f"Saved: {embed_path}\nSaved: {encoder_path}\nSaved: {decoder_path}")
    if enc_pos_path: print(f"Saved: {enc_pos_path}")
    if dec_pos_path: print(f"Saved: {dec_pos_path}")

    return embed_path, encoder_path, decoder_path, enc_pos_path, dec_pos_path


# ==========================================================
# Greedy decoding using exported modules only (robust)
# REFACTORED
# ==========================================================
@torch.no_grad()
def scripted_greedy_decode(
        tokenizer: AutoTokenizer,
        embed_jit: torch.jit.ScriptModule,
        enc_jit: torch.jit.ScriptModule,
        dec_jit: torch.jit.ScriptModule,
        # Pass optional positional embedding modules (scripted or None)
        enc_pos_jit: Optional[torch.jit.ScriptModule],
        dec_pos_jit: Optional[torch.jit.ScriptModule],
        d_model: int,  # Need d_model for sinusoidal fallback
        text: str,
        src_lang: str,
        tgt_lang: str,
        max_new_tokens: int = 64,
        device: Optional[str] = None,
        block_eos_steps: int = 3,  # block </s> for first N steps
) -> str:
    if device is None:
        device = "cuda" if torch.cuda.is_available() else "cpu"

    # tokenizer setup
    if hasattr(tokenizer, "src_lang"): tokenizer.src_lang = src_lang
    if hasattr(tokenizer, "tgt_lang"): tokenizer.tgt_lang = tgt_lang

    if hasattr(tokenizer, "_build_translation_inputs"):
        enc_inputs = tokenizer._build_translation_inputs([text], return_tensors="pt", src_lang=src_lang,
                                                         tgt_lang=tgt_lang)
    else:
        enc_inputs = tokenizer([text], return_tensors="pt", padding=True, truncation=True)

    input_ids = enc_inputs["input_ids"].to(device)
    attention_mask = enc_inputs.get("attention_mask", torch.ones_like(input_ids)).to(device)

    # modules to device
    embed_jit = embed_jit.to(device)
    enc_jit = enc_jit.to(device)
    dec_jit = dec_jit.to(device)

    # Handle positional embedders
    enc_pos: nn.Module
    dec_pos: nn.Module


    enc_pos = SinusoidalPositionalEmbeddingTS(d_model).to(device).eval()
    dec_pos = SinusoidalPositionalEmbeddingTS(d_model).to(device).eval()

    # encoder run
    # Manual embedding combination
    enc_tok_emb = embed_jit(input_ids)
    enc_pos_emb = enc_pos(attention_mask, input_ids.size(1), enc_tok_emb)
    enc_emb = enc_tok_emb + enc_pos_emb

    memory = enc_jit(enc_emb)

    # resolve ids
    eos_id = tokenizer.eos_token_id or tokenizer.convert_tokens_to_ids("</s>") or 2

    def _decode_with_seed(seed_ids: torch.Tensor) -> Tuple[str, torch.Tensor]:
        dec_ids = seed_ids.clone()
        for step in range(max_new_tokens):
            dec_mask = torch.ones_like(dec_ids, device=device)

            # Manual embedding combination
            dec_tok_emb = embed_jit(dec_ids)
            dec_pos_emb = dec_pos(dec_mask, dec_ids.size(1), dec_tok_emb)
            dec_emb = dec_tok_emb + dec_pos_emb

            dec_mask = torch.triu(torch.ones(dec_ids.size(1), dec_ids.size(1), device=device) * float("-inf"), 1).unsqueeze(0).unsqueeze(0)

            logits = dec_jit(dec_emb, memory, dec_mask)
            last = logits[:, -1, :]
            # block eos in early steps to avoid empty output
            if step < block_eos_steps:
                last[:, eos_id] = float(-1e9)
            next_id = torch.argmax(last, dim=-1)
            dec_ids = torch.cat([dec_ids, next_id.unsqueeze(0)], dim=1)
            if int(next_id.item()) == int(eos_id) and step >= block_eos_steps:
                break
        # decode skipping the seed length
        out = tokenizer.batch_decode(dec_ids[:, seed_ids.size(1):], skip_special_tokens=True)[0].strip()
        return out, dec_ids

    # seed A: language token only (M2M/NLLB convention)
    lang_id = tokenizer.convert_tokens_to_ids(f"{tgt_lang}")
    seed_lang = torch.tensor([[2 ,int(lang_id)]], device=device, dtype=torch.long)

    out, dec_ids = _decode_with_seed(seed_lang)
    if out:
        return out

    # seed B (fallback): <s>, <lang>
    bos = tokenizer.bos_token_id or tokenizer.convert_tokens_to_ids("<s>") or 2
    seed_b = torch.tensor([[int(bos), int(lang_id)]], device=device, dtype=torch.long)
    out2, dec_ids2 = _decode_with_seed(seed_b)
    return out2


# ==========================================================
# Example
# REFACTORED
# ==========================================================
if __name__ == "__main__":
    model_id = "facebook/nllb-200-distilled-600M"
    # model_id = "facebook/bart-large" # Example of a model with sinusoidal
    device = "cuda" if torch.cuda.is_available() else "cpu"

    # Get d_model from config first for the greedy decoder's sinusoidal fallback
    config = AutoConfig.from_pretrained(model_id)
    d_model = getattr(config, "d_model", 1024)  # 1024 is for nllb-600m

    # Handle 5 return paths
    embed_path, encoder_path, decoder_path, enc_pos_path, dec_pos_path = \
        export_torchscript(model_id, out_dir="./export_ts", device=device)

    embed_jit = torch.jit.load(embed_path, map_location=device)
    enc_jit = torch.jit.load(encoder_path, map_location=device)
    dec_jit = torch.jit.load(decoder_path, map_location=device)

    # Load optional positional modules
    enc_pos_jit: Optional[torch.jit.ScriptModule] = None
    dec_pos_jit: Optional[torch.jit.ScriptModule] = None

    if enc_pos_path and os.path.exists(enc_pos_path):
        print(f"Loading exported learned encoder positions: {enc_pos_path}")
        enc_pos_jit = torch.jit.load(enc_pos_path, map_location=device)
    if dec_pos_path and os.path.exists(dec_pos_path):
        print(f"Loading exported learned decoder positions: {dec_pos_path}")
        dec_pos_jit = torch.jit.load(dec_pos_path, map_location=device)

    tok = AutoTokenizer.from_pretrained(model_id)
    text = "Hello world!"

    # Pass modules and d_model to decoder
    out = scripted_greedy_decode(
        tok,
        embed_jit,
        enc_jit,
        dec_jit,
        enc_pos_jit,
        dec_pos_jit,
        d_model,
        text,
        "eng_Latn",  # Source lang for NLLB
        "zho_Hans",  # Target lang for NLLB
        64,
        device
    )
    print("\n[Scripted] =>", out)
