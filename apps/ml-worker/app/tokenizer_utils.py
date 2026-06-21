"""
CodeLens — Sliding window tokenizer (Issue #8).

Splits long texts into overlapping token windows for models with a
fixed max sequence length (CodeBERT: 512). Used at inference time when
a diff exceeds the model's context.

After running the model independently on each window, call
`aggregate_logits` to max-pool the per-window logits back into a single
logits vector of the same shape.

Exports:
    sliding_window_tokenize(text, tokenizer, max_length=512, stride=50)
    aggregate_logits(window_logits)
"""
from __future__ import annotations

from typing import Any

import torch


def sliding_window_tokenize(
    text: str,
    tokenizer: Any,
    max_length: int = 512,
    stride: int = 50,
) -> list[dict[str, list[int]]]:
    """
    Encode `text` and slice into overlapping windows.

    Each window is a dict with `input_ids` and `attention_mask` lists of
    length `max_length`. Windows overlap by `stride` tokens so that no
    token near a boundary is silently dropped.

    - text shorter than max_length (token count): returns a single
      window with the full token sequence (padded with attention_mask 0
      if shorter than max_length, but typically we don't pad here — the
      model handles dynamic padding at batch time).
    - text longer than max_length: returns ceil(N / (max_length - stride))
      windows, where N is the tokenized length.

    Empty text raises ValueError.
    """
    if text is None or text == "":
        raise ValueError("text must not be empty")

    if max_length <= 0:
        raise ValueError("max_length must be positive")
    if stride < 0 or stride >= max_length:
        raise ValueError("stride must be in [0, max_length)")

    # Encode without adding special tokens (handled per-window) to get a
    # clean token sequence we can slice. add_special_tokens=True is the
    # HF default; we keep it on so the model sees [CLS] ... [SEP].
    encoded = tokenizer(
        text,
        add_special_tokens=True,
        truncation=False,           # do NOT truncate — we slice ourselves
        return_attention_mask=True,
        return_tensors=None,        # return lists
    )
    input_ids: list[int] = encoded["input_ids"]
    attention_mask: list[int] = encoded["attention_mask"]

    n = len(input_ids)
    if n == 0:
        raise ValueError("tokenizer produced 0 tokens")

    if n <= max_length:
        # Single window. No padding here — the trainer / batcher handles it.
        return [{"input_ids": input_ids, "attention_mask": attention_mask}]

    # Step size between consecutive window starts.
    step = max_length - stride
    windows: list[dict[str, list[int]]] = []
    start = 0
    while start < n:
        end = min(start + max_length, n)
        ids = input_ids[start:end]
        mask = attention_mask[start:end]
        # Pad to max_length (right-padding with 0 / attention 0) so every
        # window has the same shape — the model's batched forward pass
        # requires equal-length tensors.
        pad = max_length - len(ids)
        if pad > 0:
            ids = ids + [tokenizer.pad_token_id] * pad
            mask = mask + [0] * pad
        windows.append({"input_ids": ids, "attention_mask": mask})
        if end == n:
            break
        start += step
    return windows


def aggregate_logits(window_logits: list[torch.Tensor]) -> torch.Tensor:
    """
    Max-pool a list of per-window logits into a single logits tensor.

    Each tensor in `window_logits` is expected to have the same shape
    (typically `[num_labels]`). Returns an elementwise max across windows.

    Empty input raises ValueError so callers fail loudly rather than
    silently returning a zero vector.
    """
    if not window_logits:
        raise ValueError("window_logits must not be empty")

    stacked = torch.stack(window_logits, dim=0)
    return stacked.max(dim=0).values