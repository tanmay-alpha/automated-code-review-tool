"""Checkpoint metadata and taxonomy compatibility tests."""

from __future__ import annotations

from types import SimpleNamespace
from typing import Any

import pytest

from app.model import compute_quality_score, validate_checkpoint_compatibility
from app.schemas import Finding
from app.taxonomy import Taxonomy, load_taxonomy


def _stub_model(taxonomy: Taxonomy, **overrides: Any) -> Any:
    labels = taxonomy.trainable_ids()
    config: dict[str, Any] = {
        "num_labels": len(labels),
        "id2label": {index: label for index, label in enumerate(labels)},
        "label2id": {label: index for index, label in enumerate(labels)},
        "problem_type": "multi_label_classification",
        "task_type": "code_review_multi_label",
        "taxonomy_version": taxonomy.version,
        "dataset_manifest_sha256": "a" * 64,
        "thresholds": {label: 0.5 for label in labels},
        "base_model_name": "microsoft/codebert-base",
        "training_git_sha": "0123456789abcdef",
    }
    config.update(overrides)
    return SimpleNamespace(config=SimpleNamespace(**config))


def _promotion_metadata(taxonomy: Taxonomy) -> dict[str, Any]:
    support = {
        label: {"known": 2, "positive": 1, "negative": 1}
        for label in taxonomy.trainable_ids()
    }
    tuning_support = {
        label: {
            "known": 2,
            "positive": 1,
            "negative": 1,
            "sufficient_for_tuning": True,
        }
        for label in taxonomy.trainable_ids()
    }
    return {
        "promotion_status": "approved_not_deployed",
        "promotion_gate_version": 1,
        "promotion_dataset_manifest_sha256": "a" * 64,
        "promotion_evaluation_sha256": "b" * 64,
        "promotion_deployment_smoke_sha256": "c" * 64,
        "promotion_deployment_smoke_passed": True,
        "promotion_evaluated_split": "test",
        "promotion_quality_critical_failures": 0,
        "promotion_baselines": ["rule", "tfidf_logistic"],
        "promotion_per_label_test_support": support,
        "promotion_metadata_sha256": "d" * 64,
        "promotion_auto_deploy": False,
        "thresholds_source": "frozen_validation",
        "threshold_tuning_dataset_manifest_sha256": "a" * 64,
        "threshold_tuning_split": "validation",
        "threshold_tuning_support": tuning_support,
    }


@pytest.fixture(scope="module")
def taxonomy() -> Taxonomy:
    return load_taxonomy()


def test_taxonomy_exposes_concrete_trainable_ids(taxonomy: Taxonomy) -> None:
    assert taxonomy.trainable_ids()
    assert "SECURITY_HARDCODED_SECRET" in taxonomy.trainable_ids()
    assert "MAINTAINABILITY_PRINT_STATEMENT" not in taxonomy.trainable_ids()
    assert len(taxonomy.ids()) == len(set(taxonomy.ids()))


def test_checkpoint_validation_accepts_complete_metadata(taxonomy: Taxonomy) -> None:
    result = validate_checkpoint_compatibility(_stub_model(taxonomy), taxonomy)
    assert result["status"] == "healthy"
    assert result["dataset_manifest_sha256"] == "a" * 64
    assert tuple(result["label_order"]) == tuple(taxonomy.trainable_ids())


def test_serving_requires_completed_promotion_gate(taxonomy: Taxonomy) -> None:
    unpromoted = validate_checkpoint_compatibility(
        _stub_model(taxonomy), taxonomy, require_promotion=True
    )
    assert unpromoted["status"] == "degraded"
    assert "promotion_status" in unpromoted["reason"]

    promoted = validate_checkpoint_compatibility(
        _stub_model(taxonomy, **_promotion_metadata(taxonomy)),
        taxonomy,
        require_promotion=True,
    )
    assert promoted["status"] == "healthy"
    assert promoted["promotion_status"] == "approved_not_deployed"


def test_serving_rejects_missing_deployment_smoke(taxonomy: Taxonomy) -> None:
    metadata = _promotion_metadata(taxonomy)
    metadata["promotion_deployment_smoke_passed"] = False
    result = validate_checkpoint_compatibility(
        _stub_model(taxonomy, **metadata),
        taxonomy,
        require_promotion=True,
    )
    assert result["status"] == "degraded"
    assert "promotion_deployment_smoke_passed" in result["reason"]


@pytest.mark.parametrize(
    ("override", "reason_fragment"),
    [
        ({"num_labels": 6}, "num_labels"),
        ({"problem_type": "single_label_classification"}, "problem_type"),
        ({"task_type": "comment_classification"}, "task_type"),
        ({"taxonomy_version": "99.0.0"}, "taxonomy_version"),
        ({"dataset_manifest_sha256": "not-a-hash"}, "dataset_manifest_sha256"),
        ({"base_model_name": ""}, "base_model_name"),
        ({"training_git_sha": ""}, "training_git_sha"),
    ],
)
def test_checkpoint_validation_rejects_incomplete_contract(
    taxonomy: Taxonomy,
    override: dict[str, Any],
    reason_fragment: str,
) -> None:
    result = validate_checkpoint_compatibility(
        _stub_model(taxonomy, **override), taxonomy
    )
    assert result["status"] == "degraded"
    assert reason_fragment in result["reason"]


def test_checkpoint_validation_rejects_wrong_label_order(taxonomy: Taxonomy) -> None:
    labels = list(taxonomy.trainable_ids())
    labels.reverse()
    result = validate_checkpoint_compatibility(
        _stub_model(
            taxonomy,
            id2label={index: label for index, label in enumerate(labels)},
        ),
        taxonomy,
    )
    assert result["status"] == "degraded"
    assert "label order" in result["reason"]


def test_checkpoint_validation_requires_exact_threshold_keys(
    taxonomy: Taxonomy,
) -> None:
    labels = list(taxonomy.trainable_ids())
    missing = {label: 0.5 for label in labels[1:]}
    result = validate_checkpoint_compatibility(
        _stub_model(taxonomy, thresholds=missing), taxonomy
    )
    assert result["status"] == "degraded"
    assert "thresholds" in result["reason"]

    extra = {label: 0.5 for label in labels}
    extra["UNKNOWN_LABEL"] = 0.5
    result = validate_checkpoint_compatibility(
        _stub_model(taxonomy, thresholds=extra), taxonomy
    )
    assert result["status"] == "degraded"


@pytest.mark.parametrize("threshold", [-0.01, 1.01, True, "0.5"])
def test_checkpoint_validation_rejects_invalid_thresholds(
    taxonomy: Taxonomy,
    threshold: Any,
) -> None:
    thresholds = {label: 0.5 for label in taxonomy.trainable_ids()}
    thresholds[taxonomy.trainable_ids()[0]] = threshold
    result = validate_checkpoint_compatibility(
        _stub_model(taxonomy, thresholds=thresholds), taxonomy
    )
    assert result["status"] == "degraded"


def test_quality_score_contract() -> None:
    findings = [
        Finding(
            antiPattern="SECURITY_HARDCODED_SECRET",
            category="SECURITY",
            severity="critical",
            confidence=1.0,
            explanation="Secret detected.",
        )
    ]
    assert compute_quality_score(findings) == pytest.approx(80.0)
