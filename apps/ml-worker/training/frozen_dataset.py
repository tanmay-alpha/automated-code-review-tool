"""Read-only access to a validated immutable dataset release."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Literal

import numpy as np

from app.taxonomy import Taxonomy, load_taxonomy
from training.dataset_contract import DatasetRecord, read_records
from training.dataset_manifest import DatasetManifest, read_manifest
from training.validate_dataset import validate_dataset_dir

Split = Literal["train", "validation", "test"]


@dataclass(frozen=True)
class FrozenDataset:
    path: Path
    manifest: DatasetManifest
    taxonomy: Taxonomy
    records: tuple[DatasetRecord, ...]
    quality_report: dict[str, object]

    @property
    def label_order(self) -> tuple[str, ...]:
        return tuple(self.taxonomy.trainable_ids())

    def split(self, name: Split) -> list[DatasetRecord]:
        records = [record for record in self.records if record.split == name]
        if not records:
            raise ValueError(f"frozen dataset has no {name!r} records")
        return records

    def label_arrays(
        self, records: list[DatasetRecord]
    ) -> tuple[np.ndarray, np.ndarray]:
        targets: list[list[float]] = []
        masks: list[list[float]] = []
        for record in records:
            target, mask = record.label_vectors(self.label_order)
            targets.append(target)
            masks.append(mask)
        return np.asarray(targets, dtype=np.float32), np.asarray(masks, dtype=np.float32)


def load_frozen_dataset(
    dataset_dir: Path | str,
    *,
    allow_synthetic_smoke: bool = False,
) -> FrozenDataset:
    path = Path(dataset_dir).resolve()
    manifest = read_manifest(path / "manifest.json")
    if not manifest.frozen:
        raise ValueError("training and evaluation require a frozen dataset manifest")
    if manifest.synthetic and not allow_synthetic_smoke:
        raise ValueError("synthetic datasets are smoke-only and cannot produce model claims")
    report = validate_dataset_dir(path)
    if int(report["critical_failures"]) != 0:
        raise ValueError(
            f"dataset has {report['critical_failures']} critical quality failures"
        )
    taxonomy = load_taxonomy()
    records = tuple(read_records(path / "samples.jsonl"))
    return FrozenDataset(path, manifest, taxonomy, records, report)
