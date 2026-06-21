"""
CodeLens — Model inference (Issue #9).

Wraps the fine-tuned CodeBERT model behind a `predict(text, language)`
method that returns a list of `Finding` objects. Handles device
selection, sliding-window tokenization, and max-pool aggregation.

`LABEL_CONFIG` is the single source of truth mapping model output
index → (antiPattern, category, severity, explanation_template). The
order here MUST match `label_mapper.LABEL_NAMES` from training.
"""
from __future__ import annotations

from typing import TYPE_CHECKING, Any

import torch

from app.config import settings
from app.schemas import Finding
from app.tokenizer_utils import aggregate_logits, sliding_window_tokenize

if TYPE_CHECKING:
    from transformers import AutoModelForSequenceClassification, AutoTokenizer


# ----------------------------------------------------------------------
# Label configuration (index → finding metadata)
# ----------------------------------------------------------------------
# Index order MUST match the training label order in label_mapper.py.
LABEL_CONFIG: list[dict[str, str]] = [
    {
        "name": "SECURITY_HARDCODED_SECRET",
        "category": "SECURITY",
        "severity": "critical",
        "explanation_template": "Hardcoded secret detected — use environment variables or a secrets manager.",
    },
    {
        "name": "PERFORMANCE_N_PLUS_1",
        "category": "PERFORMANCE",
        "severity": "major",
        "explanation_template": "N+1 query pattern detected — batch the queries or use eager loading.",
    },
    {
        "name": "ARCH_GOD_CLASS",
        "category": "ARCHITECTURE",
        "severity": "major",
        "explanation_template": "Class has too many responsibilities — split it along its cohesion boundaries.",
    },
    {
        "name": "RELY_BARE_EXCEPT",
        "category": "RELIABILITY",
        "severity": "major",
        "explanation_template": "Bare `except` swallows all errors, including KeyboardInterrupt — catch a specific exception.",
    },
    {
        "name": "READ_MAGIC_NUMBER",
        "category": "READABILITY",
        "severity": "minor",
        "explanation_template": "Magic number with no name — extract it into a named constant.",
    },
    {
        "name": "MAINT_DEEP_NESTING",
        "category": "MAINTAINABILITY",
        "severity": "minor",
        "explanation_template": "Deeply nested code is hard to follow — extract helper functions or use early returns.",
    },
]


# ----------------------------------------------------------------------
# Model wrapper
# ----------------------------------------------------------------------
class CodeLensModel:
    """Lazy-loaded fine-tuned CodeBERT wrapped in a clean predict() API."""

    def __init__(
        self,
        model_name: str | None = None,
        threshold: float | None = None,
        max_seq_length: int | None = None,
        hf_token: str | None = None,
    ) -> None:
        # Imports are deferred to keep unit tests light.
        from transformers import AutoModelForSequenceClassification, AutoTokenizer  # noqa: F401

        self.model_name = model_name or settings.MODEL_NAME
        self.threshold = threshold if threshold is not None else settings.THRESHOLD
        self.max_seq_length = max_seq_length or settings.MAX_SEQ_LENGTH
        token = hf_token if hf_token is not None else settings.HF_TOKEN
        token_kw: dict[str, Any] = {"token": token} if token else {}

        # Device selection: CUDA when available, else CPU.
        self.device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

        from transformers import AutoModelForSequenceClassification, AutoTokenizer  # noqa: E402

        self.tokenizer: Any = AutoTokenizer.from_pretrained(self.model_name, **token_kw)
        self.model: Any = AutoModelForSequenceClassification.from_pretrained(
            self.model_name, **token_kw
        ).to(self.device)
        self.model.eval()

    # ------------------------------------------------------------------
    # Inference
    # ------------------------------------------------------------------
    def predict(self, text: str, language: str) -> list[Finding]:
        """Run inference on `text`, return findings above the threshold."""
        windows = sliding_window_tokenize(
            text,
            self.tokenizer,
            max_length=self.max_seq_length,
            stride=50,
        )
        per_window_logits: list[torch.Tensor] = []
        with torch.no_grad():
            for w in windows:
                input_ids = torch.tensor([w["input_ids"]], device=self.device)
                attention_mask = torch.tensor([w["attention_mask"]], device=self.device)
                outputs = self.model(input_ids=input_ids, attention_mask=attention_mask)
                # Shape: [1, num_labels] → squeeze to [num_labels].
                per_window_logits.append(outputs.logits.squeeze(0).cpu())

        # If every window produced 0 tokens (shouldn't happen because the
        # tokenizer raises first), bail out cleanly.
        if not per_window_logits:
            return []

        aggregated = aggregate_logits(per_window_logits)
        probs = torch.sigmoid(aggregated)
        # Cache on the instance so callers (e.g. main.py) can read
        # `model.last_windows_processed` for the response envelope.
        self.last_windows_processed = len(windows)

        findings: list[Finding] = []
        for idx, prob in enumerate(probs):
            if idx >= len(LABEL_CONFIG):
                break
            score = float(prob)
            if score < self.threshold:
                continue
            cfg = LABEL_CONFIG[idx]
            findings.append(
                Finding(
                    lineStart=None,
                    lineEnd=None,
                    antiPattern=cfg["name"],
                    category=cfg["category"],
                    severity=cfg["severity"],  # type: ignore[arg-type]
                    confidence=round(score, 4),
                    explanation=cfg["explanation_template"],
                )
            )
        return findings


# ----------------------------------------------------------------------
# Quality score
# ----------------------------------------------------------------------
_SEVERITY_PENALTY: dict[str, float] = {
    "critical": 20.0,
    "major": 10.0,
    "minor": 3.0,
}


def compute_quality_score(findings: list[Finding]) -> float:
    """100 minus a weighted sum of severity penalties, floored at 0."""
    score = 100.0
    for f in findings:
        score -= _SEVERITY_PENALTY.get(f.severity, 0.0)
    return max(0.0, score)
