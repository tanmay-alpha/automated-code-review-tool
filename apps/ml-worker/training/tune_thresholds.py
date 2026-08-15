"""Tune checkpoint thresholds exclusively on the frozen validation split."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from app.model import validate_checkpoint_compatibility
from training.checkpoint import load_compatible_checkpoint, predict_scores, write_json
from training.frozen_dataset import load_frozen_dataset
from training.metrics import multilabel_metrics, tune_thresholds


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-dir", required=True)
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--max-length", type=int)
    parser.add_argument("--stride", type=int)
    args = parser.parse_args(argv)

    dataset = load_frozen_dataset(args.dataset_dir)
    validation_records = dataset.split("validation")
    model, tokenizer, _ = load_compatible_checkpoint(
        args.checkpoint,
        dataset.taxonomy,
        expected_manifest_sha256=dataset.manifest.manifest_sha256,
    )
    max_length = args.max_length or int(getattr(model.config, "max_seq_length", 512))
    stride = (
        args.stride
        if args.stride is not None
        else int(getattr(model.config, "window_stride", 64))
    )
    scores, window_counts = predict_scores(
        model,
        tokenizer,
        validation_records,
        max_length=max_length,
        stride=stride,
    )
    targets, mask = dataset.label_arrays(validation_records)
    thresholds, support = tune_thresholds(
        targets, scores, mask, dataset.label_order
    )
    metrics = multilabel_metrics(
        targets, scores, mask, dataset.label_order, thresholds
    )

    model.config.thresholds = thresholds
    model.config.thresholds_source = "frozen_validation"
    model.config.threshold_tuning_dataset_manifest_sha256 = (
        dataset.manifest.manifest_sha256
    )
    model.config.threshold_tuning_split = "validation"
    model.config.threshold_tuning_support = support
    compatibility = validate_checkpoint_compatibility(model, dataset.taxonomy)
    if compatibility.get("status") != "healthy":
        raise ValueError(f"tuned checkpoint is incompatible: {compatibility}")

    output_dir = Path(args.output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    model.save_pretrained(output_dir)
    tokenizer.save_pretrained(output_dir)
    artifact = {
        "contract_version": 1,
        "dataset_manifest_sha256": dataset.manifest.manifest_sha256,
        "source_split": "validation",
        "label_order": list(dataset.label_order),
        "thresholds": thresholds,
        "support": support,
        "metrics": metrics,
        "window_counts": window_counts,
        "max_length": max_length,
        "stride": stride,
    }
    write_json(output_dir / "thresholds.json", artifact)
    print(json.dumps(artifact, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
