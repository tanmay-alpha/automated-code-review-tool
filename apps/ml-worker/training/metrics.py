"""Masked multi-label metrics and validation-only threshold selection."""

from __future__ import annotations

from typing import Any, Iterable

import numpy as np


def _threshold_vector(
    label_order: tuple[str, ...], thresholds: dict[str, float] | float
) -> np.ndarray:
    if isinstance(thresholds, (int, float)) and not isinstance(thresholds, bool):
        return np.full(len(label_order), float(thresholds), dtype=np.float64)
    if set(thresholds) != set(label_order):
        raise ValueError("thresholds must contain exactly the canonical label order")
    values = np.asarray([float(thresholds[label]) for label in label_order])
    if np.any((values < 0.0) | (values > 1.0)):
        raise ValueError("thresholds must be in [0, 1]")
    return values


def multilabel_metrics(
    y_true: np.ndarray,
    y_score: np.ndarray,
    label_mask: np.ndarray,
    label_order: tuple[str, ...],
    thresholds: dict[str, float] | float,
) -> dict[str, Any]:
    """Calculate metrics only where an explicit label decision exists."""
    if y_true.shape != y_score.shape or y_true.shape != label_mask.shape:
        raise ValueError("targets, scores, and masks must have identical shapes")
    if y_true.ndim != 2 or y_true.shape[1] != len(label_order):
        raise ValueError("metric arrays do not match label_order")
    threshold_values = _threshold_vector(label_order, thresholds)
    prediction = y_score >= threshold_values[None, :]
    truth = y_true >= 0.5
    known = label_mask >= 0.5

    per_label: dict[str, dict[str, Any]] = {}
    total_tp = total_fp = total_fn = 0
    supported_f1: list[float] = []
    for index, label in enumerate(label_order):
        selected = known[:, index]
        actual = truth[selected, index]
        predicted = prediction[selected, index]
        tp = int(np.sum(actual & predicted))
        fp = int(np.sum(~actual & predicted))
        fn = int(np.sum(actual & ~predicted))
        tn = int(np.sum(~actual & ~predicted))
        precision = tp / (tp + fp) if tp + fp else 0.0
        recall = tp / (tp + fn) if tp + fn else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        support_positive = int(np.sum(actual))
        support_negative = int(np.sum(~actual))
        support_known = int(selected.sum())
        if support_known:
            supported_f1.append(f1)
        total_tp += tp
        total_fp += fp
        total_fn += fn
        per_label[label] = {
            "threshold": float(threshold_values[index]),
            "support_known": support_known,
            "support_positive": support_positive,
            "support_negative": support_negative,
            "true_positive": tp,
            "false_positive": fp,
            "false_negative": fn,
            "true_negative": tn,
            "precision": precision,
            "recall": recall,
            "f1": f1,
        }

    micro_precision = total_tp / (total_tp + total_fp) if total_tp + total_fp else 0.0
    micro_recall = total_tp / (total_tp + total_fn) if total_tp + total_fn else 0.0
    micro_f1 = (
        2 * micro_precision * micro_recall / (micro_precision + micro_recall)
        if micro_precision + micro_recall
        else 0.0
    )
    return {
        "known_decisions": int(known.sum()),
        "micro_precision": micro_precision,
        "micro_recall": micro_recall,
        "micro_f1": micro_f1,
        "macro_f1": float(np.mean(supported_f1)) if supported_f1 else 0.0,
        "per_label": per_label,
    }


def tune_thresholds(
    y_true: np.ndarray,
    y_score: np.ndarray,
    label_mask: np.ndarray,
    label_order: tuple[str, ...],
    *,
    candidates: Iterable[float] | None = None,
) -> tuple[dict[str, float], dict[str, dict[str, Any]]]:
    """Choose per-label F1 thresholds from validation decisions only.

    Labels without both positive and negative validation evidence retain 0.5
    and are marked insufficient; promotion later requires test support but does
    not invent a performance cutoff.
    """
    if y_true.shape != y_score.shape or y_true.shape != label_mask.shape:
        raise ValueError("targets, scores, and masks must have identical shapes")
    grid = sorted(set(float(value) for value in (candidates or np.linspace(0.05, 0.95, 19))))
    if not grid or grid[0] < 0 or grid[-1] > 1:
        raise ValueError("threshold candidates must be in [0, 1]")
    thresholds: dict[str, float] = {}
    support: dict[str, dict[str, Any]] = {}
    for index, label in enumerate(label_order):
        selected = label_mask[:, index] >= 0.5
        truth = y_true[selected, index] >= 0.5
        scores = y_score[selected, index]
        positives = int(truth.sum())
        negatives = int((~truth).sum())
        sufficient = positives > 0 and negatives > 0
        best_threshold = 0.5
        best_key = (-1.0, -1.0, -1.0)
        if sufficient:
            for threshold in grid:
                predicted = scores >= threshold
                tp = int(np.sum(truth & predicted))
                fp = int(np.sum(~truth & predicted))
                fn = int(np.sum(truth & ~predicted))
                precision = tp / (tp + fp) if tp + fp else 0.0
                recall = tp / (tp + fn) if tp + fn else 0.0
                f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
                key = (f1, -abs(threshold - 0.5), threshold)
                if key > best_key:
                    best_key = key
                    best_threshold = threshold
        thresholds[label] = float(best_threshold)
        support[label] = {
            "known": int(selected.sum()),
            "positive": positives,
            "negative": negatives,
            "sufficient_for_tuning": sufficient,
        }
    return thresholds, support
