"""Train a multi-label checkpoint from train/validation of one frozen dataset."""

from __future__ import annotations

import argparse
import json
import random
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Callable

import numpy as np
import torch
from torch.nn import functional as F
from torch.utils.data import DataLoader, Dataset

from app.model import validate_checkpoint_compatibility
from app.preprocessing import build_model_text
from app.tokenizer_utils import sliding_window_tokenize
from training.checkpoint import environment_metadata, git_sha, write_json
from training.dataset_contract import DatasetRecord
from training.frozen_dataset import FrozenDataset, load_frozen_dataset


@dataclass(frozen=True)
class TrainConfig:
    base_model: str
    epochs: int
    batch_size: int
    learning_rate: float
    weight_decay: float
    patience: int
    seed: int
    max_length: int
    stride: int
    max_pos_weight: float


class RecordDataset(Dataset[DatasetRecord]):
    def __init__(self, records: list[DatasetRecord]) -> None:
        self.records = records

    def __len__(self) -> int:
        return len(self.records)

    def __getitem__(self, index: int) -> DatasetRecord:
        return self.records[index]


def set_seed(seed: int) -> None:
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(seed)
    torch.use_deterministic_algorithms(True, warn_only=True)


def positive_weights(
    dataset: FrozenDataset,
    records: list[DatasetRecord],
    *,
    cap: float,
) -> torch.Tensor:
    targets, masks = dataset.label_arrays(records)
    positives = (targets * masks).sum(axis=0)
    negatives = ((1.0 - targets) * masks).sum(axis=0)
    unsupported = [
        label
        for index, label in enumerate(dataset.label_order)
        if positives[index] == 0 or negatives[index] == 0
    ]
    if unsupported:
        raise ValueError(
            "train split requires explicit positive and negative evidence for: "
            f"{unsupported}"
        )
    weights = np.ones(len(dataset.label_order), dtype=np.float32)
    supported = positives > 0
    weights[supported] = np.minimum(negatives[supported] / positives[supported], cap)
    weights = np.maximum(weights, 1.0)
    return torch.as_tensor(weights, dtype=torch.float32)


def make_collator(
    tokenizer: Any,
    label_order: tuple[str, ...],
    *,
    max_length: int,
    stride: int,
) -> Callable[[list[DatasetRecord]], dict[str, torch.Tensor]]:
    def collate(records: list[DatasetRecord]) -> dict[str, torch.Tensor]:
        window_fields: dict[str, list[list[int]]] = {}
        targets: list[list[float]] = []
        masks: list[list[float]] = []
        for record in records:
            target, mask = record.label_vectors(label_order)
            windows = sliding_window_tokenize(
                build_model_text(record.raw_hunk, record.language),
                tokenizer,
                max_length=max_length,
                stride=stride,
            )
            for window in windows:
                for field, values in window.items():
                    window_fields.setdefault(field, []).append(values)
                targets.append(target)
                masks.append(mask)
        batch = {
            field: torch.as_tensor(values, dtype=torch.long)
            for field, values in window_fields.items()
        }
        batch["labels"] = torch.as_tensor(targets, dtype=torch.float32)
        batch["label_mask"] = torch.as_tensor(masks, dtype=torch.float32)
        return batch

    return collate


def masked_bce_loss(
    logits: torch.Tensor,
    targets: torch.Tensor,
    mask: torch.Tensor,
    pos_weight: torch.Tensor,
) -> torch.Tensor:
    if not torch.any(mask > 0):
        raise ValueError("batch contains no explicit label decisions")
    losses = F.binary_cross_entropy_with_logits(
        logits, targets, pos_weight=pos_weight, reduction="none"
    )
    return (losses * mask).sum() / mask.sum()


def _epoch_loss(
    model: Any,
    loader: DataLoader[DatasetRecord],
    *,
    device: torch.device,
    pos_weight: torch.Tensor,
    optimizer: torch.optim.Optimizer | None,
) -> float:
    training = optimizer is not None
    model.train(training)
    total = 0.0
    batches = 0
    for batch in loader:
        labels = batch.pop("labels").to(device)
        mask = batch.pop("label_mask").to(device)
        inputs = {key: value.to(device) for key, value in batch.items()}
        with torch.set_grad_enabled(training):
            logits = model(**inputs).logits
            loss = masked_bce_loss(logits, labels, mask, pos_weight)
            if optimizer is not None:
                optimizer.zero_grad(set_to_none=True)
                loss.backward()
                optimizer.step()
        total += float(loss.detach().cpu())
        batches += 1
    if batches == 0:
        raise ValueError("data loader produced no batches")
    return total / batches


