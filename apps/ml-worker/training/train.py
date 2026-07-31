"""
automated-code-review-tool — CodeBERT fine-tuning script.

Supports CLI flags:
--data-dir, --output-dir, --model-name, --epochs, --batch-size,
--learning-rate, --threshold, --seed, --push-to-hub, --hf-repo
"""
from __future__ import annotations

import argparse
import json
import os
import random
import sys
from pathlib import Path

import numpy as np
import torch
from sklearn.metrics import f1_score
from torch.utils.data import Dataset
from transformers import (
    AutoModelForSequenceClassification,
    AutoTokenizer,
    Trainer,
    TrainingArguments,
)

# Shared taxonomy and preprocessing imports
_HERE = Path(__file__).resolve().parent
_ML_WORKER = _HERE.parent
_REPO_ROOT = _ML_WORKER.parent
for _p in (str(_ML_WORKER), str(_REPO_ROOT)):
    if _p not in sys.path:
        sys.path.insert(0, _p)

from app.taxonomy import load_taxonomy, trainable_ids  # noqa: E402
from app.tokenizer_utils import build_model_input  # noqa: E402

TAXONOMY = load_taxonomy()
TRAINABLE_IDS = trainable_ids()
NUM_LABELS = len(TRAINABLE_IDS)


def set_seed(seed: int) -> None:
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(seed)


def _load_split(path: Path) -> list[dict]:
    if not path.exists():
        raise FileNotFoundError(f"Split file not found: {path}")
    with path.open("r", encoding="utf-8") as f:
        payload = json.load(f)
    if isinstance(payload, dict) and "data" in payload and isinstance(payload["data"], list):
        return payload["data"]
    if isinstance(payload, list):
        return payload
    raise ValueError(f"Unexpected split format in {path}: type is {type(payload).__name__}")


class CodeReviewDataset(Dataset):
    """Dataset reading anti_patterns (failing loudly on unknown IDs)."""

    def __init__(self, records: list[dict], tokenizer: AutoTokenizer, max_length: int = 512) -> None:
        self.records = records
        self.tokenizer = tokenizer
        self.max_length = max_length

    def __len__(self) -> int:
        return len(self.records)

    def __getitem__(self, idx: int) -> dict[str, torch.Tensor]:
        rec = self.records[idx]
        diff_text = rec.get("diff", "")

        item = build_model_input(diff_text, self.tokenizer, max_length=self.max_length)

        ap_list = rec.get("anti_patterns", rec.get("labels", []))
        labels_arr: np.ndarray = np.zeros(NUM_LABELS, dtype=np.float32)

        for ap_id in ap_list:
            if ap_id not in TAXONOMY.ids():
                raise ValueError(f"Unknown taxonomy anti-pattern ID in record: {ap_id!r}")
            if ap_id in TRAINABLE_IDS:
                labels_arr[TRAINABLE_IDS.index(ap_id)] = 1.0

        item["labels"] = torch.tensor(labels_arr, dtype=torch.float32)
        return item


def compute_metrics_builder(threshold: float):
    def compute_metrics(eval_pred: tuple[np.ndarray, np.ndarray]) -> dict[str, float]:
        logits, labels = eval_pred
        probs = 1.0 / (1.0 + np.exp(-logits))
        preds = (probs >= threshold).astype(int)

        per_label_f1 = f1_score(labels, preds, average=None, zero_division=0)
        macro_f1 = f1_score(labels, preds, average="macro", zero_division=0)

        metrics = {"macro_f1": float(macro_f1)}
        for name, score in zip(TRAINABLE_IDS, per_label_f1):
            metrics[f"f1_{name}"] = float(score)

        return metrics
    return compute_metrics


class MultilabelTrainer(Trainer):
    def compute_loss(
        self,
        model: torch.nn.Module,
        inputs: dict[str, torch.Tensor],
        return_outputs: bool = False,
        **kwargs,
    ) -> torch.Tensor | tuple[torch.Tensor, torch.Tensor]:
        labels = inputs.pop("labels")
        outputs = model(**inputs)
        logits = outputs.logits
        loss_fct = torch.nn.BCEWithLogitsLoss()
        loss = loss_fct(logits, labels)
        return (loss, outputs) if return_outputs else loss


