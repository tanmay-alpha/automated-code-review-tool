"""Checkpoint loading and inference shared by tune, evaluate, and promote."""

from __future__ import annotations

import importlib.metadata
import json
import platform
import subprocess
from pathlib import Path
from typing import Any

import numpy as np
import torch

from app.model import validate_checkpoint_compatibility
from app.preprocessing import build_model_text
from app.taxonomy import Taxonomy
from app.tokenizer_utils import windowed_model_logits
from training.dataset_contract import DatasetRecord


def git_sha() -> str:
    completed = subprocess.run(
        ["git", "rev-parse", "HEAD"], check=True, capture_output=True, text=True
    )
    value = completed.stdout.strip().lower()
    if len(value) != 40 or any(character not in "0123456789abcdef" for character in value):
        raise RuntimeError("git rev-parse HEAD did not return a full lowercase SHA")
    return value


def load_compatible_checkpoint(
    checkpoint: Path | str,
    taxonomy: Taxonomy,
    *,
    expected_manifest_sha256: str,
) -> tuple[Any, Any, dict[str, Any]]:
    from transformers import AutoModelForSequenceClassification, AutoTokenizer

    model = AutoModelForSequenceClassification.from_pretrained(str(checkpoint))
    tokenizer = AutoTokenizer.from_pretrained(str(checkpoint))
    compatibility = validate_checkpoint_compatibility(model, taxonomy)
    if compatibility.get("status") != "healthy":
        raise ValueError(
            "checkpoint is incompatible with serving: "
            + str(compatibility.get("reason", "unknown reason"))
        )
    if compatibility["dataset_manifest_sha256"] != expected_manifest_sha256:
        raise ValueError("checkpoint and frozen dataset manifest hashes do not match")
    return model, tokenizer, compatibility


def predict_scores(
    model: Any,
    tokenizer: Any,
    records: list[DatasetRecord],
    *,
    max_length: int,
    stride: int,
) -> tuple[np.ndarray, list[int]]:
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model.to(device)
    model.eval()
    scores: list[np.ndarray] = []
    windows: list[int] = []
    for record in records:
        text = build_model_text(record.raw_hunk, record.language)
        logits, window_count = windowed_model_logits(
            model,
            tokenizer,
            text,
            device=device,
            max_length=max_length,
            stride=stride,
        )
        scores.append(torch.sigmoid(logits).numpy())
        windows.append(window_count)
    return np.asarray(scores, dtype=np.float32), windows


def environment_metadata() -> dict[str, Any]:
    packages: dict[str, str] = {}
    for name in ("torch", "transformers", "numpy", "scikit-learn"):
        try:
            packages[name] = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError:
            continue
    return {
        "python": platform.python_version(),
        "platform": platform.platform(),
        "packages": packages,
        "cuda_available": torch.cuda.is_available(),
        "cuda_version": torch.version.cuda,
    }


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
