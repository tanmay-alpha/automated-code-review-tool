"""Benchmark harness: metric arithmetic, fixture validation and committed results."""

from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path
from types import ModuleType

import pytest

BENCH = Path(__file__).resolve().parents[3] / "benchmarks"


def _load_harness() -> ModuleType:
    spec = importlib.util.spec_from_file_location("run_benchmark", BENCH / "run_benchmark.py")
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


rb = _load_harness()

_SYNTHETIC_DIFF = (
    "diff --git a/svc.py b/svc.py\n"
    "--- a/svc.py\n"
    "+++ b/svc.py\n"
    "@@ -0,0 +1,3 @@\n"
    "+first = 1\n"
    "+second = 2\n"
    "+third = 3\n"
)
_SYNTHETIC_TRUTH = """
id: synthetic
title: tiny
expected:
  - {antiPattern: SECURITY_WEAK_CRYPTO, file: svc.py, lines: [1, 1], severity: major}
  - {antiPattern: READABILITY_MAGIC_NUMBER, file: svc.py, lines: [3, 3], severity: minor}
  - {antiPattern: unsupported, defect: race_condition, file: svc.py, lines: [2, 2], severity: major}
negatives:
  - {antiPattern: MAINTAINABILITY_PRINT_STATEMENT, file: svc.py, lines: [2, 2], reason: decoy}
"""


def _synthetic_case(tmp_path: Path, truth: str = _SYNTHETIC_TRUTH):
    (tmp_path / "synthetic.diff").write_text(_SYNTHETIC_DIFF, encoding="utf-8")
    (tmp_path / "synthetic.yaml").write_text(truth, encoding="utf-8")
    return rb.load_case(tmp_path / "synthetic.diff", "dev", rb._taxonomy_ids())


def _prediction(anti_pattern: str, line: int, severity: str = "minor"):
    return rb.Prediction(anti_pattern, "svc.py", line, line, severity, True)


def test_metric_arithmetic_on_a_synthetic_case(tmp_path: Path) -> None:
    case = _synthetic_case(tmp_path)
    predictions = [
        _prediction("SECURITY_WEAK_CRYPTO", 2, "major"),  # TP within tolerance, not line-exact
        _prediction("READABILITY_MAGIC_NUMBER", 10),  # outside tolerance: FP; expected stays FN
        _prediction("MAINTAINABILITY_PRINT_STATEMENT", 2),  # FP on a labeled decoy
    ]
    score = rb.score_case(case, predictions, tolerance=3)
    report = rb.aggregate(
        [case], [score], {case.id: predictions}, rb._taxonomy_ids(), {case.id: {"svc.py"}}
    )

    overall = report["overall"]
    assert (overall["tp"], overall["fp"], overall["fn"]) == (1, 2, 1)
    assert overall["precision"] == pytest.approx(1 / 3, abs=1e-4)
    assert overall["recall"] == 0.5
    assert overall["f1"] == pytest.approx(0.4)  # 2*1 / (2*1 + 2 + 1)
    assert report["by_anti_pattern"]["SECURITY_WEAK_CRYPTO"] == {
        "tp": 1, "fp": 0, "fn": 0, "precision": 1.0, "recall": 1.0, "f1": 1.0,
    }
    assert report["by_anti_pattern"]["READABILITY_MAGIC_NUMBER"]["f1"] == 0.0
    assert report["by_category"]["SECURITY"]["tp"] == 1
    assert report["localization"]["line_accuracy"] == 0.0
    assert report["localization"]["hunk_accuracy"] == 1.0
    assert report["severity"]["accuracy"] == 1.0
    assert report["decoys"]["false_positives_on_decoys"] == 1
    assert report["pr_level"]["false_positives_per_pr"] == 2.0
    # The unsupported defect is reported, never matched, and lowers coverage.
    assert report["unsupported_defects"]["count"] == 1
    assert report["unsupported_defects"]["predictions_overlapping"] == 1
    assert report["unsupported_defects"]["coverage_of_all_labeled_defects"] == pytest.approx(1 / 3, abs=1e-4)
    assert report["label_confusion"] == {
        "unsupported:race_condition": {"MAINTAINABILITY_PRINT_STATEMENT": 1}
    }
    assert report["files"]["unsupported_file_rate"] == 0.0


def test_an_expected_finding_matches_at_most_one_prediction(tmp_path: Path) -> None:
    case = _synthetic_case(tmp_path)
    duplicates = [_prediction("SECURITY_WEAK_CRYPTO", 1), _prediction("SECURITY_WEAK_CRYPTO", 1)]
    score = rb.score_case(case, duplicates, tolerance=0)
    assert (len(score.matches), len(score.false_positives)) == (1, 1)


def test_percentile_is_nearest_rank() -> None:
    values = [float(v) for v in range(1, 21)]
    assert rb.percentile(values, 50) == 10.0
    assert rb.percentile(values, 95) == 19.0
    assert rb.percentile([], 50) is None


@pytest.mark.parametrize(
    "bad_row, message",
    [
        ("{antiPattern: NOT_A_RULE, file: svc.py, lines: [1, 1], severity: minor}", "unknown antiPattern"),
        ("{antiPattern: unsupported, file: svc.py, lines: [1, 1], severity: minor}", "defect"),
        ("{antiPattern: SECURITY_WEAK_CRYPTO, file: svc.py, lines: [7, 9], severity: minor}", "no added line"),
        ("{antiPattern: SECURITY_WEAK_CRYPTO, file: other.py, lines: [1, 1], severity: minor}", "not in the diff"),
    ],
)
def test_invalid_ground_truth_is_rejected(tmp_path: Path, bad_row: str, message: str) -> None:
    with pytest.raises(ValueError, match=message):
        _synthetic_case(tmp_path, f"id: synthetic\nexpected:\n  - {bad_row}\n")


def test_diff_walk_decodes_git_quoted_paths() -> None:
    diff = (
        'diff --git "a/caf\\303\\251.py" "b/caf\\303\\251.py"\n'
        "new file mode 100644\n"
        "--- /dev/null\n"
        '+++ "b/caf\\303\\251.py"\n'
        "@@ -0,0 +1,2 @@\n+a = 1\n+b = 2\n"
    )
    [(path, change)] = rb.diff_files(diff).items()
    assert path == "café.py"
    assert change.added == frozenset({1, 2})


@pytest.mark.parametrize("split", rb.SPLITS)
def test_committed_results_match_a_fresh_run(split: str) -> None:
    """The numbers quoted in docs/review-benchmark.md come from these files."""
    [fresh] = rb.run([split], ["fallback"], repeat=1, out_dir=None)
    committed = json.loads((rb.RESULTS / f"{split}-fallback.json").read_text(encoding="utf-8"))
    for report in (fresh["report"], committed["report"]):
        report.pop("performance")
    assert fresh["report"] == committed["report"]
    assert fresh["fixtures"] == committed["fixtures"]


def test_every_dev_finding_is_explainable() -> None:
    [result] = rb.run(["dev"], ["fallback"], repeat=1, out_dir=None)
    explainability = result["report"]["explainability"]
    assert explainability["predictions"] > 0
    assert explainability["rate"] == 1.0
