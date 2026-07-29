"""Canonical taxonomy loader.

This package wraps the YAML file at ``taxonomy/anti_patterns.yaml``
and exposes deterministic helpers used by both the training pipeline
and the evaluation pipeline.

Importing this module is enough to load the canonical taxonomy.

Layout of the YAML:

* ``version`` (semver string)
* ``entries`` (list of label definitions)

Each entry must declare:

* ``id``           (unique, kebab-friendly identifier)
* ``display_name`` (human-readable name)
* ``category``     (one of SECURITY/PERFORMANCE/ARCHITECTURE/RELIABILITY/READABILITY/MAINTAINABILITY)
* ``default_severity`` (critical/major/minor)
* ``trainable``    (bool) — may be included in the model output?
* ``description``  (free-form)

If any of these invariants fail the loader raises :class:`TaxonomyError`
with a precise message — there is no silent fallback.
"""

from __future__ import annotations

import os
import re
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import yaml  # type: ignore

VALID_CATEGORIES = frozenset({
    "SECURITY", "PERFORMANCE", "ARCHITECTURE", "RELIABILITY",
    "READABILITY", "MAINTAINABILITY",
})
VALID_SEVERITIES = frozenset({"critical", "major", "minor"})
SEMVER_RE = re.compile(r"^\d+\.\d+\.\d+$")


class TaxonomyError(RuntimeError):
    pass


@dataclass(frozen=True)
class AntiPattern:
    id: str
    display_name: str
    category: str
    default_severity: str
    trainable: bool
    description: str = ""

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "display_name": self.display_name,
            "category": self.category,
            "default_severity": self.default_severity,
            "trainable": self.trainable,
            "description": self.description,
        }


@dataclass(frozen=True)
class Taxonomy:
    version: str
    entries: Tuple[AntiPattern, ...]

    def to_dict(self) -> Dict[str, Any]:
        return {
            "version": self.version,
            "entries": [e.to_dict() for e in self.entries],
        }

    def by_id(self, pid: str) -> Optional[AntiPattern]:
        for e in self.entries:
            if e.id == pid:
                return e
        return None


def _candidate_paths() -> List[Path]:
    here = Path(__file__).resolve().parent
    pkg_root = here.parent
    candidates: List[Path] = [
        Path("/app/taxonomy/anti_patterns.yaml"),
        pkg_root / "taxonomy" / "anti_patterns.yaml",
        here / "anti_patterns.yaml",
    ]
    env = os.environ.get("TAXONOMY_PATH")
    if env:
        candidates.insert(0, Path(env))
    return candidates


def _resolve_yaml() -> Path:
    for p in _candidate_paths():
        if p.exists():
            return p
    raise TaxonomyError(
        "taxonomy/anti_patterns.yaml not found in any candidate path: "
        + ", ".join(str(p) for p in _candidate_paths())
    )


def _parse_yaml(path: Path) -> Dict[str, Any]:
    with path.open("r", encoding="utf-8") as f:
        data = yaml.safe_load(f)
    if not isinstance(data, dict):
        raise TaxonomyError(f"taxonomy YAML root must be a mapping, got {type(data).__name__}")
    return data


def _validate(data: Dict[str, Any]) -> Taxonomy:
    version = data.get("version", "")
    if not isinstance(version, str) or not SEMVER_RE.match(version):
        raise TaxonomyError(f"invalid taxonomy version {version!r}; must match \\d+\\.\\d+\\.\\d+")
    entries_raw = data.get("anti_patterns") or data.get("entries") or []
    if not isinstance(entries_raw, list) or not entries_raw:
        raise TaxonomyError("taxonomy must declare a non-empty 'anti_patterns' list")

    seen_ids: set[str] = set()
    entries: List[AntiPattern] = []
    for raw in entries_raw:
        if not isinstance(raw, dict):
            raise TaxonomyError(f"entry must be a mapping, got {type(raw).__name__}")
        for field in ("id", "display_name", "category", "default_severity"):
            if field not in raw or not raw[field]:
                raise TaxonomyError(f"entry missing required field {field!r}: {raw}")
        pid = raw["id"]
        if pid in seen_ids:
            raise TaxonomyError(f"duplicate taxonomy id: {pid}")
        seen_ids.add(pid)
        if raw["category"] not in VALID_CATEGORIES:
            raise TaxonomyError(
                f"entry {pid} has invalid category {raw['category']!r}; "
                f"allowed: {sorted(VALID_CATEGORIES)}"
            )
        if raw["default_severity"] not in VALID_SEVERITIES:
            raise TaxonomyError(
                f"entry {pid} has invalid severity {raw['default_severity']!r}; "
                f"allowed: {sorted(VALID_SEVERITIES)}"
            )
        if not isinstance(raw.get("trainable", False), bool):
            raise TaxonomyError(f"entry {pid} 'trainable' must be a bool")
        entries.append(AntiPattern(
            id=pid,
            display_name=raw["display_name"],
            category=raw["category"],
            default_severity=raw["default_severity"],
            trainable=bool(raw["trainable"]),
            description=str(raw.get("description", "")),
        ))

    if not any(e.trainable for e in entries):
        raise TaxonomyError("taxonomy must contain at least one trainable entry")
    return Taxonomy(version=version, entries=tuple(entries))


@lru_cache(maxsize=1)
def load_taxonomy() -> Taxonomy:
    path = _resolve_yaml()
    data = _parse_yaml(path)
    return _validate(data)


def load_canonical_taxonomy() -> Dict[str, Any]:
    """Return a dict-shaped view used by callers that prefer dicts."""
    tax = load_taxonomy()
    return tax.to_dict()


def trainable_ids() -> Tuple[str, ...]:
    """Return a deterministic tuple of trainable label IDs in the
    order they appear in the YAML file (file order is stable)."""
    return tuple(e.id for e in load_taxonomy().entries if e.trainable)


__all__ = [
    "AntiPattern",
    "Taxonomy",
    "TaxonomyError",
    "load_taxonomy",
    "load_canonical_taxonomy",
    "trainable_ids",
]