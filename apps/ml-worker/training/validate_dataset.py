"""Quality gates for immutable dataset artifacts."""

from __future__ import annotations

import hashlib
import json
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from app.secret_redaction import redact_secrets
from app.taxonomy import load_taxonomy
from training.dataset_contract import ALLOWED_DATA_USE, read_records
from training.dataset_manifest import file_sha256, read_manifest
from training.group_split import duplicate_components


@dataclass(frozen=True)
class Finding:
    severity: str
    code: str
    message: str
    sample_ids: tuple[str, ...] = ()

    def to_dict(self) -> dict[str, Any]:
        return {
            "severity": self.severity,
            "code": self.code,
            "message": self.message,
            "sample_ids": list(self.sample_ids),
        }


def _load_splits(path: Path) -> dict[str, Any]:
    if not path.is_file():
        raise FileNotFoundError(f"splits artifact not found: {path}")
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict) or raw.get("schema_version") != 1:
        raise ValueError("splits.json must be a schema_version=1 object")
    if not isinstance(raw.get("samples"), dict) or not isinstance(raw.get("components"), dict):
        raise ValueError("splits.json requires samples and components mappings")
    return raw


def validate_dataset_dir(dataset_dir: Path) -> dict[str, Any]:
    findings: list[Finding] = []
    manifest_path = dataset_dir / "manifest.json"
    samples_path = dataset_dir / "samples.jsonl"
    splits_path = dataset_dir / "splits.json"

    try:
        manifest = read_manifest(manifest_path)
    except (FileNotFoundError, ValueError, json.JSONDecodeError) as exc:
        return _report([Finding("critical", "invalid_manifest", str(exc))], {})
    try:
        records = read_records(samples_path)
    except (FileNotFoundError, ValueError) as exc:
        return _report([Finding("critical", "invalid_samples", str(exc))], {})
    try:
        splits = _load_splits(splits_path)
    except (FileNotFoundError, ValueError, json.JSONDecodeError) as exc:
        return _report([Finding("critical", "invalid_splits", str(exc))], {})

    taxonomy = load_taxonomy()
    trainable = set(taxonomy.trainable_ids())
    if manifest.taxonomy_version != taxonomy.version:
        findings.append(Finding("critical", "taxonomy_version_mismatch", "manifest taxonomy does not match canonical taxonomy"))
    if file_sha256(samples_path) != manifest.samples_sha256:
        findings.append(Finding("critical", "samples_hash_mismatch", "samples.jsonl hash does not match manifest"))
    if file_sha256(splits_path) != manifest.splits_sha256:
        findings.append(Finding("critical", "splits_hash_mismatch", "splits.json hash does not match manifest"))

    seen_ids: set[str] = set()
    group_splits: dict[str, set[str]] = defaultdict(set)
    label_distribution: Counter[str] = Counter()
    negative_label_distribution: Counter[str] = Counter()
    language_distribution: Counter[str] = Counter()
    repository_ids: set[str] = set()
    sample_mapping = {str(key): str(value) for key, value in splits["samples"].items()}

    if set(sample_mapping) != {record.sample_id for record in records}:
        findings.append(
            Finding(
                "critical",
                "split_sample_set_mismatch",
                "splits.json sample IDs do not exactly match samples.jsonl",
            )
        )

    for record in records:
        if record.sample_id in seen_ids:
            findings.append(Finding("critical", "duplicate_sample_id", "sample ID appears more than once", (record.sample_id,)))
        seen_ids.add(record.sample_id)
        repository_ids.add(record.repository_id)
        language_distribution[record.language] += 1

        mapped_split = sample_mapping.get(record.sample_id)
        if mapped_split != record.split:
            findings.append(Finding("critical", "split_mapping_mismatch", f"record split {record.split!r} != splits.json {mapped_split!r}", (record.sample_id,)))
        group_splits[record.group_key].add(record.split)
        if record.data_use_status not in ALLOWED_DATA_USE:
                findings.append(Finding("critical", "blocked_data_use", f"disallowed data-use status {record.data_use_status}", (record.sample_id,)))
        if record.taxonomy_version != taxonomy.version:
            findings.append(Finding("critical", "record_taxonomy_mismatch", "record taxonomy does not match canonical taxonomy", (record.sample_id,)))
        if redact_secrets(record.raw_hunk).redaction_count or redact_secrets(record.added_code).redaction_count:
            findings.append(Finding("critical", "secret_escaped_redaction", "likely secret remains in dataset text", (record.sample_id,)))
        expected_content_hash = hashlib.sha256(record.added_code.encode("utf-8")).hexdigest()
        if record.content_sha256 != expected_content_hash:
            findings.append(Finding("critical", "content_hash_mismatch", "content_sha256 is not SHA-256 of added_code", (record.sample_id,)))

        label_ids: set[str] = set()
        for evidence in record.labels:
            if evidence.anti_pattern_id in label_ids:
                findings.append(Finding("critical", "duplicate_label_state", f"label {evidence.anti_pattern_id} appears more than once", (record.sample_id,)))
            label_ids.add(evidence.anti_pattern_id)
            if evidence.anti_pattern_id not in trainable:
                findings.append(Finding("critical", "unknown_or_non_trainable_label", f"invalid model label {evidence.anti_pattern_id}", (record.sample_id,)))
            if evidence.trust_level in {"fallback", "model"}:
                findings.append(Finding("critical", "automated_gold_label", "model/fallback evidence cannot become gold", (record.sample_id,)))
            if evidence.state == "positive":
                label_distribution[evidence.anti_pattern_id] += 1
            else:
                negative_label_distribution[evidence.anti_pattern_id] += 1

    for group_key, assigned in group_splits.items():
        if len(assigned) > 1:
            findings.append(Finding("critical", "group_split_leakage", f"group {group_key!r} crosses splits {sorted(assigned)}"))

    _, duplicate_pairs = duplicate_components(
        records, near_threshold=manifest.near_duplicate_threshold
    )
    for pair in duplicate_pairs:
        left_split = sample_mapping.get(pair.left_sample_id)
        right_split = sample_mapping.get(pair.right_sample_id)
        severity = "critical" if left_split != right_split else "warning"
        code = f"{pair.kind}_duplicate_cross_split" if severity == "critical" else f"{pair.kind}_duplicate_same_split"
        findings.append(Finding(severity, code, f"{pair.kind} duplicate similarity={pair.similarity:.3f}", (pair.left_sample_id, pair.right_sample_id)))

    actual_split_counts = Counter(record.split for record in records)
    expected_split_counts = {name: actual_split_counts.get(name, 0) for name in ("train", "validation", "test")}
    for split, count in expected_split_counts.items():
        if count == 0:
            findings.append(
                Finding("critical", "empty_split", f"{split} split has no samples")
            )
    for label in sorted(trainable):
        if label_distribution[label] == 0:
            findings.append(
                Finding(
                    "critical",
                    "missing_positive_label_support",
                    f"{label} has no explicit positive evidence",
                )
            )
        if negative_label_distribution[label] == 0:
            findings.append(
                Finding(
                    "critical",
                    "missing_negative_label_support",
                    f"{label} has no explicit negative evidence",
                )
            )
    comparisons: list[tuple[bool, str, str]] = [
        (manifest.sample_count == len(records), "sample_count_mismatch", "manifest sample_count does not match samples.jsonl"),
        (manifest.repository_count == len(repository_ids), "repository_count_mismatch", "manifest repository_count does not match records"),
        (manifest.split_counts == expected_split_counts, "split_count_mismatch", "manifest split_counts do not match records"),
        (manifest.label_distribution == dict(sorted(label_distribution.items())), "label_distribution_mismatch", "manifest label_distribution does not match records"),
        (manifest.negative_label_distribution == dict(sorted(negative_label_distribution.items())), "negative_label_distribution_mismatch", "manifest negative_label_distribution does not match records"),
        (manifest.language_distribution == dict(sorted(language_distribution.items())), "language_distribution_mismatch", "manifest language_distribution does not match records"),
        (sorted(manifest.redaction_versions) == sorted({record.redaction_version for record in records}), "redaction_versions_mismatch", "manifest redaction_versions do not match records"),
    ]
    for valid, code, message in comparisons:
        if not valid:
            findings.append(Finding("critical", code, message))

    statistics = {
        "sample_count": len(records),
        "repository_count": len(repository_ids),
        "split_counts": expected_split_counts,
        "label_distribution": dict(sorted(label_distribution.items())),
        "negative_label_distribution": dict(sorted(negative_label_distribution.items())),
        "language_distribution": dict(sorted(language_distribution.items())),
        "exact_duplicate_pairs": sum(pair.kind == "exact" for pair in duplicate_pairs),
        "near_duplicate_pairs": sum(pair.kind == "near" for pair in duplicate_pairs),
        "synthetic": manifest.synthetic,
        "frozen": manifest.frozen,
    }
    if manifest.synthetic:
        findings.append(Finding("warning", "synthetic_smoke_dataset", "synthetic data is for lifecycle smoke tests, not performance claims"))
    return _report(findings, statistics)


def _report(findings: list[Finding], statistics: dict[str, Any]) -> dict[str, Any]:
    critical = sum(item.severity == "critical" for item in findings)
    warnings = sum(item.severity == "warning" for item in findings)
    return {
        "critical_failures": critical,
        "warnings": warnings,
        "statistics": statistics,
        "findings": [item.to_dict() for item in findings],
    }


def write_quality_report(dataset_dir: Path, report: dict[str, Any]) -> Path:
    path = dataset_dir / "data_quality_report.json"
    path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return path
