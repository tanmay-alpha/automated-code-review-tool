from __future__ import annotations

import numpy as np
import pytest

from training.annotate_dataset import (
    annotation_idempotency_key,
    normalize_annotation,
)
from training.label_resolution import (
    AnnotationEvidence,
    Resolution,
    ReviewState,
    clean_review_evidence,
    resolve_label,
)
from training.metrics import multilabel_metrics, tune_thresholds
from training.promote import _support_gate


def _evidence(identifier: str, label: str, trust: str) -> AnnotationEvidence:
    return AnnotationEvidence(identifier, trust, label, "reviewer", "human")


def test_same_trust_contradiction_requires_adjudication() -> None:
    result = resolve_label(
        [
            _evidence("one", "positive", "human_single"),
            _evidence("two", "negative", "human_single"),
        ]
    )
    assert result.resolution is Resolution.CONFLICT
    assert result.annotation_ids == ("one", "two")


def test_adjudication_overrides_lower_trust_without_erasing_provenance() -> None:
    result = resolve_label(
        [
            _evidence("adjudicated", "negative", "human_adjudicated"),
            _evidence("single", "positive", "human_single"),
        ]
    )
    assert result.resolution is Resolution.NEGATIVE
    assert result.winning_trust == "human_adjudicated"
    assert result.annotation_ids == ("adjudicated",)


def test_clean_review_is_an_explicit_negative_only_for_reviewed_label() -> None:
    review = ReviewState("review", "complete", True, ("LABEL_A",), "reviewer")
    assert clean_review_evidence([review], "LABEL_A") == ("review",)
    assert clean_review_evidence([review], "LABEL_B") == ()


def test_masked_metrics_ignore_unreviewed_labels() -> None:
    labels = ("A", "B")
    targets = np.asarray([[1, 0], [0, 1]], dtype=np.float32)
    scores = np.asarray([[0.9, 0.99], [0.1, 0.01]], dtype=np.float32)
    mask = np.asarray([[1, 0], [1, 0]], dtype=np.float32)
    metrics = multilabel_metrics(targets, scores, mask, labels, 0.5)
    assert metrics["per_label"]["A"]["f1"] == 1.0
    assert metrics["per_label"]["B"]["support_known"] == 0
    assert metrics["known_decisions"] == 2


def test_threshold_tuning_reports_insufficient_label_support() -> None:
    labels = ("A", "B")
    targets = np.asarray([[1, 1], [0, 1]], dtype=np.float32)
    scores = np.asarray([[0.8, 0.8], [0.2, 0.7]], dtype=np.float32)
    mask = np.ones_like(targets)
    thresholds, support = tune_thresholds(
        targets, scores, mask, labels, candidates=[0.3, 0.5, 0.7]
    )
    assert thresholds["A"] in {0.3, 0.5, 0.7}
    assert support["A"]["sufficient_for_tuning"] is True
    assert thresholds["B"] == 0.5
    assert support["B"]["sufficient_for_tuning"] is False


def test_annotation_normalization_is_taxonomy_bound_and_deduplicated() -> None:
    raw = {
        "sampleId": "sample",
        "antiPatternId": "LABEL_A",
        "label": "positive",
        "reviewerId": "reviewer",
        "lineStart": 4,
        "lineEnd": 5,
    }
    normalized = normalize_annotation(raw, {"LABEL_A"})
    assert annotation_idempotency_key(normalized) == annotation_idempotency_key(
        normalized
    )
    changed = dict(normalized, label_state="negative")
    assert annotation_idempotency_key(normalized) != annotation_idempotency_key(changed)
    with pytest.raises(ValueError, match="trainable taxonomy"):
        normalize_annotation(dict(raw, antiPatternId="LEGACY_SECURITY"), {"LABEL_A"})


def test_promotion_support_gate_requires_both_test_classes() -> None:
    supported = {
        "A": {
            "support_known": 2,
            "support_positive": 1,
            "support_negative": 1,
        }
    }
    assert _support_gate(supported, ("A",))["A"] == {
        "known": 2,
        "positive": 1,
        "negative": 1,
    }
    supported["A"]["support_negative"] = 0
    with pytest.raises(ValueError, match="positive and negative"):
        _support_gate(supported, ("A",))
