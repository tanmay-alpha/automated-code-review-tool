"""Verify evidence and mark a checkpoint eligible; never deploy it automatically."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any

from app.model import validate_checkpoint_compatibility
from training.checkpoint import git_sha, load_compatible_checkpoint, write_json
from training.dataset_manifest import canonical_json_bytes, file_sha256
from training.evaluate import checkpoint_manifest
from training.frozen_dataset import load_frozen_dataset


def _read_object(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return value


def _support_gate(
    per_label: dict[str, Any], label_order: tuple[str, ...]
) -> dict[str, dict[str, int]]:
    if set(per_label) != set(label_order):
        raise ValueError("evaluation metrics do not contain every canonical label")
    support: dict[str, dict[str, int]] = {}
    for label in label_order:
        row = per_label[label]
        values = {
            "known": int(row.get("support_known", 0)),
            "positive": int(row.get("support_positive", 0)),
            "negative": int(row.get("support_negative", 0)),
        }
        if min(values.values()) <= 0:
            raise ValueError(
                f"test support for {label} requires known positive and negative decisions"
            )
        support[label] = values
    return support


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-dir", required=True)
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--evaluation-dir", required=True)
    parser.add_argument("--smoke-report", required=True)
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args(argv)

    dataset = load_frozen_dataset(args.dataset_dir)
    critical_failures = dataset.quality_report.get("critical_failures")
    if not isinstance(critical_failures, int) or critical_failures != 0:
        raise ValueError("dataset quality report contains critical failures")
    checkpoint = Path(args.checkpoint).resolve()
    evaluation_dir = Path(args.evaluation_dir).resolve()
    output_dir = Path(args.output_dir).resolve()
    if output_dir == checkpoint:
        raise ValueError("promotion output must be a new checkpoint directory")

    metrics_path = evaluation_dir / "metrics.json"
    metrics = _read_object(metrics_path)
    if metrics.get("split") != "test":
        raise ValueError("promotion requires a frozen-test evaluation artifact")
    if metrics.get("dataset_manifest_sha256") != dataset.manifest.manifest_sha256:
        raise ValueError("evaluation and dataset manifest hashes do not match")
    evaluated_checkpoint = Path(str(metrics.get("checkpoint", ""))).resolve()
    if evaluated_checkpoint != checkpoint:
        raise ValueError("evaluation was produced from a different checkpoint")

    recorded_checkpoint = _read_object(evaluation_dir / "checkpoint_manifest.json")
    current_checkpoint = checkpoint_manifest(checkpoint)
    if recorded_checkpoint != current_checkpoint:
        raise ValueError("checkpoint files changed after frozen-test evaluation")
    smoke_path = Path(args.smoke_report).resolve()
    smoke = _read_object(smoke_path)
    current_checkpoint_sha = hashlib.sha256(
        canonical_json_bytes(current_checkpoint)
    ).hexdigest()
    expected_smoke = {
        "passed": True,
        "dataset_manifest_sha256": dataset.manifest.manifest_sha256,
        "taxonomy_version": dataset.taxonomy.version,
        "checkpoint_manifest_sha256": current_checkpoint_sha,
        "compatibility_status": "healthy",
    }
    for field, expected in expected_smoke.items():
        if smoke.get(field) != expected:
            raise ValueError(f"deployment smoke report has invalid {field}")
    if not smoke.get("finite_scores") or int(smoke.get("windows_processed", 0)) <= 0:
        raise ValueError("deployment smoke report lacks successful minimal inference")
    model_metrics = metrics.get("model", {}).get("metrics", {})
    support = _support_gate(model_metrics.get("per_label", {}), dataset.label_order)
    baselines = metrics.get("baselines", {})
    if set(baselines) != {"rule", "tfidf_logistic"}:
        raise ValueError("evaluation must compare rule and TF-IDF baselines")
    for name in ("rule", "tfidf_logistic"):
        baseline_labels = baselines[name].get("metrics", {}).get("per_label", {})
        if set(baseline_labels) != set(dataset.label_order):
            raise ValueError(f"{name} baseline lacks canonical per-label metrics")

    model, tokenizer, compatibility = load_compatible_checkpoint(
        checkpoint,
        dataset.taxonomy,
        expected_manifest_sha256=dataset.manifest.manifest_sha256,
    )
    if getattr(model.config, "thresholds_source", None) != "frozen_validation":
        raise ValueError("promotion requires validation-only tuned thresholds")
    tuning_support = getattr(model.config, "threshold_tuning_support", None)
    if not isinstance(tuning_support, dict) or set(tuning_support) != set(
        dataset.label_order
    ):
        raise ValueError("checkpoint lacks per-label validation tuning support")
    insufficient = [
        label
        for label in dataset.label_order
        if not bool(tuning_support[label].get("sufficient_for_tuning"))
    ]
    if insufficient:
        raise ValueError(
            f"validation lacks positive/negative threshold support for: {insufficient}"
        )

    metadata: dict[str, Any] = {
        "promotion_gate_version": 1,
        "promotion_status": "approved_not_deployed",
        "dataset_manifest_sha256": dataset.manifest.manifest_sha256,
        "evaluation_sha256": file_sha256(metrics_path),
        "deployment_smoke_sha256": file_sha256(smoke_path),
        "deployment_smoke_passed": True,
        "evaluated_split": "test",
        "quality_critical_failures": 0,
        "baseline_comparisons": ["rule", "tfidf_logistic"],
        "per_label_test_support": support,
        "thresholds_source": "frozen_validation",
        "promotion_git_sha": git_sha(),
        "automatic_deployment_enabled": False,
        "performance_cutoff_applied": False,
    }
    metadata_sha = hashlib.sha256(canonical_json_bytes(metadata)).hexdigest()
    metadata["promotion_metadata_sha256"] = metadata_sha

    model.config.promotion_status = metadata["promotion_status"]
    model.config.promotion_gate_version = metadata["promotion_gate_version"]
    model.config.promotion_dataset_manifest_sha256 = metadata[
        "dataset_manifest_sha256"
    ]
    model.config.promotion_evaluation_sha256 = metadata["evaluation_sha256"]
    model.config.promotion_deployment_smoke_sha256 = metadata[
        "deployment_smoke_sha256"
    ]
    model.config.promotion_deployment_smoke_passed = True
    model.config.promotion_evaluated_split = metadata["evaluated_split"]
    model.config.promotion_quality_critical_failures = 0
    model.config.promotion_baselines = metadata["baseline_comparisons"]
    model.config.promotion_per_label_test_support = support
    model.config.promotion_metadata_sha256 = metadata_sha
    model.config.promotion_auto_deploy = False
    final_compatibility = validate_checkpoint_compatibility(
        model, dataset.taxonomy, require_promotion=True
    )
    if final_compatibility.get("status") != "healthy":
        raise ValueError(f"promoted checkpoint became incompatible: {final_compatibility}")

    output_dir.mkdir(parents=True, exist_ok=False)
    model.save_pretrained(output_dir)
    tokenizer.save_pretrained(output_dir)
    write_json(output_dir / "promotion.json", metadata)
    (output_dir / "dataset_manifest.json").write_text(
        (dataset.path / "manifest.json").read_text(encoding="utf-8"), encoding="utf-8"
    )
    print(json.dumps(metadata, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
