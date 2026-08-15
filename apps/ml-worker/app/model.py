"""Versioned multi-label model loading and hunk-level inference."""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass
from typing import Any

import torch

from app.config import settings
from app.preprocessing import build_model_text
from app.schemas import Finding
from app.taxonomy import Taxonomy, load_taxonomy
from app.tokenizer_utils import windowed_model_logits

logger = logging.getLogger(__name__)

SEVERITY_PENALTY: dict[str, float] = {
    "critical": 20.0,
    "major": 10.0,
    "minor": 3.0,
}
_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


def compute_quality_score(findings: list[Finding]) -> float:
    """Apply the shared confidence-weighted severity contract."""
    raw = 100.0
    for finding in findings:
        raw -= SEVERITY_PENALTY.get(finding.severity, 0.0) * float(
            finding.confidence
        )
    return round(max(0.0, min(100.0, raw)), 2)


@dataclass(frozen=True)
class LabelMetadata:
    anti_pattern_id: str
    category: str
    severity: str
    explanation: str


@dataclass(frozen=True)
class ModelPrediction:
    findings: tuple[Finding, ...]
    windows_processed: int


def build_label_config(taxonomy: Taxonomy | None = None) -> tuple[LabelMetadata, ...]:
    """Build ordered model-label metadata from the canonical taxonomy."""
    loaded = taxonomy or load_taxonomy()
    return tuple(
        LabelMetadata(
            anti_pattern_id=entry.id,
            category=entry.category,
            severity=entry.default_severity,
            explanation=(
                entry.description.strip().splitlines()[0]
                if entry.description
                else f"{entry.display_name} detected."
            ),
        )
        for entry in loaded.entries
        if entry.trainable
    )


# Retained as a read-only compatibility export. Its values are derived from YAML.
LABEL_CONFIG = build_label_config()


def _degraded(reason: str, **details: Any) -> dict[str, Any]:
    return {"status": "degraded", "reason": reason, **details}


def _valid_sha256(value: Any) -> bool:
    return isinstance(value, str) and bool(_SHA256_RE.fullmatch(value.lower()))


def _promotion_error(config: Any, labels: list[str], manifest_sha: str) -> str | None:
    """Return the first failed production-promotion invariant, if any."""
    scalar_expectations = {
        "promotion_status": "approved_not_deployed",
        "promotion_gate_version": 1,
        "promotion_dataset_manifest_sha256": manifest_sha,
        "promotion_evaluated_split": "test",
        "promotion_quality_critical_failures": 0,
        "promotion_deployment_smoke_passed": True,
        "promotion_auto_deploy": False,
        "thresholds_source": "frozen_validation",
        "threshold_tuning_dataset_manifest_sha256": manifest_sha,
        "threshold_tuning_split": "validation",
    }
    for field, expected in scalar_expectations.items():
        if getattr(config, field, None) != expected:
            return f"{field} must equal {expected!r}"

    for field in (
        "promotion_evaluation_sha256",
        "promotion_deployment_smoke_sha256",
        "promotion_metadata_sha256",
    ):
        if not _valid_sha256(getattr(config, field, None)):
            return f"{field} must be a 64-character SHA-256"

    baselines = getattr(config, "promotion_baselines", None)
    if not isinstance(baselines, list) or set(baselines) != {
        "rule",
        "tfidf_logistic",
    }:
        return "promotion_baselines must contain rule and tfidf_logistic"

    test_support = getattr(config, "promotion_per_label_test_support", None)
    if not isinstance(test_support, dict) or set(test_support) != set(labels):
        return "promotion_per_label_test_support must contain every trainable label"
    for label in labels:
        row = test_support[label]
        if not isinstance(row, dict):
            return f"invalid test support for {label}"
        known_value = row.get("known")
        positive_value = row.get("positive")
        negative_value = row.get("negative")
        if (
            isinstance(known_value, bool)
            or not isinstance(known_value, int)
            or known_value <= 0
            or isinstance(positive_value, bool)
            or not isinstance(positive_value, int)
            or positive_value <= 0
            or isinstance(negative_value, bool)
            or not isinstance(negative_value, int)
            or negative_value <= 0
        ):
            return f"test support for {label} must include known positives and negatives"
        known = known_value
        positive = positive_value
        negative = negative_value
        if known != positive + negative:
            return f"test support totals are inconsistent for {label}"

    tuning_support = getattr(config, "threshold_tuning_support", None)
    if not isinstance(tuning_support, dict) or set(tuning_support) != set(labels):
        return "threshold_tuning_support must contain every trainable label"
    for label in labels:
        row = tuning_support[label]
        if not isinstance(row, dict) or row.get("sufficient_for_tuning") is not True:
            return f"threshold tuning support is insufficient for {label}"
        tuning_positive = row.get("positive")
        tuning_negative = row.get("negative")
        if (
            isinstance(tuning_positive, bool)
            or isinstance(tuning_negative, bool)
            or not isinstance(tuning_positive, int)
            or not isinstance(tuning_negative, int)
            or tuning_positive <= 0
            or tuning_negative <= 0
        ):
            return f"threshold tuning support is invalid for {label}"
    return None


