"""PR-level benchmark for the code-review engines.

Each fixture is a PR-shaped unified diff (``<case>.diff``) with a reviewed
ground truth (``<case>.yaml``) listing expected findings, defects outside the
taxonomy (``antiPattern: unsupported``) and explicit negatives (decoys that a
precise reviewer must not flag). See ``docs/review-benchmark.md``.

Usage (from the repository root, with the ML worker's dependencies installed)::

    python benchmarks/run_benchmark.py                       # dev + heldout, rules (+ model note)
    python benchmarks/run_benchmark.py --split dev
    python benchmarks/run_benchmark.py --worker-url http://localhost:8000  # also a running worker

Outputs ``<split>-<engine>.json`` and ``.md`` under ``benchmarks/results``.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import platform
import sys
import time
import tracemalloc
import urllib.request
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Iterable, Sequence

import yaml

BENCH_ROOT = Path(__file__).resolve().parent
REPO_ROOT = BENCH_ROOT.parent
WORKER_ROOT = REPO_ROOT / "apps" / "ml-worker"
FIXTURES = BENCH_ROOT / "fixtures"
RESULTS = BENCH_ROOT / "results"
SPLITS = ("dev", "heldout")
UNSUPPORTED = "unsupported"
ANY_PATTERN = "*"
SEVERITIES = ("critical", "major", "minor")
DEFAULT_TOLERANCE = 3
DEFAULT_REPEAT = 5
GUIDANCE_FIELDS = ("explanation", "evidence", "remediation", "caveat")

if str(WORKER_ROOT) not in sys.path:
    sys.path.insert(0, str(WORKER_ROOT))


# ----------------------------------------------------------------------
# Fixtures
# ----------------------------------------------------------------------
@dataclass(frozen=True)
class Expected:
    anti_pattern: str
    file: str
    start: int
    end: int
    severity: str
    defect: str | None
    note: str


@dataclass(frozen=True)
class Negative:
    anti_pattern: str
    file: str
    start: int
    end: int
    reason: str


@dataclass(frozen=True)
class FileChange:
    path: str
    binary: bool
    deleted: bool
    added: frozenset[int]
    hunks: tuple[tuple[int, int], ...]  # inclusive new-file line ranges


@dataclass(frozen=True)
class Case:
    id: str
    split: str
    title: str
    diff: str
    expected: tuple[Expected, ...]
    negatives: tuple[Negative, ...]
    files: dict[str, FileChange]

    @property
    def supported(self) -> tuple[Expected, ...]:
        return tuple(e for e in self.expected if e.anti_pattern != UNSUPPORTED)

    @property
    def unsupported(self) -> tuple[Expected, ...]:
        return tuple(e for e in self.expected if e.anti_pattern == UNSUPPORTED)


@dataclass(frozen=True)
class Prediction:
    anti_pattern: str
    file: str
    start: int
    end: int
    severity: str
    guidance_complete: bool


def _unquote_path(raw: str) -> str:
    """Decode a git C-quoted path (``"b/caf\\303\\251.py"``)."""
    raw = raw.strip()
    if not (raw.startswith('"') and raw.endswith('"') and len(raw) >= 2):
        return raw
    body, out, i = raw[1:-1], bytearray(), 0
    simple = {"n": b"\n", "t": b"\t", '"': b'"', "\\": b"\\", "a": b"\a", "b": b"\b",
              "f": b"\f", "r": b"\r", "v": b"\v"}
    while i < len(body):
        char = body[i]
        if char == "\\" and i + 1 < len(body):
            nxt = body[i + 1]
            if nxt in "01234567" and i + 3 < len(body) + 1:
                out.append(int(body[i + 1 : i + 4], 8))
                i += 4
                continue
            out += simple.get(nxt, nxt.encode("utf-8"))
            i += 2
            continue
        out += char.encode("utf-8")
        i += 1
    return out.decode("utf-8", errors="replace")


def _strip_prefix(path: str) -> str:
    path = _unquote_path(path.split("\t", 1)[0])
    return path[2:] if path.startswith(("a/", "b/")) else path


def diff_files(diff: str) -> dict[str, FileChange]:
    """Independent diff walk used to validate ground truth and count files.

    Deliberately separate from the worker's parser so a parser defect cannot
    hide itself by also corrupting the reference.
    """
    files: dict[str, dict[str, Any]] = {}
    lines = diff.replace("\r\n", "\n").split("\n")
    current: dict[str, Any] | None = None
    i = 0

    def start(path: str) -> dict[str, Any]:
        entry = files.setdefault(
            path, {"binary": False, "deleted": False, "added": set(), "hunks": []}
        )
        return entry

    while i < len(lines):
        line = lines[i]
        i += 1
        if line.startswith("diff --git "):
            rest = line[len("diff --git ") :]
            if rest.startswith('"'):
                close = rest.find('" ', 1)
                new = rest[close + 2 :] if close > 0 else rest
            else:
                marker = rest.find(" b/")
                new = rest[marker + 1 :] if marker >= 0 else rest
            current = start(_strip_prefix(new))
            continue
        if current is None:
            if line.startswith("+++ "):
                current = start(_strip_prefix(line[4:]))
            continue
        if line.startswith("Binary files"):
            current["binary"] = True
            continue
        if line.startswith("deleted file mode"):
            current["deleted"] = True
            continue
        if line.startswith("+++ "):
            continue
        if not line.startswith("@@ "):
            continue
        header = line.split("@@")[1].split()
        new_part = header[1][1:].split(",")
        new_line = int(new_part[0])
        new_count = int(new_part[1]) if len(new_part) > 1 else 1
        old_part = header[0][1:].split(",")
        old_left = int(old_part[1]) if len(old_part) > 1 else 1
        new_left = new_count
        if new_count:
            current["hunks"].append((new_line, new_line + new_count - 1))
        while i < len(lines) and (old_left > 0 or new_left > 0):
            body = lines[i]
            i += 1
            if body.startswith("+"):
                current["added"].add(new_line)
                new_line += 1
                new_left -= 1
            elif body.startswith("-"):
                old_left -= 1
            elif body.startswith("\\"):
                continue
            else:
                new_line += 1
                new_left -= 1
                old_left -= 1
    return {
        path: FileChange(
            path=path,
            binary=entry["binary"],
            deleted=entry["deleted"],
            added=frozenset(entry["added"]),
            hunks=tuple(entry["hunks"]),
        )
        for path, entry in files.items()
    }


def _range(raw: Any, where: str) -> tuple[int, int]:
    if (
        not isinstance(raw, list)
        or len(raw) != 2
        or not all(isinstance(v, int) and v >= 1 for v in raw)
        or raw[0] > raw[1]
    ):
        raise ValueError(f"{where}: lines must be [start, end] with 1 <= start <= end")
    return raw[0], raw[1]


def _taxonomy_ids() -> dict[str, str]:
    from app.taxonomy import load_taxonomy

    return {entry.id: entry.category for entry in load_taxonomy().entries}


def load_case(diff_path: Path, split: str, taxonomy: dict[str, str]) -> Case:
    truth_path = diff_path.with_suffix(".yaml")
    truth = yaml.safe_load(truth_path.read_text(encoding="utf-8"))
    diff = diff_path.read_text(encoding="utf-8")
    files = diff_files(diff)
    case_id = truth["id"]
    if case_id != diff_path.stem:
        raise ValueError(f"{truth_path}: id {case_id!r} does not match file name")

    expected: list[Expected] = []
    for index, row in enumerate(truth.get("expected") or []):
        where = f"{case_id} expected[{index}]"
        anti_pattern = row["antiPattern"]
        if anti_pattern != UNSUPPORTED and anti_pattern not in taxonomy:
            raise ValueError(f"{where}: unknown antiPattern {anti_pattern!r}")
        if anti_pattern == UNSUPPORTED and not row.get("defect"):
            raise ValueError(f"{where}: unsupported defects need a 'defect' class")
        if row["severity"] not in SEVERITIES:
            raise ValueError(f"{where}: invalid severity {row['severity']!r}")
        start, end = _range(row["lines"], where)
        change = files.get(row["file"])
        if change is None:
            raise ValueError(f"{where}: file {row['file']!r} is not in the diff")
        if not any(start <= line <= end for line in change.added):
            raise ValueError(f"{where}: lines {start}-{end} contain no added line")
        expected.append(
            Expected(anti_pattern, row["file"], start, end, row["severity"],
                     row.get("defect"), row.get("note", ""))
        )

    negatives: list[Negative] = []
    for index, row in enumerate(truth.get("negatives") or []):
        where = f"{case_id} negatives[{index}]"
        anti_pattern = row["antiPattern"]
        if anti_pattern != ANY_PATTERN and anti_pattern not in taxonomy:
            raise ValueError(f"{where}: unknown antiPattern {anti_pattern!r}")
        if row["file"] not in files:
            raise ValueError(f"{where}: file {row['file']!r} is not in the diff")
        if not row.get("reason"):
            raise ValueError(f"{where}: negatives need a reason")
        start, end = _range(row["lines"], where)
        negatives.append(Negative(anti_pattern, row["file"], start, end, row["reason"]))

    return Case(case_id, split, truth.get("title", ""), diff, tuple(expected), tuple(negatives), files)


def load_cases(split: str) -> list[Case]:
    taxonomy = _taxonomy_ids()
    directory = FIXTURES / split
    cases = [load_case(path, split, taxonomy) for path in sorted(directory.glob("*.diff"))]
    if not cases:
        raise ValueError(f"no fixtures under {directory}")
    return cases


# ----------------------------------------------------------------------
# Scoring
# ----------------------------------------------------------------------
def _overlaps(a_start: int, a_end: int, b_start: int, b_end: int) -> bool:
    return a_start <= b_end and b_start <= a_end


def _gap(a_start: int, a_end: int, b_start: int, b_end: int) -> int:
    if _overlaps(a_start, a_end, b_start, b_end):
        return 0
    return b_start - a_end if a_end < b_start else a_start - b_end


def _same_hunk(change: FileChange | None, a: tuple[int, int], b: tuple[int, int]) -> bool:
    if change is None:
        return False
    return any(
        _overlaps(*a, lo, hi) and _overlaps(*b, lo, hi) for lo, hi in change.hunks
    )


@dataclass
class CaseScore:
    case_id: str
    matches: list[tuple[Prediction, Expected]]
    false_positives: list[Prediction]
    false_negatives: list[Expected]
    decoy_false_positives: list[tuple[Prediction, Negative]]
    near_misses: int
    mislabels: list[tuple[str, str]]  # (expected antiPattern, predicted antiPattern)
    unsupported_overlaps: int


def score_case(case: Case, predictions: Sequence[Prediction], tolerance: int) -> CaseScore:
    """Match predictions to supported expected findings, one-to-one.

    A prediction matches when antiPattern and file are equal and its line
    range overlaps the expected range widened by ``tolerance`` lines. Pairs
    are assigned greedily, closest first, so each prediction and each
    expected finding is used at most once.
    """
    pairs: list[tuple[int, int, int]] = []
    for p_index, prediction in enumerate(predictions):
        for e_index, expected in enumerate(case.supported):
            if prediction.anti_pattern != expected.anti_pattern or prediction.file != expected.file:
                continue
            if not _overlaps(prediction.start, prediction.end,
                             expected.start - tolerance, expected.end + tolerance):
                continue
            gap = _gap(prediction.start, prediction.end, expected.start, expected.end)
            pairs.append((gap, e_index, p_index))
    pairs.sort()
    used_p: set[int] = set()
    used_e: set[int] = set()
    matches: list[tuple[Prediction, Expected]] = []
    for _, e_index, p_index in pairs:
        if p_index in used_p or e_index in used_e:
            continue
        used_p.add(p_index)
        used_e.add(e_index)
        matches.append((predictions[p_index], case.supported[e_index]))

    false_positives = [p for i, p in enumerate(predictions) if i not in used_p]
    false_negatives = [e for i, e in enumerate(case.supported) if i not in used_e]

    decoys: list[tuple[Prediction, Negative]] = []
    for prediction in false_positives:
        for negative in case.negatives:
            if (
                negative.file == prediction.file
                and negative.anti_pattern in (ANY_PATTERN, prediction.anti_pattern)
                and _overlaps(prediction.start, prediction.end, negative.start, negative.end)
            ):
                decoys.append((prediction, negative))
                break

    near_misses = 0
    for prediction in false_positives:
        if any(
            e.anti_pattern == prediction.anti_pattern
            and e.file == prediction.file
            and _same_hunk(case.files.get(e.file), (prediction.start, prediction.end), (e.start, e.end))
            for e in false_negatives
        ):
            near_misses += 1

    mislabels: list[tuple[str, str]] = []
    for prediction in false_positives:
        for expected in case.expected:
            if (
                expected.file == prediction.file
                and expected.anti_pattern != prediction.anti_pattern
                and _overlaps(prediction.start, prediction.end, expected.start, expected.end)
            ):
                mislabels.append((expected.anti_pattern if expected.anti_pattern != UNSUPPORTED
                                  else f"unsupported:{expected.defect}", prediction.anti_pattern))
                break

    unsupported_overlaps = sum(
        1
        for prediction in false_positives
        if any(
            u.file == prediction.file and _overlaps(prediction.start, prediction.end, u.start, u.end)
            for u in case.unsupported
        )
    )
    return CaseScore(case.id, matches, false_positives, false_negatives, decoys,
                     near_misses, mislabels, unsupported_overlaps)


def _ratio(numerator: int, denominator: int) -> float | None:
    return round(numerator / denominator, 4) if denominator else None


def _prf(tp: int, fp: int, fn: int) -> dict[str, Any]:
    # F1 = 2TP / (2TP + FP + FN): the harmonic mean of precision and recall,
    # and 0.0 (not undefined) when nothing was matched but something was wrong.
    return {
        "tp": tp,
        "fp": fp,
        "fn": fn,
        "precision": _ratio(tp, tp + fp),
        "recall": _ratio(tp, tp + fn),
        "f1": _ratio(2 * tp, 2 * tp + fp + fn),
    }


def percentile(values: Sequence[float], q: float) -> float | None:
    """Nearest-rank percentile (q in (0, 100])."""
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, math.ceil(q / 100 * len(ordered)))
    return round(ordered[rank - 1], 3)


def aggregate(
    cases: Sequence[Case],
    scores: Sequence[CaseScore],
    predictions: dict[str, Sequence[Prediction]],
    taxonomy: dict[str, str],
    analyzed_files: dict[str, set[str]] | None,
) -> dict[str, Any]:
    per_pattern: dict[str, Counter[str]] = defaultdict(Counter)
    for score in scores:
        for prediction, _ in score.matches:
            per_pattern[prediction.anti_pattern]["tp"] += 1
        for prediction in score.false_positives:
            per_pattern[prediction.anti_pattern]["fp"] += 1
        for expected in score.false_negatives:
            per_pattern[expected.anti_pattern]["fn"] += 1

    by_pattern = {
        pattern: _prf(counts["tp"], counts["fp"], counts["fn"])
        for pattern, counts in sorted(per_pattern.items())
    }
    per_category: dict[str, Counter[str]] = defaultdict(Counter)
    for pattern, counts in per_pattern.items():
        per_category[taxonomy[pattern]].update(counts)
    by_category = {
        category: _prf(counts["tp"], counts["fp"], counts["fn"])
        for category, counts in sorted(per_category.items())
    }

    tp = sum(len(s.matches) for s in scores)
    fp = sum(len(s.false_positives) for s in scores)
    fn = sum(len(s.false_negatives) for s in scores)
    exact_lines = sum(
        1 for s in scores for p, e in s.matches if _overlaps(p.start, p.end, e.start, e.end)
    )
    same_hunk = sum(
        1
        for s, case in zip(scores, cases)
        for p, e in s.matches
        if _same_hunk(case.files.get(e.file), (p.start, p.end), (e.start, e.end))
    )
    near_misses = sum(s.near_misses for s in scores)
    severity_correct = sum(1 for s in scores for p, e in s.matches if p.severity == e.severity)
    severity_confusion: dict[str, Counter[str]] = defaultdict(Counter)
    for s in scores:
        for p, e in s.matches:
            severity_confusion[e.severity][p.severity] += 1

    unsupported = [e for case in cases for e in case.unsupported]
    supported_total = sum(len(case.supported) for case in cases)
    all_predictions = [p for case in cases for p in predictions[case.id]]

    changed = [change for case in cases for change in case.files.values()]
    file_report: dict[str, Any] = {"changed_files": len(changed)}
    if analyzed_files is not None:
        analyzed = sum(
            1 for case in cases for path in case.files if path in analyzed_files[case.id]
        )
        reasons: Counter[str] = Counter()
        for case in cases:
            for path, change in case.files.items():
                if path in analyzed_files[case.id]:
                    continue
                if change.binary:
                    reasons["binary"] += 1
                elif not change.added:
                    reasons["no_added_lines"] += 1
                else:
                    reasons["language_not_reviewed"] += 1
        file_report.update(
            analyzed_files=analyzed,
            unsupported_files=len(changed) - analyzed,
            unsupported_file_rate=_ratio(len(changed) - analyzed, len(changed)),
            unsupported_by_reason=dict(sorted(reasons.items())),
        )
    else:
        file_report["note"] = "Analyzed files are not observable through the HTTP API."

    mislabel_matrix: dict[str, Counter[str]] = defaultdict(Counter)
    for s in scores:
        for expected_label, predicted_label in s.mislabels:
            mislabel_matrix[expected_label][predicted_label] += 1

    return {
        "overall": {
            **_prf(tp, fp, fn),
            "supported_expected": supported_total,
            "predictions": len(all_predictions),
        },
        "by_anti_pattern": by_pattern,
        "by_category": by_category,
        "label_confusion": {k: dict(sorted(v.items())) for k, v in sorted(mislabel_matrix.items())},
        "pr_level": {
            "prs": len(cases),
            "false_positives_per_pr": _ratio(fp, len(cases)),
            "prs_with_false_positives": sum(1 for s in scores if s.false_positives),
            "prs_with_supported_defects": sum(1 for c in cases if c.supported),
            "prs_fully_detected": sum(
                1 for s, c in zip(scores, cases) if c.supported and not s.false_negatives
            ),
            "prs_clean": sum(
                1 for s in scores if not s.false_positives and not s.false_negatives
            ),
        },
        "decoys": {
            "negatives": sum(len(c.negatives) for c in cases),
            "false_positives_on_decoys": sum(len(s.decoy_false_positives) for s in scores),
        },
        "severity": {
            "matched": tp,
            "correct": severity_correct,
            "accuracy": _ratio(severity_correct, tp),
            "confusion_expected_to_predicted": {
                k: dict(sorted(v.items())) for k, v in sorted(severity_confusion.items())
            },
        },
        "localization": {
            "tolerance_lines": None,  # filled by caller
            "line_exact": exact_lines,
            "line_accuracy": _ratio(exact_lines, tp),
            "same_hunk": same_hunk,
            "hunk_accuracy": _ratio(same_hunk, tp),
            "near_misses_same_hunk": near_misses,
        },
        "unsupported_defects": {
            "count": len(unsupported),
            "by_defect": dict(sorted(Counter(e.defect or "" for e in unsupported).items())),
            "predictions_overlapping": sum(s.unsupported_overlaps for s in scores),
            "coverage_of_all_labeled_defects": _ratio(tp, supported_total + len(unsupported)),
            "note": "Never counted as detected; listed so recall is not overstated.",
        },
        "files": file_report,
        "explainability": {
            "predictions": len(all_predictions),
            "complete": sum(1 for p in all_predictions if p.guidance_complete),
            "rate": _ratio(sum(1 for p in all_predictions if p.guidance_complete), len(all_predictions)),
            "fields": list(GUIDANCE_FIELDS),
        },
    }


# ----------------------------------------------------------------------
# Engines
# ----------------------------------------------------------------------
Runner = Callable[[str], list[Prediction]]


def _guidance_complete(finding: dict[str, Any], require_evidence: bool) -> bool:
    fields = GUIDANCE_FIELDS if require_evidence else tuple(f for f in GUIDANCE_FIELDS if f != "evidence")
    return all(isinstance(finding.get(f), str) and finding[f].strip() for f in fields)


def _to_predictions(findings: Iterable[dict[str, Any]], require_evidence: bool) -> list[Prediction]:
    predictions = []
    for finding in findings:
        start = finding.get("lineStart") or 1
        end = finding.get("lineEnd") or start
        predictions.append(
            Prediction(
                anti_pattern=finding["antiPattern"],
                file=finding.get("filePath") or "",
                start=int(start),
                end=int(end),
                severity=finding["severity"],
                guidance_complete=_guidance_complete(finding, require_evidence),
            )
        )
    return predictions


def fallback_runner() -> tuple[Runner, Callable[[str], set[str]]]:
    from app.fallback_scanner import fallback_findings, reviewable_hunks

    def run(diff: str) -> list[Prediction]:
        findings, _, _ = fallback_findings(diff)
        return _to_predictions((f.model_dump() for f in findings), require_evidence=True)

    def analyzed(diff: str) -> set[str]:
        return {hunk.file_path for hunk in reviewable_hunks(diff)}

    return run, analyzed


def model_runner() -> tuple[Runner | None, str]:
    """Return a runner for a configured, promoted checkpoint, or a skip note."""
    from app.config import settings

    model_name = (os.environ.get("MODEL_NAME") or settings.MODEL_NAME or "").strip()
    if model_name.lower() in {"", "none"}:
        return None, (
            "Skipped: no checkpoint configured (MODEL_NAME=none). The repository ships no "
            "trained or promoted model, so no model metrics are reported."
        )
    from app.main import _predict_hunks
    from app.model import AutomatedCodeReviewToolModel
    from app.schemas import ReviewRequest

    model = AutomatedCodeReviewToolModel(model_name=model_name)
    if not model.is_healthy:
        return None, f"Skipped: checkpoint {model_name!r} is not servable ({model.compatibility.get('reason')})."

    def run(diff: str) -> list[Prediction]:
        findings, _ = _predict_hunks(model, ReviewRequest(diff=diff, language="unknown"))
        return _to_predictions((f.model_dump() for f in findings), require_evidence=False)

    return run, f"Checkpoint {model.model_version}"


def url_runner(base_url: str, secret: str) -> tuple[Runner, dict[str, Counter[str]]]:
    engines: dict[str, Counter[str]] = {"engine": Counter()}

    def run(diff: str) -> list[Prediction]:
        body = json.dumps({"diff": diff, "language": "unknown", "mode": "diff"}).encode("utf-8")
        request = urllib.request.Request(
            base_url.rstrip("/") + "/ml/review",
            data=body,
            headers={"Content-Type": "application/json", "x-ml-worker-secret": secret},
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=60) as response:  # noqa: S310 - operator-supplied URL
            payload = json.loads(response.read().decode("utf-8"))
        engines["engine"][payload.get("engine", "unknown")] += 1
        return _to_predictions(payload["findings"], require_evidence=payload.get("engine") == "fallback")

    return run, engines


# ----------------------------------------------------------------------
# Run + report
# ----------------------------------------------------------------------
def evaluate(
    cases: Sequence[Case],
    runner: Runner,
    *,
    analyzer: Callable[[str], set[str]] | None,
    repeat: int,
    tolerance: int,
    measure_memory: bool,
) -> dict[str, Any]:
    taxonomy = _taxonomy_ids()
    predictions: dict[str, list[Prediction]] = {}
    latencies: list[float] = []
    peak_bytes = 0
    for case in cases:
        predictions[case.id] = runner(case.diff)
        for _ in range(repeat):
            started = time.perf_counter()
            runner(case.diff)
            latencies.append((time.perf_counter() - started) * 1000)
        if measure_memory:
            tracemalloc.start()
            runner(case.diff)
            peak_bytes = max(peak_bytes, tracemalloc.get_traced_memory()[1])
            tracemalloc.stop()

    scores = [score_case(case, predictions[case.id], tolerance) for case in cases]
    analyzed = {case.id: analyzer(case.diff) for case in cases} if analyzer else None
    report = aggregate(cases, scores, predictions, taxonomy, analyzed)
    report["localization"]["tolerance_lines"] = tolerance
    report["performance"] = {
        "runs_per_pr": repeat,
        "latency_ms_p50": percentile(latencies, 50),
        "latency_ms_p95": percentile(latencies, 95),
        "peak_python_heap_kib": round(peak_bytes / 1024, 1) if measure_memory else None,
        "note": "Wall-clock on the benchmark host; heap is tracemalloc peak per PR, not RSS.",
    }
    report["cases"] = [
        {
            "id": case.id,
            "title": case.title,
            "supported_expected": len(case.supported),
            "unsupported_defects": len(case.unsupported),
            "tp": len(score.matches),
            "fp": len(score.false_positives),
            "fn": len(score.false_negatives),
            "false_positives": [
                f"{p.anti_pattern} {p.file}:{p.start}-{p.end}" for p in score.false_positives
            ],
            "false_negatives": [
                f"{e.anti_pattern} {e.file}:{e.start}-{e.end}" for e in score.false_negatives
            ],
        }
        for case, score in zip(cases, scores)
    ]
    return report


def _fmt(value: Any) -> str:
    if value is None:
        return "n/a"
    if isinstance(value, float):
        return f"{value:.3f}"
    return str(value)


def render_markdown(result: dict[str, Any]) -> str:
    lines = [f"# Benchmark: {result['split']} / {result['engine']}", ""]
    if result.get("status") == "skipped":
        lines += [result["note"], ""]
        return "\n".join(lines)
    r = result["report"]
    o = r["overall"]
    lines += [
        f"{r['pr_level']['prs']} PRs, {o['supported_expected']} supported expected findings, "
        f"{r['unsupported_defects']['count']} unsupported defects, {r['decoys']['negatives']} labeled negatives. "
        f"Match tolerance: ±{r['localization']['tolerance_lines']} lines.",
        "",
        "| Metric | Value |",
        "| --- | --- |",
        f"| Precision | {_fmt(o['precision'])} |",
        f"| Recall (supported) | {_fmt(o['recall'])} |",
        f"| F1 | {_fmt(o['f1'])} |",
        f"| TP / FP / FN | {o['tp']} / {o['fp']} / {o['fn']} |",
        f"| False positives per PR | {_fmt(r['pr_level']['false_positives_per_pr'])} |",
        f"| FPs on labeled decoys | {r['decoys']['false_positives_on_decoys']} |",
        f"| Severity accuracy (matched) | {_fmt(r['severity']['accuracy'])} |",
        f"| Line localization accuracy | {_fmt(r['localization']['line_accuracy'])} |",
        f"| Hunk localization accuracy | {_fmt(r['localization']['hunk_accuracy'])} |",
        f"| Near misses (right hunk, wrong lines) | {r['localization']['near_misses_same_hunk']} |",
        f"| Coverage of all labeled defects | {_fmt(r['unsupported_defects']['coverage_of_all_labeled_defects'])} |",
        f"| Unsupported defects (never counted) | {r['unsupported_defects']['count']} |",
        f"| Unsupported-file rate | {_fmt(r['files'].get('unsupported_file_rate'))} |",
        f"| Findings with all guidance fields | {_fmt(r['explainability']['rate'])} |",
        f"| Latency p50 / p95 (ms) | {_fmt(r['performance']['latency_ms_p50'])} / {_fmt(r['performance']['latency_ms_p95'])} |",
        f"| Peak Python heap (KiB) | {_fmt(r['performance']['peak_python_heap_kib'])} |",
        "",
        "## Confusion by anti-pattern",
        "",
        "| antiPattern | TP | FP | FN | Precision | Recall | F1 |",
        "| --- | --- | --- | --- | --- | --- | --- |",
    ]
    for pattern, row in r["by_anti_pattern"].items():
        lines.append(
            f"| `{pattern}` | {row['tp']} | {row['fp']} | {row['fn']} | {_fmt(row['precision'])} "
            f"| {_fmt(row['recall'])} | {_fmt(row['f1'])} |"
        )
    lines += ["", "## By category", "", "| Category | TP | FP | FN | Precision | Recall | F1 |",
              "| --- | --- | --- | --- | --- | --- | --- |"]
    for category, row in r["by_category"].items():
        lines.append(
            f"| {category} | {row['tp']} | {row['fp']} | {row['fn']} | {_fmt(row['precision'])} "
            f"| {_fmt(row['recall'])} | {_fmt(row['f1'])} |"
        )
    if r["label_confusion"]:
        lines += ["", "## Wrong-label predictions on labeled lines", "",
                  "| Expected | Predicted | Count |", "| --- | --- | --- |"]
        for expected, row in r["label_confusion"].items():
            for predicted, count in row.items():
                lines.append(f"| {expected} | {predicted} | {count} |")
    lines += ["", "## Per PR", "", "| PR | Expected | Unsupported | TP | FP | FN |",
              "| --- | --- | --- | --- | --- | --- |"]
    for case in r["cases"]:
        lines.append(
            f"| {case['id']} | {case['supported_expected']} | {case['unsupported_defects']} "
            f"| {case['tp']} | {case['fp']} | {case['fn']} |"
        )
    errors = [(c["id"], kind, item) for c in r["cases"]
              for kind in ("false_positives", "false_negatives") for item in c[kind]]
    if errors:
        lines += ["", "## Errors", ""]
        for case_id, kind, item in errors:
            lines.append(f"- {case_id} {'FP' if kind == 'false_positives' else 'FN'}: {item}")
    lines += ["", "## Unsupported defects", "",
              ", ".join(f"{k}: {v}" for k, v in r["unsupported_defects"]["by_defect"].items()) or "none",
              "", r["unsupported_defects"]["note"], ""]
    return "\n".join(lines)


def run(
    splits: Sequence[str],
    engines: Sequence[str],
    *,
    worker_url: str | None = None,
    repeat: int = DEFAULT_REPEAT,
    tolerance: int = DEFAULT_TOLERANCE,
    out_dir: Path | None = RESULTS,
) -> list[dict[str, Any]]:
    results: list[dict[str, Any]] = []
    for split in splits:
        cases = load_cases(split)
        for engine in engines:
            meta = {"split": split, "engine": engine, "fixtures": len(cases)}
            if engine == "fallback":
                runner, analyzer = fallback_runner()
                result = {**meta, "status": "ok", "report": evaluate(
                    cases, runner, analyzer=analyzer, repeat=repeat,
                    tolerance=tolerance, measure_memory=True)}
            elif engine == "model":
                model_run, note = model_runner()
                if model_run is None:
                    result = {**meta, "status": "skipped", "note": note}
                else:
                    result = {**meta, "status": "ok", "note": note, "report": evaluate(
                        cases, model_run, analyzer=None, repeat=repeat,
                        tolerance=tolerance, measure_memory=True)}
            elif engine == "url":
                if not worker_url:
                    raise ValueError("--worker-url is required for the url engine")
                url_run, seen = url_runner(worker_url, os.environ.get("ML_WORKER_SECRET", ""))
                report = evaluate(cases, url_run, analyzer=None, repeat=repeat,
                                  tolerance=tolerance, measure_memory=False)
                result = {**meta, "status": "ok",
                          "engines_reported": dict(seen["engine"]), "report": report}
            else:
                raise ValueError(f"unknown engine {engine!r}")
            result["environment"] = {
                "python": platform.python_version(),
                "platform": platform.system(),
            }
            results.append(result)
            if out_dir is not None:
                out_dir.mkdir(parents=True, exist_ok=True)
                stem = out_dir / f"{split}-{engine}"
                stem.with_suffix(".json").write_text(
                    json.dumps(result, indent=2, sort_keys=False) + "\n", encoding="utf-8")
                stem.with_suffix(".md").write_text(render_markdown(result), encoding="utf-8")
    return results


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--split", choices=(*SPLITS, "all"), default="all")
    parser.add_argument("--engine", action="append", choices=("fallback", "model", "url"),
                        help="repeatable; default: fallback and model (model is skipped without a checkpoint)")
    parser.add_argument("--worker-url", help="also benchmark a running worker (adds the url engine)")
    parser.add_argument("--repeat", type=int, default=DEFAULT_REPEAT)
    parser.add_argument("--tolerance", type=int, default=DEFAULT_TOLERANCE)
    parser.add_argument("--out", type=Path, default=RESULTS)
    args = parser.parse_args(argv)
    splits = SPLITS if args.split == "all" else (args.split,)
    engines = list(args.engine or ["fallback", "model"])
    if args.worker_url and "url" not in engines:
        engines.append("url")
    for result in run(splits, engines, worker_url=args.worker_url, repeat=args.repeat,
                      tolerance=args.tolerance, out_dir=args.out):
        if result["status"] != "ok":
            print(f"{result['split']}/{result['engine']}: {result['note']}")
            continue
        overall = result["report"]["overall"]
        print(
            f"{result['split']}/{result['engine']}: P={_fmt(overall['precision'])} "
            f"R={_fmt(overall['recall'])} F1={_fmt(overall['f1'])} "
            f"TP={overall['tp']} FP={overall['fp']} FN={overall['fn']}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
