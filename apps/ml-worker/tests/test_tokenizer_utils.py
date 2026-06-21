"""
CodeLens — tokenizer_utils unit tests (Issue #8).

Uses a tiny deterministic fake tokenizer instead of loading CodeBERT,
so these tests stay fast and don't require network or GPU.

Run from the repo root:
    cd apps/ml-worker && pytest tests/test_tokenizer_utils.py -v
"""
from __future__ import annotations

import sys
from pathlib import Path
from typing import Any

import pytest
import torch

_HERE = Path(__file__).resolve().parent
_ML_WORKER = _HERE.parent
sys.path.insert(0, str(_ML_WORKER))

from app.tokenizer_utils import aggregate_logits, sliding_window_tokenize  # noqa: E402


# ----------------------------------------------------------------------
# Fake tokenizer
# ----------------------------------------------------------------------
class FakeTokenizer:
    """
    Minimal stand-in for transformers.PreTrainedTokenizer.

    - Splits on whitespace
    - Each word becomes a unique integer ID (deterministic via dict)
    - vocab_size is exposed (HF-style)
    - pad_token_id == 0
    - add_special_tokens wraps input in [101] ... [102] (BERT-style)
    """

    PAD_TOKEN_ID = 0
    CLS_TOKEN_ID = 101
    SEP_TOKEN_ID = 102

    def __init__(self, vocab_size: int = 100_000):
        self._vocab: dict[str, int] = {}
        self.vocab_size = vocab_size
        # Reserve special IDs so they're not assigned to words.
        self._vocab["[PAD]"] = self.PAD_TOKEN_ID
        self._vocab["[CLS]"] = self.CLS_TOKEN_ID
        self._vocab["[SEP]"] = self.SEP_TOKEN_ID

    @property
    def pad_token_id(self) -> int:
        return self.PAD_TOKEN_ID

    def _id_for(self, word: str) -> int:
        if word not in self._vocab:
            # Hash into vocab range, skipping reserved IDs.
            idx = (hash(word) % (self.vocab_size - 1000)) + 1000
            self._vocab[word] = idx
        return self._vocab[word]

    def __call__(
        self,
        text: str,
        add_special_tokens: bool = True,
        truncation: bool = False,
        return_attention_mask: bool = True,
        return_tensors: Any = None,
        **kwargs: Any,
    ) -> dict[str, Any]:
        assert truncation is False, "fake tokenizer does not support truncation"
        words = text.split()
        ids = [self._id_for(w) for w in words]
        if add_special_tokens:
            ids = [self.CLS_TOKEN_ID] + ids + [self.SEP_TOKEN_ID]
        attention_mask = [1] * len(ids)
        out: dict[str, Any] = {"input_ids": ids, "attention_mask": attention_mask}
        return out


@pytest.fixture
def tok() -> FakeTokenizer:
    return FakeTokenizer()


# ----------------------------------------------------------------------
# Tests
# ----------------------------------------------------------------------
def test_short_text_returns_one_window(tok):
    text = "def foo(): return 42"
    windows = sliding_window_tokenize(text, tok, max_length=512, stride=50)
    assert len(windows) == 1
    assert isinstance(windows[0], dict)
    assert "input_ids" in windows[0] and "attention_mask" in windows[0]
    # CLS + 5 words + SEP = 7 tokens (no padding because ≤ max_length).
    assert len(windows[0]["input_ids"]) == 7
    assert len(windows[0]["attention_mask"]) == 7
    # First and last token are CLS / SEP.
    assert windows[0]["input_ids"][0] == tok.CLS_TOKEN_ID
    assert windows[0]["input_ids"][-1] == tok.SEP_TOKEN_ID


def test_long_text_returns_multiple_windows(tok):
    # 1500 tokens (incl. CLS + SEP) — exceeds max_length=512.
    text = " ".join(f"word{i}" for i in range(1500))
    windows = sliding_window_tokenize(text, tok, max_length=512, stride=50)
    # step = 512 - 50 = 462; covers 1500 tokens in ceil((1500-512)/462)+1 = 3 windows.
    assert len(windows) >= 3
    # Every window has exactly max_length tokens (padding to right).
    for w in windows:
        assert len(w["input_ids"]) == 512
        assert len(w["attention_mask"]) == 512


def test_windows_have_overlap(tok):
    """Consecutive windows must share a suffix/prefix of length == stride."""
    text = " ".join(f"w{i}" for i in range(1500))
    max_length = 512
    stride = 50
    windows = sliding_window_tokenize(text, tok, max_length=max_length, stride=stride)

    for i in range(len(windows) - 1):
        a = windows[i]["input_ids"]
        b = windows[i + 1]["input_ids"]
        # The end of window i and the start of window i+1 must overlap.
        # With stride=50, window i+1 starts 462 tokens after window i,
        # so the last 50 tokens of i should equal the first 50 tokens
        # of i+1 — BUT only if neither was padded at the boundary.
        # Since we pad only the final window, interior windows agree exactly.
        tail = a[max_length - stride :]
        head = b[:stride]
        assert tail == head, (
            f"Windows {i} and {i+1} do not overlap as expected: "
            f"tail={tail[:5]}..., head={head[:5]}..."
        )


def test_aggregate_max_pools_correctly():
    """aggregate_logits takes the elementwise max across stacked windows."""
    a = torch.tensor([0.1, 0.9, 0.3])
    b = torch.tensor([0.7, 0.2, 0.5])
    c = torch.tensor([0.4, 0.8, 0.6])
    out = aggregate_logits([a, b, c])
    expected = torch.tensor([0.7, 0.9, 0.6])
    assert torch.allclose(out, expected)
    # Shape is preserved.
    assert out.shape == a.shape


def test_empty_text_raises_value_error(tok):
    with pytest.raises(ValueError, match="text must not be empty"):
        sliding_window_tokenize("", tok)
    with pytest.raises(ValueError, match="text must not be empty"):
        sliding_window_tokenize(None, tok)  # type: ignore[arg-type]


def test_aggregate_empty_raises():
    with pytest.raises(ValueError, match="window_logits must not be empty"):
        aggregate_logits([])


def test_stride_validation(tok):
    text = " ".join(f"w{i}" for i in range(1500))
    # stride >= max_length is rejected.
    with pytest.raises(ValueError, match="stride"):
        sliding_window_tokenize(text, tok, max_length=512, stride=512)
    with pytest.raises(ValueError, match="stride"):
        sliding_window_tokenize(text, tok, max_length=512, stride=600)
    # negative stride rejected.
    with pytest.raises(ValueError, match="stride"):
        sliding_window_tokenize(text, tok, max_length=512, stride=-1)


def test_max_length_validation(tok):
    text = "hello world"
    with pytest.raises(ValueError, match="max_length"):
        sliding_window_tokenize(text, tok, max_length=0, stride=10)
    with pytest.raises(ValueError, match="max_length"):
        sliding_window_tokenize(text, tok, max_length=-1, stride=10)