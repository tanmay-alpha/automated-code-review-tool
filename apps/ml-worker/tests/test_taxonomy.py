"""
automated-code-review-tool — taxonomy consistency tests (Phase 0).

The canonical YAML taxonomy at ``taxonomy/anti_patterns.yaml`` is the
single source of truth for anti-pattern IDs. Every other module in this
project that references an anti-pattern ID (fallback scanner, schemas,
training scripts, tests) MUST use IDs that exist in the taxonomy, and
the categories used in those modules MUST match the leading-prefix
category declared in the taxonomy.

These tests pin that contract so a typo in the YAML, in the scanner, or
in any test is caught at CI time.

Run from the repo root:
    cd apps/ml-worker && pytest tests/test_taxonomy.py -v
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import pytest

# Allow `from app.taxonomy import ...` regardless of where pytest is invoked from.
_HERE = Path(__file__).resolve().parent
_ML_WORKER = _HERE.parent
sys.path.insert(0, str(_ML_WORKER))

from app.fallback_scanner import _RULE_CONFIG  # noqa: E402
from app.taxonomy import load_taxonomy  # noqa: E402


REPO_ROOT = Path(__file__).resolve().parents[3]
TAXONOMY_PATH = REPO_ROOT / "taxonomy" / "anti_patterns.yaml"

_VALID_SEVERITIES = {"critical", "major", "minor"}
_ID_PATTERN = re.compile(r"^[A-Z][A-Z0-9]+(_[A-Z0-9]+)+$")


# ----------------------------------------------------------------------
# Fixtures
# ----------------------------------------------------------------------
@pytest.fixture(scope="module")
def taxonomy():
    """Load the canonical taxonomy once for the whole module."""
    return load_taxonomy()


@pytest.fixture(scope="module")
def taxonomy_ids(taxonomy) -> list[str]:
    """Sorted list of every ID declared in the taxonomy."""
    return taxonomy.ids()


# ----------------------------------------------------------------------
# Taxonomy existence & loadability
# ----------------------------------------------------------------------
def test_yaml_taxonomy_exists():
    """The canonical YAML file must exist on disk."""
    assert TAXONOMY_PATH.exists(), f"Missing taxonomy file at {TAXONOMY_PATH}"
    assert TAXONOMY_PATH.is_file(), f"{TAXONOMY_PATH} is not a regular file"


def test_yaml_taxonomy_loads():
    """``load_taxonomy()`` must succeed against the canonical YAML."""
    taxonomy = load_taxonomy()
    assert taxonomy is not None
    assert len(taxonomy.entries) > 0, "Taxonomy contains zero anti-pattern entries"


def test_yaml_taxonomy_has_version(taxonomy):
    """The loaded taxonomy must expose a ``version`` field."""
    assert hasattr(taxonomy, "version"), "Taxonomy missing 'version' attribute"
    assert taxonomy.version, f"Taxonomy version is empty: {taxonomy.version!r}"
    assert isinstance(taxonomy.version, str)


# ----------------------------------------------------------------------
# Fallback scanner ↔ taxonomy alignment
# ----------------------------------------------------------------------
def test_all_fallback_rule_ids_are_in_taxonomy(taxonomy_ids):
    """Every ID in ``_RULE_CONFIG`` must be present in the canonical taxonomy."""
    taxonomy_set = set(taxonomy_ids)
    fallback_ids = set(_RULE_CONFIG.keys())

    missing = fallback_ids - taxonomy_set
    assert not missing, (
        f"Fallback scanner references IDs not in taxonomy: "
        f"{sorted(missing)}"
    )


def test_no_unknown_ids_in_fallback_scanner(taxonomy_ids):
    """Symmetric guard: no unknown IDs in the fallback scanner (alias of above)."""
    taxonomy_set = set(taxonomy_ids)
    fallback_ids = set(_RULE_CONFIG.keys())

    unknown = sorted(fallback_ids - taxonomy_set)
    assert not unknown, (
        f"Fallback scanner has {len(unknown)} unknown IDs: {unknown}"
    )


# ----------------------------------------------------------------------
# Per-entry invariants
# ----------------------------------------------------------------------
def test_taxonomy_category_consistency(taxonomy):
    """The leading prefix of each ID (split on first '_') must equal its
    declared category. E.g. ``SECURITY_HARDCODED_SECRET`` → ``SECURITY``."""
    bad: list[tuple[str, str, str]] = []
    for entry in taxonomy.entries:
        prefix = entry.id.split("_", 1)[0]
        if prefix != entry.category:
            bad.append((entry.id, prefix, entry.category))

    assert not bad, (
        "Taxonomy entries whose leading prefix does not match their category: "
        f"{bad}"
    )


def test_taxonomy_ids_are_unique(taxonomy_ids):
    """No ID may appear twice in the taxonomy."""
    assert len(taxonomy_ids) == len(set(taxonomy_ids)), (
        f"Duplicate IDs in taxonomy: "
        f"{sorted([i for i in taxonomy_ids if taxonomy_ids.count(i) > 1])}"
    )


def test_taxonomy_severity_is_valid(taxonomy):
    """Each entry's ``default_severity`` must be one of the allowed literals."""
    bad: list[tuple[str, str]] = []
    for entry in taxonomy.entries:
        if entry.default_severity not in _VALID_SEVERITIES:
            bad.append((entry.id, entry.default_severity))

    assert not bad, (
        f"Taxonomy entries with invalid default_severity: {bad}"
    )


def test_taxonomy_id_format(taxonomy_ids):
    """Each ID must match ``^[A-Z][A-Z0-9]+(_[A-Z0-9]+)+$``.

    Format rules:
      * Starts with an uppercase letter (no leading digit/underscore).
      * Two or more underscore-separated segments.
      * Each segment is uppercase letters or digits only.
    """
    bad = [i for i in taxonomy_ids if not _ID_PATTERN.match(i)]
    assert not bad, (
        f"Taxonomy IDs that violate the format rule "
        f"{_ID_PATTERN.pattern!r}: {bad}"
    )
