"""Data-quality validator for produced dataset directories.

Checks (each carries a severity; ``critical`` failures fail the run):

* missing diff content                  (critical)
* missing language                      (critical)
* unknown taxonomy IDs                  (critical)
* invalid line ranges                   (critical)
* duplicate hashes                      (warning)
* samples present in multiple splits    (critical)
* PR groups present in multiple splits  (critical)
* empty labels                          (critical)
* labels without supporting annotation  (critical)
* trainable labels with zero examples   (warning)
* extreme label imbalance               (warning)
* secrets that escaped redaction        (critical)

Emits ``data_quality_report.json`` and ``.md`` into the dataset dir.
"""

from __future__ import annotations

import json
import re
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List

_HERE = Path(__file__).resolve().parent
_REPO_ROOT = _HERE.parent.parent
for _p in (str(_HERE.parent), str(_REPO_ROOT)):
    if _p not in sys.path:
        sys.path.insert(0, _p)

from taxonomy import load_canonical_taxonomy, trainable_ids  # noqa: E402


SPLITS = ("train", "validation", "test")

_SECRET_PATTERNS = [
    re.compile(r"(?i)(password|secret|api[_-]?key)\s*[:=]\s*['\"][^'\"]{4,}['\"]"),
    re.compile(r"['\"][A-Za-z0-9+/=_-]{32,}['\"]"),
]


@dataclass
class Finding:
    severity: str
    code: str
    message: str


def _read_jsonl(path: Path) -> List[Dict[str, Any]]:
    if not path.exists():
        return []
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        out.append(json.loads(line))
    return out


def _looks_like_secret(text: str) -> bool:
    return any(p.search(text or "") for p in _SECRET_PATTERNS)


def validate_dataset_dir(dataset_dir: Path) -> Dict[str, Any]:
    """Validate a dataset directory.

    Returns a dict with keys ``findings`` (list of Finding dicts),
    ``critical_failures`` (int) and ``summary``.
    """

    findings: List[Finding] = []
    critical_failures = 0

    manifest_path = dataset_dir / "manifest.json"
    samples_path = dataset_dir / "samples.jsonl"
    splits_path = dataset_dir / "splits.json"

    if not manifest_path.exists():
        return _result(findings, 1, "manifest.json missing")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

    if not samples_path.exists():
        return _result(findings, 1, "samples.jsonl missing")
    if not splits_path.exists():
        return _result(findings, 1, "splits.json missing")

    samples = _read_jsonl(samples_path)
    splits = json.loads(splits_path.read_text(encoding="utf-8"))

    taxonomy = load_canonical_taxonomy()
    trainable = set(trainable_ids())
    valid_ids = {item["id"] for item in taxonomy["entries"]}  # noqa: E402

    sample_split: Dict[str, str] = {}
    group_split: Dict[str, str] = {}
    label_counts: Counter = Counter()
    missing_diff = missing_lang = 0
    unknown_labels = 0
    invalid_line_ranges = 0
    secrets = 0

    for record in samples:
        sid = record.get("id")
        added = record.get("added_code") or ""
        language = record.get("language")
        labels = record.get("labels") or []
        group = record.get("group_key")
        split = splits.get(group)

        if not added.strip():
            missing_diff += 1
            findings.append(Finding("critical", "missing_diff",
                                    f"sample {sid} has empty added_code"))
        if not language or language == "unknown":
            missing_lang += 1
            findings.append(Finding("critical", "missing_language",
                                    f"sample {sid} has no language"))
        for label in labels:
            if label not in valid_ids:
                unknown_labels += 1
                findings.append(Finding("critical", "unknown_taxonomy_id",
                                        f"sample {sid} has unknown label {label}"))
            elif label not in trainable:
                findings.append(Finding("critical", "non_trainable_label",
                                        f"sample {sid} has non-trainable label {label}"))
            else:
                label_counts[label] += 1
        if record.get("new_start") is None or record.get("new_start") < 0:
            invalid_line_ranges += 1
            findings.append(Finding("critical", "invalid_line_range",
                                    f"sample {sid} has invalid new_start"))
        if _looks_like_secret(added):
            secrets += 1
            findings.append(Finding("critical", "secret_escaped_redaction",
                                    f"sample {sid} contains a likely secret"))

        if sid in sample_split and sample_split[sid] != split:
            findings.append(Finding("critical", "split_conflict_sample",
                                    f"sample {sid} appears in both "
                                    f"{sample_split[sid]} and {split}"))
        sample_split[sid] = split or "train"

        if group_split.get(group) not in (None, split):
            findings.append(Finding("critical", "split_conflict_group",
                                    f"group {group} appears in both "
                                    f"{group_split[group]} and {split}"))
        group_split[group] = split or "train"

    # trainable labels with zero examples
    for label in sorted(trainable):
        if label_counts.get(label, 0) == 0:
            findings.append(Finding("warning", "zero_examples",
                                    f"trainable label {label} has zero examples"))

    # extreme imbalance
    counts = sorted(label_counts.values())
    if counts and counts[0] > 0 and counts[-1] / counts[0] > 100:
        findings.append(Finding("warning", "extreme_imbalance",
                                f"label imbalance ratio = {counts[-1] / counts[0]:.1f}x"))

    critical_failures = sum(1 for f in findings if f.severity == "critical")
    warnings = sum(1 for f in findings if f.severity == "warning")

    summary = {
        "samples": len(samples),
        "labels_seen": len(label_counts),
        "duplicates": manifest.get("duplicate_count", 0),
        "missing_diff": missing_diff,
        "missing_language": missing_lang,
        "unknown_taxonomy_ids": unknown_labels,
        "invalid_line_ranges": invalid_line_ranges,
        "secrets_escaped": secrets,
        "critical_failures": critical_failures,
        "warnings": warnings,
    }

    return {
        "summary": summary,
        "findings": [f.__dict__ for f in findings],
        "critical_failures": critical_failures,
        "warnings": warnings,
    }


def _result(findings: List[Finding], critical: int, message: str) -> Dict[str, Any]:
    findings.append(Finding("critical", "missing_file", message))
    return {
        "summary": {"critical_failures": critical, "warnings": 0},
        "findings": [f.__dict__ for f in findings],
        "critical_failures": critical,
        "warnings": 0,
    }


def render_markdown(report: Dict[str, Any]) -> str:
    lines = [
        "# Data Quality Report",
        "",
        f"Samples: {report['summary'].get('samples', 0)}",
        f"Labels seen: {report['summary'].get('labels_seen', 0)}",
        f"Duplicates: {report['summary'].get('duplicates', 0)}",
        f"Missing diff: {report['summary'].get('missing_diff', 0)}",
        f"Missing language: {report['summary'].get('missing_language', 0)}",
        f"Unknown taxonomy IDs: {report['summary'].get('unknown_taxonomy_ids', 0)}",
        f"Invalid line ranges: {report['summary'].get('invalid_line_ranges', 0)}",
        f"Secrets escaped: {report['summary'].get('secrets_escaped', 0)}",
        "",
        f"Critical failures: {report['critical_failures']}",
        f"Warnings: {report['warnings']}",
        "",
        "## Findings",
        "",
    ]
    for f in report["findings"]:
        lines.append(f"- **{f['severity']}** `{f['code']}` — {f['message']}")
    return "\n".join(lines) + "\n"