def validate_checkpoint_compatibility(
    model: Any,
    taxonomy: Taxonomy | None = None,
    *,
    require_promotion: bool = False,
) -> dict[str, Any]:
    """Validate every label, threshold, and provenance field needed to serve.

    A generic base model is intentionally incompatible. A promoted checkpoint
    must carry the frozen dataset identity and validation-tuned thresholds in
    its Hugging Face config.
    """
    loaded = taxonomy or load_taxonomy()
    labels = loaded.trainable_ids()
    expected_id2label = {index: label for index, label in enumerate(labels)}
    expected_label2id = {label: index for index, label in enumerate(labels)}

    config = getattr(model, "config", None)
    if config is None:
        return _degraded("model has no config attribute")

    if getattr(config, "num_labels", None) != len(labels):
        return _degraded(
            "num_labels does not match the canonical trainable label count",
            expected_num_labels=len(labels),
            actual_num_labels=getattr(config, "num_labels", None),
        )
    if getattr(config, "problem_type", None) != "multi_label_classification":
        return _degraded("problem_type must be multi_label_classification")
    if getattr(config, "task_type", None) != "code_review_multi_label":
        return _degraded("task_type must be code_review_multi_label")
    if getattr(config, "taxonomy_version", None) != loaded.version:
        return _degraded("checkpoint taxonomy_version is missing or incompatible")

    raw_id2label = getattr(config, "id2label", None)
    raw_label2id = getattr(config, "label2id", None)
    if not isinstance(raw_id2label, dict) or not isinstance(raw_label2id, dict):
        return _degraded("id2label and label2id must be mappings")
    try:
        id2label = {int(key): str(value) for key, value in raw_id2label.items()}
        label2id = {str(key): int(value) for key, value in raw_label2id.items()}
    except (TypeError, ValueError):
        return _degraded("checkpoint label mappings contain invalid keys or values")
    if id2label != expected_id2label or label2id != expected_label2id:
        return _degraded("checkpoint label order does not match the taxonomy")

    manifest_sha = getattr(config, "dataset_manifest_sha256", None)
    if not _valid_sha256(manifest_sha):
        return _degraded("dataset_manifest_sha256 must be a 64-character SHA-256")

    thresholds = getattr(config, "thresholds", None)
    if not isinstance(thresholds, dict) or set(thresholds) != set(labels):
        return _degraded("thresholds must contain exactly every trainable label")
    for label, threshold in thresholds.items():
        if (
            isinstance(threshold, bool)
            or not isinstance(threshold, (int, float))
            or not 0.0 <= float(threshold) <= 1.0
        ):
            return _degraded(f"invalid threshold for {label}")

    for field in ("base_model_name", "training_git_sha"):
        value = getattr(config, field, None)
        if not isinstance(value, str) or not value.strip():
            return _degraded(f"{field} is missing")

    manifest_sha_normalized = str(manifest_sha).lower()
    if require_promotion:
        promotion_error = _promotion_error(
            config, labels, manifest_sha_normalized
        )
        if promotion_error:
            return _degraded(promotion_error)

    result = {
        "status": "healthy",
        "taxonomy_version": loaded.version,
        "dataset_manifest_sha256": manifest_sha_normalized,
        "label_order": labels,
        "thresholds": {label: float(thresholds[label]) for label in labels},
    }
    if require_promotion:
        result["promotion_status"] = config.promotion_status
    return result


