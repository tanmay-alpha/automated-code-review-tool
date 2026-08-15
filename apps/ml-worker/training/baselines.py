"""Rule and TF-IDF baselines evaluated on the same frozen split contract."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

import numpy as np

from app.preprocessing import build_model_text
from training.dataset_contract import DatasetRecord
from training.frozen_dataset import FrozenDataset, load_frozen_dataset
from training.metrics import multilabel_metrics, tune_thresholds


def rule_scores(
    records: list[DatasetRecord], label_order: tuple[str, ...]
) -> np.ndarray:
    """Return binary scores from the production deterministic fallback rules."""
    from app.fallback_scanner import fallback_findings

    index = {label: position for position, label in enumerate(label_order)}
    scores = np.zeros((len(records), len(label_order)), dtype=np.float32)
    for row_index, record in enumerate(records):
        findings, _, _ = fallback_findings(record.raw_hunk)
        for finding in findings:
            position = index.get(finding.anti_pattern_id)
            if position is not None:
                scores[row_index, position] = max(
                    scores[row_index, position], float(finding.confidence)
                )
    return scores


def tfidf_scores(
    train_records: list[DatasetRecord],
    validation_records: list[DatasetRecord],
    test_records: list[DatasetRecord],
    dataset: FrozenDataset,
) -> tuple[np.ndarray, np.ndarray, dict[str, Any]]:
    """Fit train-only one-vs-rest logistic models and score validation/test."""
    from sklearn.feature_extraction.text import TfidfVectorizer
    from sklearn.linear_model import LogisticRegression

    train_text = [build_model_text(row.raw_hunk, row.language) for row in train_records]
    validation_text = [
        build_model_text(row.raw_hunk, row.language) for row in validation_records
    ]
    test_text = [build_model_text(row.raw_hunk, row.language) for row in test_records]
    vectorizer = TfidfVectorizer(
        analyzer="char_wb", ngram_range=(3, 5), min_df=1, max_features=50_000
    )
    train_features = vectorizer.fit_transform(train_text)
    validation_features = vectorizer.transform(validation_text)
    test_features = vectorizer.transform(test_text)
    y_train, train_mask = dataset.label_arrays(train_records)
    validation_scores = np.zeros(
        (len(validation_records), len(dataset.label_order)), dtype=np.float32
    )
    test_scores = np.zeros(
        (len(test_records), len(dataset.label_order)), dtype=np.float32
    )
    fit_report: dict[str, Any] = {}
    for index, label in enumerate(dataset.label_order):
        known = train_mask[:, index] >= 0.5
        target = y_train[known, index].astype(np.int32)
        classes = np.unique(target)
        if len(target) == 0:
            fit_report[label] = {"trained": False, "reason": "no known train labels"}
            continue
        if len(classes) == 1:
            constant = float(classes[0])
            validation_scores[:, index] = constant
            test_scores[:, index] = constant
            fit_report[label] = {
                "trained": False,
                "reason": "single train class",
                "constant_score": constant,
            }
            continue
        classifier = LogisticRegression(
            class_weight="balanced",
            max_iter=1_000,
            random_state=dataset.manifest.seed,
            solver="liblinear",
        )
        classifier.fit(train_features[known], target)
        validation_scores[:, index] = classifier.predict_proba(validation_features)[:, 1]
        test_scores[:, index] = classifier.predict_proba(test_features)[:, 1]
        fit_report[label] = {
            "trained": True,
            "known_train": int(known.sum()),
            "positive_train": int(target.sum()),
        }
    return validation_scores, test_scores, fit_report


def evaluate_baselines(dataset: FrozenDataset) -> dict[str, Any]:
    train_records = dataset.split("train")
    validation_records = dataset.split("validation")
    test_records = dataset.split("test")
    y_validation, validation_mask = dataset.label_arrays(validation_records)
    y_test, test_mask = dataset.label_arrays(test_records)

    rule = rule_scores(test_records, dataset.label_order)
    rule_thresholds = {label: 0.5 for label in dataset.label_order}
    rule_metrics = multilabel_metrics(
        y_test, rule, test_mask, dataset.label_order, rule_thresholds
    )

    validation_scores, test_scores, fit_report = tfidf_scores(
        train_records, validation_records, test_records, dataset
    )
    tfidf_thresholds, tuning_support = tune_thresholds(
        y_validation,
        validation_scores,
        validation_mask,
        dataset.label_order,
    )
    tfidf_metrics = multilabel_metrics(
        y_test, test_scores, test_mask, dataset.label_order, tfidf_thresholds
    )
    return {
        "dataset_manifest_sha256": dataset.manifest.manifest_sha256,
        "split": "test",
        "rule": {
            "name": "production_rule_fallback",
            "thresholds": rule_thresholds,
            "metrics": rule_metrics,
        },
        "tfidf_logistic": {
            "name": "train_only_char_tfidf_logistic_regression",
            "thresholds_source": "validation",
            "thresholds": tfidf_thresholds,
            "validation_support": tuning_support,
            "fit_report": fit_report,
            "metrics": tfidf_metrics,
        },
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-dir", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args(argv)
    dataset = load_frozen_dataset(args.dataset_dir)
    result = evaluate_baselines(dataset)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
