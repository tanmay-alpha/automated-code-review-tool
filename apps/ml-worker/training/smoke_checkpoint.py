"""Run a real local load, compatibility, and minimal-inference smoke check."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np

from training.checkpoint import load_compatible_checkpoint, predict_scores, write_json
from training.dataset_manifest import canonical_json_bytes
from training.evaluate import checkpoint_manifest
from training.frozen_dataset import load_frozen_dataset


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-dir", required=True)
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args(argv)

    dataset = load_frozen_dataset(args.dataset_dir)
    checkpoint = Path(args.checkpoint).resolve()
    model, tokenizer, compatibility = load_compatible_checkpoint(
        checkpoint,
        dataset.taxonomy,
        expected_manifest_sha256=dataset.manifest.manifest_sha256,
    )
    record = dataset.split("validation")[0]
    max_length = int(getattr(model.config, "max_seq_length", 512))
    stride = int(getattr(model.config, "window_stride", 64))
    scores, windows = predict_scores(
        model,
        tokenizer,
        [record],
        max_length=max_length,
        stride=stride,
    )
    if scores.shape != (1, len(dataset.label_order)) or not np.isfinite(scores).all():
        raise ValueError("smoke inference returned invalid score shape or non-finite values")
    checkpoint_files = checkpoint_manifest(checkpoint)
    checkpoint_sha = hashlib.sha256(canonical_json_bytes(checkpoint_files)).hexdigest()
    report = {
        "schema_version": 1,
        "passed": True,
        "dataset_manifest_sha256": dataset.manifest.manifest_sha256,
        "taxonomy_version": dataset.taxonomy.version,
        "checkpoint_manifest_sha256": checkpoint_sha,
        "compatibility_status": compatibility["status"],
        "label_count": len(dataset.label_order),
        "sample_id_sha256": hashlib.sha256(record.sample_id.encode()).hexdigest(),
        "windows_processed": windows[0],
        "finite_scores": True,
        "max_length": max_length,
        "stride": stride,
    }
    write_json(Path(args.output), report)
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