class AutomatedCodeReviewToolModel:
    """Load an explicitly promoted checkpoint and classify one hunk at a time."""

    def __init__(
        self,
        model_name: str | None = None,
        threshold: float | None = None,
        max_seq_length: int | None = None,
        stride: int | None = None,
        hf_token: str | None = None,
    ) -> None:
        self.model_name = model_name or settings.MODEL_NAME
        self.threshold_override = (
            threshold
            if threshold is not None
            else settings.MODEL_THRESHOLD_OVERRIDE
        )
        if self.threshold_override is not None and not 0.0 <= self.threshold_override <= 1.0:
            raise ValueError("MODEL_THRESHOLD_OVERRIDE must be in [0, 1]")
        self.max_seq_length = max_seq_length or settings.MAX_SEQ_LENGTH
        self.stride = stride if stride is not None else settings.MODEL_STRIDE
        self.last_windows_processed = 0
        self.taxonomy = load_taxonomy()
        self.label_config = build_label_config(self.taxonomy)

        token = hf_token if hf_token is not None else settings.HF_TOKEN
        token_kwargs: dict[str, Any] = {"token": token} if token else {}
        self.device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

        from transformers import (  # noqa: PLC0415
            AutoModelForSequenceClassification,
            AutoTokenizer,
        )

        self.tokenizer: Any = AutoTokenizer.from_pretrained(
            self.model_name, **token_kwargs
        )
        self.model: Any = AutoModelForSequenceClassification.from_pretrained(
            self.model_name, **token_kwargs
        ).to(self.device)
        self.model.eval()
        self.compatibility = validate_checkpoint_compatibility(
            self.model, self.taxonomy, require_promotion=True
        )
        if not self.is_healthy:
            logger.warning(
                "Checkpoint %s is not eligible for serving: %s",
                self.model_name,
                self.compatibility.get("reason"),
            )

    @property
    def is_healthy(self) -> bool:
        return self.compatibility.get("status") == "healthy"

    @property
    def taxonomy_version(self) -> str:
        if self.is_healthy:
            return str(self.compatibility["taxonomy_version"])
        return self.taxonomy.version

    @property
    def model_version(self) -> str:
        config_version = getattr(self.model.config, "model_version", None)
        return str(config_version or self.model_name)

    def predict_hunk(
        self,
        text: str,
        language: str,
        *,
        mode: str = "diff",
        file_path: str | None = None,
        hunk_hash: str | None = None,
        line_start: int | None = None,
        line_end: int | None = None,
    ) -> ModelPrediction:
        """Classify one hunk and return request-local inference metadata."""
        if not self.is_healthy:
            raise RuntimeError("checkpoint is not compatible with the runtime contract")

        model_text = build_model_text(text, language, mode)
        aggregated_logits, window_count = windowed_model_logits(
            self.model,
            self.tokenizer,
            model_text,
            device=self.device,
            max_length=self.max_seq_length,
            stride=self.stride,
        )
        self.last_windows_processed = window_count
        probabilities = torch.sigmoid(aggregated_logits)
        configured_thresholds: dict[str, float] = self.compatibility["thresholds"]

        findings: list[Finding] = []
        for index, probability in enumerate(probabilities):
            if index >= len(self.label_config):
                break
            metadata = self.label_config[index]
            score = float(probability)
            selected_threshold = (
                self.threshold_override
                if self.threshold_override is not None
                else configured_thresholds[metadata.anti_pattern_id]
            )
            if score < selected_threshold:
                continue
            findings.append(
                Finding(
                    filePath=file_path,
                    hunkHash=hunk_hash,
                    lineStart=line_start,
                    lineEnd=line_end,
                    antiPattern=metadata.anti_pattern_id,
                    category=metadata.category,
                    severity=metadata.severity,
                    confidence=round(score, 4),
                    explanation=metadata.explanation,
                )
            )
        return ModelPrediction(tuple(findings), window_count)

    def predict(
        self,
        text: str,
        language: str,
        *,
        mode: str = "diff",
        file_path: str | None = None,
        hunk_hash: str | None = None,
        line_start: int | None = None,
        line_end: int | None = None,
    ) -> list[Finding]:
        """Compatibility API returning only findings for one hunk."""
        prediction = self.predict_hunk(
            text,
            language,
            mode=mode,
            file_path=file_path,
            hunk_hash=hunk_hash,
            line_start=line_start,
            line_end=line_end,
        )
        return list(prediction.findings)
