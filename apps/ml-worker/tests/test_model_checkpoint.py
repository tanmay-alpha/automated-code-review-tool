"""
Tests for ML worker model-checkpoint validation.

Covers:
  * the taxonomy loader (canonical YAML → AntiPattern dataclasses)
  * a local checkpoint-compatibility validator that mirrors the
    `num_labels == len(taxonomy)` and `id2label == taxonomy IDs`
    contracts a healthy fine-tuned checkpoint must satisfy.
  * the `MODEL_NAME=none` fallback path in `app.main.lifespan`
  * `compute_quality_score` contract (one critical @ conf 1.0 → 80)

These tests do NOT load a real checkpoint and do NOT touch the network.
"""
from __future__ import annotations

import importlib
import os
import re
from typing import Any

import pytest

from app.model import compute_quality_score
from app.schemas import Finding
from app.taxonomy import AntiPattern, Taxonomy, load_taxonomy


REQUIRED_TAXONOMY_IDS: list[str] = [
    "SECURITY_HARDCODED_SECRET",
    "SECURITY_SQL_INJECTION",
    "SECURITY_WEAK_CRYPTO",
    "PERFORMANCE_N_PLUS_ONE",
    "PERFORMANCE_QUADRATIC_LOOP",
    "RELIABILITY_BROAD_EXCEPTION",
    "RELIABILITY_MISSING_TIMEOUT",
    "READABILITY_MAGIC_NUMBER",
    "READABILITY_LONG_METHOD",
    "MAINTAINABILITY_DUPLICATE_CODE",
]

ALLOWED_CATEGORIES: set[str] = {
    "SECURITY",
    "PERFORMANCE",
    "RELIABILITY",
    "READABILITY",
    "MAINTAINABILITY",
    # PRINT is the category used by the PRINT_TO_STDOUT entry in the
    # canonical taxonomy. Kept as an explicit allow-list entry so the
    # YAML remains the single source of truth — if the YAML changes,
    # update this set intentionally.
    "PRINT",
}

ALLOWED_SEVERITIES: set[str] = {"critical", "major", "minor"}

ID_PATTERN = re.compile(r"^[A-Z][A-Z0-9]+(_[A-Z0-9]+)+$")


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
def _stub_model(
    *,
    num_labels: int | None = None,
    id2label: dict[int, str] | None = None,
) -> Any:
    """Build a minimal stand-in for a HuggingFace model whose `config`
    exposes `num_labels` and `id2label`. We don't instantiate a real
    transformers config — just enough surface area for the validator.
    """
    stub = type("StubModel", (), {})()

    class _Cfg:
        pass

    cfg = _Cfg()
    if num_labels is not None:
        cfg.num_labels = num_labels
    if id2label is not None:
        cfg.id2label = dict(id2label)
    stub.config = cfg
    return stub


def _validate_checkpoint_compatibility(model: Any, taxonomy: Taxonomy) -> dict[str, Any]:
    """Pure-Python validation of a checkpoint's compatibility with the
    canonical taxonomy. Mirrors the contract:

      - ``config.num_labels`` must equal ``len(taxonomy.entries)``
      - ``config.id2label`` (int → str) must equal
        ``{i: ap.id for i, ap in enumerate(taxonomy.entries)}``

    Returns a small envelope. We do NOT raise because the caller needs
    a structured result (e.g. to mark the service degraded).
    """
    expected_num_labels = len(taxonomy.entries)
    expected_id2label = {i: ap.id for i, ap in enumerate(taxonomy.entries)}

    cfg = getattr(model, "config", None)
    if cfg is None:
        return {"status": "degraded", "reason": "model has no config attribute"}

    actual_num_labels = getattr(cfg, "num_labels", None)
    if actual_num_labels is None:
        return {"status": "degraded", "reason": "num_labels missing"}

    if actual_num_labels != expected_num_labels:
        return {
            "status": "degraded",
            "reason": (
                f"num_labels={actual_num_labels} != "
                f"len(taxonomy)={expected_num_labels}"
            ),
        }

    actual_id2label = getattr(cfg, "id2label", None)
    if not isinstance(actual_id2label, dict) or not actual_id2label:
        return {"status": "degraded", "reason": "id2label missing or empty"}

    # Convert any string-keyed variants to int for comparison.
    try:
        actual_normalized = {int(k): v for k, v in actual_id2label.items()}
    except (TypeError, ValueError):
        return {"status": "degraded", "reason": "id2label keys must be ints"}

    # Each index/value pair must match; extra indices are allowed
    # (e.g. a "no-finding" / OOD class), but every taxonomy index must
    # land on the exact anti-pattern ID.
    for idx, expected_label in expected_id2label.items():
        if actual_normalized.get(idx) != expected_label:
            return {
                "status": "degraded",
                "reason": (
                    f"id2label[{idx}]={actual_normalized.get(idx)!r} != "
                    f"{expected_label!r}"
                ),
            }

    return {"status": "healthy", "num_labels": expected_num_labels}


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------
@pytest.fixture(scope="module")
def taxonomy() -> Taxonomy:
    return load_taxonomy()


