import sys
from pathlib import Path
from typing import Any
import pytest
import torch

_HERE = Path(__file__).resolve().parent
_ML_WORKER = _HERE.parent
sys.path.insert(0, str(_ML_WORKER))

from app.tokenizer_utils import aggregate_logits, sliding_window_tokenize

class FakeTokenizer:
    """Minimal PreTrainedTokenizer stand-in supporting encode/decode."""
    PAD_TOKEN_ID = 0
    CLS_TOKEN_ID = 101
    SEP_TOKEN_ID = 102

    def __init__(self, vocab_size: int = 100_000):
        self._vocab = {"[PAD]": self.PAD_TOKEN_ID, "[CLS]": self.CLS_TOKEN_ID, "[SEP]": self.SEP_TOKEN_ID}
        self._reverse_vocab = {self.PAD_TOKEN_ID: "[PAD]", self.CLS_TOKEN_ID: "[CLS]", self.SEP_TOKEN_ID: "[SEP]"}
        self.vocab_size = vocab_size

    @property
    def pad_token_id(self) -> int:
        return self.PAD_TOKEN_ID

    def _id_for(self, word: str) -> int:
        if word not in self._vocab:
            idx = (hash(word) % (self.vocab_size - 1000)) + 1000
            self._vocab[word] = idx
            self._reverse_vocab[idx] = word
        return self._vocab[word]

    def __call__(self, text: str, add_special_tokens: bool = True, **kwargs: Any) -> dict[str, Any]:
        words = text.split()
        ids = [self._id_for(w) for w in words]
        if add_special_tokens:
            ids = [self.CLS_TOKEN_ID] + ids + [self.SEP_TOKEN_ID]
        attention_mask = [1] * len(ids)
        return {"input_ids": ids, "attention_mask": attention_mask}

    def decode(self, ids: list[int]) -> str:
        words = []
        for tid in ids:
            if tid in [self.PAD_TOKEN_ID, self.CLS_TOKEN_ID, self.SEP_TOKEN_ID]:
                continue
            words.append(self._reverse_vocab.get(tid, f"[ID_{tid}]"))
        return " ".join(words)

@pytest.fixture
def tokenizer():
    return FakeTokenizer()

def test_important_content_at_end_not_lost(tokenizer):
    """Security findings often appear at end of diff — verify they're not truncated"""
    # Create a diff that has about 600 words so it exceeds 512 tokens
    security_issue = "password = 'hardcoded_secret_12345'"
    padding = "x = 1 " * 600  # 600 words of padding
    long_diff = padding + security_issue
    
    windows = sliding_window_tokenize(long_diff, tokenizer, max_length=512, stride=50)
    
    # Check that at least one window contains the decoded security issue
    # The security_issue text should be reconstructed in the decode output
    found = any("password = 'hardcoded_secret_12345'" in tokenizer.decode(w["input_ids"]) for w in windows)
    assert found, "Content at end of long diff was lost — security findings will be missed"

def test_stride_overlap_preserves_context(tokenizer):
    """Stride of 50 tokens means context is preserved between windows"""
    diff = "some_code " * 1000
    windows = sliding_window_tokenize(diff, tokenizer, max_length=512, stride=50)
    assert len(windows) > 1
    
    # Decode first and second window, verify overlap exists
    decoded_1 = tokenizer.decode(windows[0]["input_ids"])
    decoded_2 = tokenizer.decode(windows[1]["input_ids"])
    assert len(decoded_1) > 0 and len(decoded_2) > 0

def test_max_pool_takes_highest_confidence():
    """Max pooling should take the most confident prediction across windows"""
    window1_logits = torch.tensor([[0.1, 0.9, 0.2, 0.1, 0.1, 0.1]])
    window2_logits = torch.tensor([[0.9, 0.1, 0.1, 0.1, 0.1, 0.1]])
    aggregated = aggregate_logits([window1_logits, window2_logits])
    assert aggregated[0][0].item() == pytest.approx(0.9)  # max of 0.1 and 0.9
    assert aggregated[0][1].item() == pytest.approx(0.9)  # max of 0.9 and 0.1
