"""Shared token-window and aggregation contract for train/evaluate/serve."""

from __future__ import annotations

from typing import Any

import torch


def sliding_window_tokenize(
    text: str,
    tokenizer: Any,
    max_length: int = 512,
    stride: int = 50,
) -> list[dict[str, list[int]]]:
    """Tokenize content and add model special tokens to every window.

    ``stride`` is the number of content tokens shared by adjacent windows.
    Every returned field is right-padded to ``max_length``.
    """
    if text is None:
        text = ""
    if max_length <= 0:
        raise ValueError("max_length must be positive")

    special_count = int(
        tokenizer.num_special_tokens_to_add(pair=False)
        if hasattr(tokenizer, "num_special_tokens_to_add")
        else 2
    )
    content_length = max_length - special_count
    if content_length <= 0:
        raise ValueError("max_length does not leave room for content tokens")
    if stride < 0 or stride >= content_length:
        raise ValueError("stride must be in [0, max_length - special_tokens)")

    encoded = tokenizer(
        text,
        add_special_tokens=False,
        truncation=False,
        return_attention_mask=False,
        return_tensors=None,
    )
    content_ids = list(encoded.get("input_ids", []))
    step = content_length - stride
    windows: list[dict[str, list[int]]] = []
    start = 0

    while start < len(content_ids) or not windows:
        chunk = content_ids[start : start + content_length]
        if hasattr(tokenizer, "build_inputs_with_special_tokens"):
            ids = list(tokenizer.build_inputs_with_special_tokens(chunk))
        else:
            cls_id = getattr(tokenizer, "cls_token_id", 101)
            sep_id = getattr(tokenizer, "sep_token_id", 102)
            ids = [cls_id, *chunk, sep_id]
        if len(ids) > max_length:
            raise ValueError("tokenizer added more special tokens than reported")

        mask = [1] * len(ids)
        token_types: list[int] | None = None
        if hasattr(tokenizer, "create_token_type_ids_from_sequences"):
            token_types = list(tokenizer.create_token_type_ids_from_sequences(chunk))

        pad = max_length - len(ids)
        if pad:
            pad_id = getattr(tokenizer, "pad_token_id", None)
            if pad_id is None:
                pad_id = getattr(tokenizer, "eos_token_id", None) or 0
            ids += [int(pad_id)] * pad
            mask += [0] * pad
            if token_types is not None:
                token_types += [0] * pad

        window = {"input_ids": ids, "attention_mask": mask}
        if token_types is not None and len(token_types) == max_length:
            window["token_type_ids"] = token_types
        windows.append(window)

        if start + content_length >= len(content_ids):
            break
        start += step

    return windows


def aggregate_logits(window_logits: list[torch.Tensor]) -> torch.Tensor:
    """Elementwise max-pool logits from windows belonging to one hunk."""
    if not window_logits:
        raise ValueError("window_logits must not be empty")
    first_shape = window_logits[0].shape
    if any(item.shape != first_shape for item in window_logits):
        raise ValueError("all window logits must have the same shape")
    return torch.stack(window_logits, dim=0).max(dim=0).values


def windowed_model_logits(
    model: Any,
    tokenizer: Any,
    text: str,
    *,
    device: Any,
    max_length: int = 512,
    stride: int = 50,
) -> tuple[torch.Tensor, int]:
    """Run canonical windows and return max-pooled logits for one hunk."""
    windows = sliding_window_tokenize(
        text, tokenizer, max_length=max_length, stride=stride
    )
    logits: list[torch.Tensor] = []
    with torch.no_grad():
        for window in windows:
            tensors = {
                name: torch.tensor([values], device=device)
                for name, values in window.items()
            }
            logits.append(model(**tensors).logits.squeeze(0).detach().cpu())
    return aggregate_logits(logits), len(windows)