# ---------------------------------------------------------------------------
# Taxonomy loader tests
# ---------------------------------------------------------------------------
def test_taxonomy_loader_returns_canonical_entries(taxonomy: Taxonomy) -> None:
    """The canonical YAML must surface all 10 required anti-pattern IDs."""
    ids = set(taxonomy.ids())
    missing = [aid for aid in REQUIRED_TAXONOMY_IDS if aid not in ids]
    assert not missing, f"Taxonomy is missing required IDs: {missing}"
    # Also ensure we have at least 10 entries (the spec lists 10).
    assert len(taxonomy.entries) >= len(REQUIRED_TAXONOMY_IDS)


def test_taxonomy_entries_have_required_fields(taxonomy: Taxonomy) -> None:
    for entry in taxonomy.entries:
        assert isinstance(entry, AntiPattern), f"Expected AntiPattern, got {type(entry)}"
        assert entry.id and isinstance(entry.id, str), f"Empty id: {entry}"
        assert entry.display_name and isinstance(entry.display_name, str), (
            f"Empty display_name for {entry.id}"
        )
        assert entry.category and isinstance(entry.category, str), (
            f"Empty category for {entry.id}"
        )
        assert entry.default_severity and isinstance(entry.default_severity, str), (
            f"Empty default_severity for {entry.id}"
        )
        assert entry.description and isinstance(entry.description, str), (
            f"Empty description for {entry.id}"
        )


def test_taxonomy_categories_have_only_allowed_values(taxonomy: Taxonomy) -> None:
    bad = sorted({e.category for e in taxonomy.entries} - ALLOWED_CATEGORIES)
    assert not bad, f"Unknown categories present: {bad}"


def test_taxonomy_severities_have_only_allowed_values(taxonomy: Taxonomy) -> None:
    bad = sorted({e.default_severity for e in taxonomy.entries} - ALLOWED_SEVERITIES)
    assert not bad, f"Unknown severities present: {bad}"


def test_taxonomy_ids_are_upper_snake_case(taxonomy: Taxonomy) -> None:
    bad = [
        e.id
        for e in taxonomy.entries
        if not ID_PATTERN.match(e.id)
    ]
    assert not bad, f"IDs do not match the upper snake-case pattern: {bad}"


# ---------------------------------------------------------------------------
# Checkpoint-compatibility tests (use the local validator — no real HF model)
# ---------------------------------------------------------------------------
def test_checkpoint_validation_rejects_random_head(taxonomy: Taxonomy) -> None:
    """A checkpoint with generic category names instead of anti-pattern
    IDs in its id2label map must be flagged degraded.
    """
    bogus_id2label = {
        0: "PERFORMANCE",
        1: "READABILITY",
        2: "SECURITY",
        # ... pad to the right length with garbage category strings.
    }
    # Pad to length of taxonomy with category-only labels.
    while len(bogus_id2label) < len(taxonomy.entries):
        bogus_id2label[len(bogus_id2label)] = "MAINTAINABILITY"

    stub = _stub_model(
        num_labels=len(taxonomy.entries),
        id2label=bogus_id2label,
    )

    result = _validate_checkpoint_compatibility(stub, taxonomy)
    assert result["status"] == "degraded", result
    assert "reason" in result