def train(args: argparse.Namespace) -> dict[str, Any]:
    from transformers import AutoModelForSequenceClassification, AutoTokenizer

    config = TrainConfig(
        base_model=args.base_model,
        epochs=args.epochs,
        batch_size=args.batch_size,
        learning_rate=args.learning_rate,
        weight_decay=args.weight_decay,
        patience=args.patience,
        seed=args.seed,
        max_length=args.max_length,
        stride=args.stride,
        max_pos_weight=args.max_pos_weight,
    )
    set_seed(config.seed)
    dataset = load_frozen_dataset(args.dataset_dir)
    train_records = dataset.split("train")
    validation_records = dataset.split("validation")
    output_dir = Path(args.output_dir).resolve()
    if (output_dir / "config.json").exists() and not args.overwrite_output_dir:
        raise ValueError("output directory already contains a checkpoint")
    output_dir.mkdir(parents=True, exist_ok=True)

    tokenizer = AutoTokenizer.from_pretrained(config.base_model)
    labels = dataset.label_order
    id2label = {index: label for index, label in enumerate(labels)}
    label2id = {label: index for index, label in enumerate(labels)}
    model = AutoModelForSequenceClassification.from_pretrained(
        config.base_model,
        num_labels=len(labels),
        id2label=id2label,
        label2id=label2id,
        problem_type="multi_label_classification",
        ignore_mismatched_sizes=True,
    )
    training_sha = git_sha()
    model.config.task_type = "code_review_multi_label"
    model.config.taxonomy_version = dataset.taxonomy.version
    model.config.dataset_manifest_sha256 = dataset.manifest.manifest_sha256
    model.config.base_model_name = config.base_model
    model.config.training_git_sha = training_sha
    model.config.thresholds = {label: 0.5 for label in labels}
    model.config.thresholds_source = "pending_validation_tuning"
    model.config.preprocessing_contract = "build_model_text:v1"
    model.config.max_seq_length = config.max_length
    model.config.window_stride = config.stride
    model.config.window_aggregation = "max_logits"
    model.config.model_version = (
        f"{dataset.manifest.dataset_name}-{dataset.manifest.dataset_version}"
    )
    compatibility = validate_checkpoint_compatibility(model, dataset.taxonomy)
    if compatibility.get("status") != "healthy":
        raise ValueError(f"new checkpoint metadata is incompatible: {compatibility}")

    collator = make_collator(
        tokenizer,
        labels,
        max_length=config.max_length,
        stride=config.stride,
    )
    generator = torch.Generator().manual_seed(config.seed)
    train_loader = DataLoader(
        RecordDataset(train_records),
        batch_size=config.batch_size,
        shuffle=True,
        generator=generator,
        collate_fn=collator,
    )
    validation_loader = DataLoader(
        RecordDataset(validation_records),
        batch_size=config.batch_size,
        shuffle=False,
        collate_fn=collator,
    )
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model.to(device)
    pos_weight = positive_weights(
        dataset, train_records, cap=config.max_pos_weight
    ).to(device)
    optimizer = torch.optim.AdamW(
        model.parameters(),
        lr=config.learning_rate,
        weight_decay=config.weight_decay,
    )

    history: list[dict[str, float | int]] = []
    best_validation = float("inf")
    stale_epochs = 0
    best_epoch = 0
    for epoch in range(1, config.epochs + 1):
        train_loss = _epoch_loss(
            model,
            train_loader,
            device=device,
            pos_weight=pos_weight,
            optimizer=optimizer,
        )
        validation_loss = _epoch_loss(
            model,
            validation_loader,
            device=device,
            pos_weight=pos_weight,
            optimizer=None,
        )
        history.append(
            {"epoch": epoch, "train_loss": train_loss, "validation_loss": validation_loss}
        )
        if validation_loss < best_validation:
            best_validation = validation_loss
            best_epoch = epoch
            stale_epochs = 0
            model.config.best_validation_loss = best_validation
            model.config.best_epoch = best_epoch
            model.save_pretrained(output_dir)
            tokenizer.save_pretrained(output_dir)
        else:
            stale_epochs += 1
            if stale_epochs >= config.patience:
                break

    metadata = {
        "contract_version": 1,
        "dataset_manifest_sha256": dataset.manifest.manifest_sha256,
        "taxonomy_version": dataset.taxonomy.version,
        "training_git_sha": training_sha,
        "config": asdict(config),
        "label_order": list(labels),
        "positive_weights": pos_weight.detach().cpu().tolist(),
        "best_epoch": best_epoch,
        "best_validation_loss": best_validation,
        "history": history,
        "test_split_accessed": False,
    }
    write_json(output_dir / "training_metadata.json", metadata)
    write_json(output_dir / "environment.json", environment_metadata())
    (output_dir / "dataset_manifest.json").write_text(
        (dataset.path / "manifest.json").read_text(encoding="utf-8"), encoding="utf-8"
    )
    return metadata


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-dir", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--base-model", default="microsoft/codebert-base")
    parser.add_argument("--epochs", type=int, default=5)
    parser.add_argument("--batch-size", type=int, default=4)
    parser.add_argument("--learning-rate", type=float, default=2e-5)
    parser.add_argument("--weight-decay", type=float, default=0.01)
    parser.add_argument("--patience", type=int, default=2)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--max-length", type=int, default=512)
    parser.add_argument("--stride", type=int, default=64)
    parser.add_argument("--max-pos-weight", type=float, default=20.0)
    parser.add_argument("--overwrite-output-dir", action="store_true")
    args = parser.parse_args(argv)
    if min(args.epochs, args.batch_size, args.patience, args.max_length) <= 0:
        parser.error("epochs, batch-size, patience, and max-length must be positive")
    if args.learning_rate <= 0 or args.max_pos_weight < 1:
        parser.error("learning-rate must be positive and max-pos-weight at least 1")
    return args


def main(argv: list[str] | None = None) -> int:
    metadata = train(parse_args(argv))
    print(json.dumps(metadata, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
