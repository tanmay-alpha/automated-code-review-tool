"""Deterministic tests for special-token-safe sliding windows."""

from __future__ import annotations

from typing import Any

import pytest
import torch

from app.tokenizer_utils import aggregate_logits, sliding_window_tokenize


class FakeTokenizer:
    pad_token_id = 0
    cls_token_id = 101
    sep_token_id = 102

    def __call__(self, text: str, **_: Any) -> dict[str, list[int]]:
        return {"input_ids": [int(word[1:]) + 1_000 for word in text.split()]}

    def num_special_tokens_to_add(self, pair: bool = False) -> int:
        assert pair is False
        return 2

    def build_inputs_with_special_tokens(self, ids: list[int]) -> list[int]:
        return [self.cls_token_id, *ids, self.sep_token_id]

    def create_token_type_ids_from_sequences(self, ids: list[int]) -> list[int]:
        return [0] * (len(ids) + 2)


@pytest.fixture
def tokenizer() -> FakeTokenizer:
    return FakeTokenizer()


def test_every_window_has_its_own_special_tokens_and_padding(
    tokenizer: FakeTokenizer,
) -> None:
    text = " ".join(f"w{i}" for i in range(15))
    windows = sliding_window_tokenize(text, tokenizer, max_length=8, stride=2)
    assert len(windows) == 4
    for window in windows:
        assert len(window["input_ids"]) == 8
        assert len(window["attention_mask"]) == 8
        assert len(window["token_type_ids"]) == 8
        assert window["input_ids"][0] == tokenizer.cls_token_id
        last_real = sum(window["attention_mask"]) - 1
        assert window["input_ids"][last_real] == tokenizer.sep_token_id


def test_overlap_counts_content_not_special_tokens(tokenizer: FakeTokenizer) -> None:
    text = " ".join(f"w{i}" for i in range(12))
    first, second, *_ = sliding_window_tokenize(
        text, tokenizer, max_length=8, stride=2
    )
    first_content = first["input_ids"][1:-1]
    second_content = second["input_ids"][1:-1]
    assert first_content[-2:] == second_content[:2]
    assert first["input_ids"][0] == second["input_ids"][0] == 101


def test_final_content_is_not_truncated(tokenizer: FakeTokenizer) -> None:
    text = " ".join(f"w{i}" for i in range(25))
    windows = sliding_window_tokenize(text, tokenizer, max_length=8, stride=2)
    assert 1_024 in {token for window in windows for token in window["input_ids"]}


def test_empty_text_returns_one_special_only_window(tokenizer: FakeTokenizer) -> None:
    window = sliding_window_tokenize("", tokenizer, max_length=6, stride=1)[0]
    assert window["input_ids"] == [101, 102, 0, 0, 0, 0]
    assert window["attention_mask"] == [1, 1, 0, 0, 0, 0]


@pytest.mark.parametrize("stride", (-1, 6, 7))
def test_invalid_stride_is_rejected(tokenizer: FakeTokenizer, stride: int) -> None:
    with pytest.raises(ValueError, match="stride"):
        sliding_window_tokenize("w1", tokenizer, max_length=8, stride=stride)


def test_aggregate_logits_is_elementwise_max() -> None:
    output = aggregate_logits(
        [torch.tensor([0.1, 0.9]), torch.tensor([0.8, 0.2])]
    )
    assert torch.allclose(output, torch.tensor([0.8, 0.9]))


def test_aggregate_rejects_mismatched_shapes() -> None:
    with pytest.raises(ValueError, match="same shape"):
        aggregate_logits([torch.zeros(2), torch.zeros(3)])
