"""
automated-code-review-tool — Model inference (Issue #9).

Wraps the fine-tuned CodeBERT model behind a `predict(text, language)`
method that returns a list of `Finding` objects. Handles device
selection, sliding-window tokenization, and max-pool aggregation.

`LABEL_CONFIG` is the single source of truth mapping model output
index → (antiPattern, category, severity, explanation_template). The
order here MUST match the order of the canonical taxonomy at
``taxonomy/anti_patterns.yaml`` (built at runtime by ``build_label_config``).
"""
from __future__ import annotations

import logging
from typing import TYPE_CHECKING, Any

import torch

from app.config import settings
from app.schemas import Finding
from app.tokenizer_utils import aggregate_logits, sliding_window_tokenize
from app.taxonomy import load_taxonomy

if TYPE_CHECKING:
    pass

logger = logging.getLogger(__name__)


# ----------------------------------------------------------------------
# Quality score contract (Phase 0)
# ----------------------------------------------------------------------
# Severity penalty weights. MUST match the contract tested in
# ``tests/test_quality_score.py`` AND the Java implementation in
# ``apps/api``. See README § "Quality scoring contract".
SEVERITY_PENALTY: dict[str, float] = {
    "critical": 20.0,
    "major": 10.0,
    "minor": 3.0,
}


def compute_quality_score(findings: list[Finding]) -> float:
    """Compute the 0–100 quality score for a list of findings.

    Contract (see README):
      * critical: 20-point penalty
      * major: 10-point penalty
      * minor: 3-point penalty
      * each penalty is multiplied by the finding's confidence
      * the result is `100 - sum(penalty * confidence)`,
        clamped to [0, 100] and rounded to two decimals.

    This MUST produce identical numbers to the Java implementation in
    ``apps/api`` for the same inputs.
    """
    raw = 100.0
    for f in findings:
        raw -= SEVERITY_PENALTY.get(f.severity, 0.0) * float(f.confidence)
    clamped = max(0.0, min(100.0, raw))
    return round(clamped, 2)


# ----------------------------------------------------------------------
# Label configuration (index → finding metadata)
# ----------------------------------------------------------------------
def build_label_config() -> list[dict[str, str]]:
    """Build LABEL_CONFIG from the canonical taxonomy YAML for trainable IDs only.

    Ensures that model output index ``i`` always maps to the same
    anti-pattern ID the rest of the system uses.
    """
    taxonomy = load_taxonomy()
    trainable_entries = [ap for ap in taxonomy.entries if ap.trainable]
    return [
        {
            "name": ap.id,
            "category": ap.category,
            "severity": ap.default_severity,
            "explanation_template": (
                ap.description.strip().splitlines()[0]
                if ap.description
                else f"{ap.display_name} detected."
            ),
        }
        for ap in trainable_entries
    ]


LABEL_CONFIG: list[dict[str, str]] = build_label_config()