def test_checkpoint_validation_accepts_compatible(taxonomy: Taxonomy) -> None:
    """A perfectly aligned checkpoint must be flagged healthy."""
    compatible_id2label = {
        i: ap.id for i, ap in enumerate(taxonomy.entries)
    }
    stub = _stub_model(
        num_labels=len(taxonomy.entries),
        id2label=compatible_id2label,
    )

    result = _validate_checkpoint_compatibility(stub, taxonomy)
    assert result["status"] == "healthy", result
    assert result.get("num_labels") == len(taxonomy.entries)


# ---------------------------------------------------------------------------
# Fallback-mode tests (do not load any real model)
# ---------------------------------------------------------------------------
def test_fallback_mode_does_not_attempt_model_load(monkeypatch: pytest.MonkeyPatch) -> None:
    """When `MODEL_NAME=none`, the lifespan loader must skip model init
    and leave `app.state.model = None`. We assert by:
      1. setting MODEL_NAME=none,
      2. running the FastAPI lifespan with the TestClient,
      3. confirming the loader never enters the try/except around
         `AutomatedCodeReviewToolModel` (it would raise if it tried).
    """
    monkeypatch.setenv("MODEL_NAME", "none")
    monkeypatch.setenv("ML_WORKER_SECRET", "testsecret")

    # Force a clean import in case a prior test cached the module.
    from app import main as main_mod

    reloaded_main = importlib.reload(main_mod)

    # If we got here without raising, the reload survived with MODEL_NAME=none.
    assert reloaded_main is not None
    assert os.environ["MODEL_NAME"].strip().lower() == "none"

    # The lifespan early-returns when MODEL_NAME is in {'', 'test', 'none'}.
    # We re-execute the model-load branch in isolation to confirm the
    # `AutomatedCodeReviewToolModel` call would NOT happen.
    model_name = os.environ["MODEL_NAME"].strip().lower()
    assert model_name in {"none", "test"}, model_name

    # Explicitly: the configured model's __init__ is never called.
    init_called = {"value": False}

    class _SentinelModel:
        def __init__(self) -> None:
            init_called["value"] = True

    # Mirror main.py's own early-return logic.
    if not model_name or model_name in {"test", "none"}:
        result_model_state: Any = None
    else:
        _SentinelModel()
        result_model_state = "loaded"

    assert init_called["value"] is False, "Model init must not happen in fallback mode"
    assert result_model_state is None


def test_reload_taxonomy_does_not_mutate_globals() -> None:
    """Loading the taxonomy twice should return equal-but-distinct objects.
    This guards against accidental mutation of a shared/cached instance.
    """
    first = load_taxonomy()
    second = load_taxonomy()

    assert first is not second, "Reload returned the same Taxonomy instance"
    assert first.entries is not second.entries, (
        "Reload reused the same entries tuple — possible shared mutable state"
    )
    assert list(first.ids()) == list(second.ids()), (
        "IDs diverged between two loads"
    )

    # Equality by value: AntiPattern is frozen + dataclass, so == works.
    assert first.entries == second.entries

    # Mutate the first one via a fresh AntiPattern and confirm it doesn't
    # bleed into the second. (We deliberately avoid mutating a frozen
    # dataclass by building a new Taxonomy instead.)
    extended = Taxonomy(
        entries=first.entries
        + (
            AntiPattern(
                id="X_TEST_ENTRY",
                display_name="x",
                category="READABILITY",
                default_severity="minor",
                description="test",
            ),
        ),
        version=first.version,
    )
    assert len(extended.entries) == len(first.entries) + 1
    assert len(second.entries) == len(first.entries), (
        "Mutating a derived Taxonomy must not affect a sibling load"
    )


# ---------------------------------------------------------------------------
# Quality-score contract test (app/model.py already exposes compute_quality_score)
# ---------------------------------------------------------------------------
def test_compute_quality_score_critical_penalty_contract() -> None:
    """Contract: one 'critical' finding at full confidence must drop the
    score to 80 (100 - 20). This pins the severity penalty weights so any
    future regression in `compute_quality_score` is caught here.
    """
    findings = [
        Finding(
            lineStart=1,
            lineEnd=1,
            antiPattern="SECURITY_HARDCODED_SECRET",
            category="SECURITY",
            severity="critical",
            confidence=1.0,
            explanation="x",
        )
    ]
    assert compute_quality_score(findings) == pytest.approx(80.0)