def parse_args(args: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Train CodeBERT on anti-pattern dataset.")
    parser.add_argument("--data-dir", default="./training/data", help="Directory containing train.json and val.json")
    parser.add_argument("--output-dir", default="./automated-code-review-tool-model", help="Directory to save model")
    parser.add_argument("--model-name", default="microsoft/codebert-base", help="Base model checkpoint name")
    parser.add_argument("--epochs", type=int, default=5, help="Number of training epochs")
    parser.add_argument("--batch-size", type=int, default=16, help="Per-device train/eval batch size")
    parser.add_argument("--learning-rate", type=float, default=2e-5, help="Learning rate")
    parser.add_argument("--threshold", type=float, default=0.5, help="Classification threshold")
    parser.add_argument("--seed", type=int, default=42, help="Random seed")
    parser.add_argument("--push-to-hub", action="store_true", help="Push saved model to HuggingFace Hub")
    parser.add_argument("--hf-repo", default="tanmay-alpha/automated-code-review-tool-codebert", help="HF repository ID")
    return parser.parse_args(args)


def main(args_list: list[str] | None = None) -> None:
    args = parse_args(args_list)
    set_seed(args.seed)

    data_dir = Path(args.data_dir)
    train_path = data_dir / "train.json"
    val_path = data_dir / "val.json"

    if not train_path.exists() or not val_path.exists():
        print(f"Error: dataset files not found at {train_path} or {val_path}.", file=sys.stderr)
        sys.exit(1)

    train_records = _load_split(train_path)
    val_records = _load_split(val_path)

    tokenizer = AutoTokenizer.from_pretrained(args.model_name)
    train_dataset = CodeReviewDataset(train_records, tokenizer)
    val_dataset = CodeReviewDataset(val_records, tokenizer)

    id2label = {i: name for i, name in enumerate(TRAINABLE_IDS)}
    label2id = {name: i for i, name in enumerate(TRAINABLE_IDS)}

    model = AutoModelForSequenceClassification.from_pretrained(
        args.model_name,
        num_labels=NUM_LABELS,
        problem_type="multi_label_classification",
        id2label=id2label,
        label2id=label2id,
    )

    # Attach required contract metadata to model config
    model.config.taxonomy_version = TAXONOMY.version
    model.config.task_type = "code_review_multi_label"
    model.config.training_git_sha = os.environ.get("GIT_SHA", "dev-local")
    model.config.dataset_manifest_sha256 = os.environ.get("DATASET_MANIFEST_SHA256", "local-build")
    model.config.base_model_name = args.model_name
    model.config.thresholds = {name: args.threshold for name in TRAINABLE_IDS}

    training_args = TrainingArguments(
        output_dir=args.output_dir,
        eval_strategy="epoch",
        save_strategy="epoch",
        learning_rate=args.learning_rate,
        per_device_train_batch_size=args.batch_size,
        per_device_eval_batch_size=args.batch_size,
        num_train_epochs=args.epochs,
        load_best_model_at_end=True,
        metric_for_best_model="macro_f1",
        greater_is_better=True,
        seed=args.seed,
        report_to="none",
    )

    trainer = MultilabelTrainer(
        model=model,
        args=training_args,
        train_dataset=train_dataset,
        eval_dataset=val_dataset,
        processing_class=tokenizer,
        compute_metrics=compute_metrics_builder(args.threshold),
    )

    trainer.train()
    eval_metrics = trainer.evaluate()
    print("Validation metrics:", eval_metrics)

    trainer.save_model(args.output_dir)
    tokenizer.save_pretrained(args.output_dir)

    if args.push_to_hub:
        token = os.environ.get("HF_TOKEN")
        if token:
            model.push_to_hub(args.hf_repo, token=token)
            tokenizer.push_to_hub(args.hf_repo, token=token)
        else:
            print("HF_TOKEN environment variable not set; skipping Hub push.")


if __name__ == "__main__":
    main()
