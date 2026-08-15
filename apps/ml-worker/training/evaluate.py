"""Evaluate one compatible tuned checkpoint once on a frozen test split."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from training.baselines import evaluate_baselines
from training.checkpoint import (
    environment_metadata,
    load_compatible_checkpoint,
    predict_scores,
    write_json,
)
from training.dataset_manifest import file_sha256
from training.frozen_dataset import load_frozen_dataset
from training.metrics import multilabel_metrics


def checkpoint_manifest(checkpoint: Path) -> dict[str, Any]:
    names = (
        "config.json",
        "model.safetensors",
        "pytorch_model.bin",
        "tokenizer.json",
        "tokenizer_config.json",
        "vocab.json",
        "merges.txt",
    )
    artifacts = {
        name: file_sha256(checkpoint / name)
        for name in names
        if (checkpoint / name).is_file()
    }
    if "config.json" not in artifacts or not any(
        name in artifacts for name in ("model.safetensors", "pytorch_model.bin")
    ):
        raise ValueError("checkpoint must contain config.json and local model weights")
    return {"schema_version": 1, "artifacts": artifacts}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-dir", required=True)
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--max-length", type=int)
    parser.add_argument("--stride", type=int)
    args = parser.parse_args(argv)

    dataset = load_frozen_dataset(args.dataset_dir)
    checkpoint = Path(args.checkpoint).resolve()
    if not checkpoint.is_dir():
        raise ValueError("evaluation requires a local immutable checkpoint directory")
    model, tokenizer, compatibility = load_compatible_checkpoint(
        checkpoint,
        dataset.taxonomy,
        expected_manifest_sha256=dataset.manifest.manifest_sha256,
    )
    if getattr(model.config, "thresholds_source", None) != "frozen_validation":
        raise ValueError("checkpoint thresholds were not produced by frozen validation tuning")
    if (
        getattr(model.config, "threshold_tuning_dataset_manifest_sha256", None)
        != dataset.manifest.manifest_sha256
        or getattr(model.config, "threshold_tuning_split", None) != "validation"
    ):
        raise ValueError("checkpoint threshold provenance does not match this dataset")
    thresholds = {
        label: float(compatibility["thresholds"][label])
        for label in dataset.label_order
    }
    max_length = args.max_length or int(getattr(model.config, "max_seq_length", 512))
    stride = (
        args.stride
        if args.stride is not None
        else int(getattr(model.config, "window_stride", 64))
    )
    test_records = dataset.split("test")
    scores, window_counts = predict_scores(
        model,
        tokenizer,
        test_records,
        max_length=max_length,
        stride=stride,
    )
    targets, mask = dataset.label_arrays(test_records)
    model_metrics = multilabel_metrics(
        targets, scores, mask, dataset.label_order, thresholds
    )
    baselines = evaluate_baselines(dataset)

    output_dir = Path(args.output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    metrics_artifact = {
        "contract_version": 1,
        "dataset_manifest_sha256": dataset.manifest.manifest_sha256,
        "taxonomy_version": dataset.taxonomy.version,
        "split": "test",
        "checkpoint": str(checkpoint),
        "thresholds_source": "frozen_validation",
        "max_length": max_length,
        "stride": stride,
        "window_aggregation": "max_logits",
        "sample_count": len(test_records),
        "window_counts": window_counts,
        "model": {"name": "transformer", "metrics": model_metrics},
        "baselines": {
            "rule": baselines["rule"],
            "tfidf_logistic": baselines["tfidf_logistic"],
        },
    }
    write_json(output_dir / "metrics.json", metrics_artifact)
    write_json(
        output_dir / "thresholds.json",
        {
            "dataset_manifest_sha256": dataset.manifest.manifest_sha256,
            "source_split": "validation",
            "thresholds": thresholds,
            "support": getattr(model.config, "threshold_tuning_support", {}),
        },
    )
    write_json(output_dir / "environment.json", environment_metadata())
    write_json(output_dir / "checkpoint_manifest.json", checkpoint_manifest(checkpoint))
    (output_dir / "dataset_manifest.json").write_text(
        (dataset.path / "manifest.json").read_text(encoding="utf-8"), encoding="utf-8"
    )
    (output_dir / "config.json").write_text(
        (checkpoint / "config.json").read_text(encoding="utf-8"), encoding="utf-8"
    )
    print(json.dumps(metrics_artifact, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