# ----------------------------------------------------------------------
# Checkpoint compatibility validation
# ----------------------------------------------------------------------
def validate_checkpoint_compatibility(model: Any, taxonomy: Any | None = None) -> dict[str, Any]:
    """Validate that a loaded HF model is compatible with the taxonomy."""
    if taxonomy is None:
        taxonomy = load_taxonomy()

    trainable_entries = [e for e in taxonomy.entries if e.trainable]
    expected_num_labels = len(trainable_entries)
    expected_id2label: dict[int, str] = {
        i: ap.id for i, ap in enumerate(trainable_entries)
    }
    expected_label2id: dict[str, int] = {
        ap.id: i for i, ap in enumerate(trainable_entries)
    }
    expected_taxonomy_version = taxonomy.version

    cfg = getattr(model, "config", None)
    if cfg is None:
        return {
            "status": "degraded",
            "reason": "model has no config attribute",
            "expected_num_labels": expected_num_labels,
        }

    actual_num_labels = getattr(cfg, "num_labels", None)
    if actual_num_labels != expected_num_labels:
        return {
            "status": "degraded",
            "reason": (
                f"num_labels={actual_num_labels} != "
                f"len(trainable)={expected_num_labels}"
            ),
            "expected_num_labels": expected_num_labels,
            "actual_num_labels": actual_num_labels,
        }

    actual_id2label = getattr(cfg, "id2label", None)
    actual_label2id = getattr(cfg, "label2id", None)
    actual_problem_type = getattr(cfg, "problem_type", None)
    actual_task_type = getattr(cfg, "task_type", None)
    actual_version = getattr(cfg, "taxonomy_version", None)

    if not isinstance(actual_id2label, dict) or not actual_id2label:
        return {"status": "degraded", "reason": "id2label missing or empty"}
    if not isinstance(actual_label2id, dict) or not actual_label2id:
        return {"status": "degraded", "reason": "label2id missing or empty"}
    if not actual_problem_type or actual_problem_type != "multi_label_classification":
        return {"status": "degraded", "reason": "problem_type missing or not multi_label_classification"}
    if not actual_version or actual_version != expected_taxonomy_version:
        return {"status": "degraded", "reason": "taxonomy_version missing or mismatch"}
    if not actual_task_type or actual_task_type != "code_review_multi_label":
        return {"status": "degraded", "reason": "task_type missing or mismatch"}

    try:
        actual_id2label_norm = {int(k): v for k, v in actual_id2label.items()}
    except (TypeError, ValueError):
        return {"status": "degraded", "reason": "id2label keys must be ints"}

    for idx, expected_label in expected_id2label.items():
        if actual_id2label_norm.get(idx) != expected_label:
            return {
                "status": "degraded",
                "reason": f"id2label[{idx}]={actual_id2label_norm.get(idx)!r} != {expected_label!r}",
            }

    for label_id, expected_idx in expected_label2id.items():
        if actual_label2id.get(label_id) != expected_idx:
            return {
                "status": "degraded",
                "reason": f"label2id[{label_id!r}]={actual_label2id.get(label_id)!r} != {expected_idx}",
            }

    return {
        "status": "healthy",
        "expected_num_labels": expected_num_labels,
        "taxonomy_version": expected_taxonomy_version,
    }


# ----------------------------------------------------------------------
# Model wrapper
# ----------------------------------------------------------------------
class AutomatedCodeReviewToolModel:
    """Lazy-loaded fine-tuned CodeBERT wrapped in a clean predict() API."""

    def __init__(
        self,
        model_name: str | None = None,
        threshold: float | None = None,
        max_seq_length: int | None = None,
        hf_token: str | None = None,
    ) -> None:
        self.model_name = model_name or settings.MODEL_NAME
        self.threshold = threshold if threshold is not None else settings.THRESHOLD
        self.max_seq_length = max_seq_length if max_seq_length is not None else settings.MAX_SEQ_LENGTH
        self.last_windows_processed = 0
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

        # Validate checkpoint compatibility against the canonical taxonomy.
        # If incompatible, refuse to serve predictions: leave self.model
        # attribute set (so healthcheck can report it) but mark the
        # service degraded and route inference to the fallback scanner.
        self.compatibility: dict[str, Any] = validate_checkpoint_compatibility(self.model)
        if self.compatibility["status"] != "healthy":
            logger.warning(
                "Checkpoint %s is incompatible: %s — service will run in fallback mode.",
                self.model_name,
                self.compatibility.get("reason"),
            )

    @property
    def is_healthy(self) -> bool:
        return bool(getattr(self, "compatibility", {}).get("status") == "healthy")

    # ------------------------------------------------------------------
    # Inference
    # ------------------------------------------------------------------
    def predict(self, text: str, language: str) -> list[Finding]:
        """Run inference on `text`, return findings above the threshold.

        Returns an empty list if the checkpoint was incompatible at
        construction time. Callers should use the fallback scanner
        when ``is_healthy`` is False.
        """
        if not self.is_healthy:
            return []

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
                    filePath=None,
                    hunkHash=None,
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

