"""
Canonical anti-pattern taxonomy loader for the ML worker.

Loads the canonical anti-pattern taxonomy from
``taxonomy/anti_patterns.yaml`` and exposes it as a small dataclass
hierarchy. Every Python module that references an anti-pattern ID MUST
import from here so all implementations stay in sync.

The path resolution is:

    1. The path passed explicitly to :func:`load_taxonomy`.
    2. ``<repo-root>/taxonomy/anti_patterns.yaml`` (computed from this file).

Use :func:`load_taxonomy` to get a :class:`Taxonomy` instance.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover - yaml is in requirements-test.txt
    yaml = None


def _find_default_taxonomy_path() -> Path:
    """Locate ``taxonomy/anti_patterns.yaml`` relative to this file.

    Walks upward from the module's directory looking for a sibling
    ``taxonomy/anti_patterns.yaml``. This works both in development
    (``apps/ml-worker/app/taxonomy.py`` -> 3 levels up) and in the
    Docker image where the directory layout is flattened to ``/app``.
    """
    here = Path(__file__).resolve().parent
    for ancestor in (here, *here.parents):
        candidate = ancestor / "taxonomy" / "anti_patterns.yaml"
        if candidate.exists():
            return candidate
    # Return the conventional container path so load_taxonomy emits one clear
    # FileNotFoundError. Never substitute inline label definitions.
    return here.parent / "taxonomy" / "anti_patterns.yaml"


DEFAULT_TAXONOMY_PATH = _find_default_taxonomy_path()

_SEMVER_RE = re.compile(r"^\d+\.\d+\.\d+$")
_VALID_CATEGORIES = frozenset({
    "SECURITY",
    "PERFORMANCE",
    "ARCHITECTURE",
    "RELIABILITY",
    "READABILITY",
    "MAINTAINABILITY",
})
_VALID_SEVERITIES = frozenset({"critical", "major", "minor"})
_REQUIRED_FIELDS = ("id", "display_name", "category", "default_severity", "trainable")
_ID_PATTERN = re.compile(r"^[A-Z][A-Z0-9]+(_[A-Z0-9]+)+$")


class TaxonomyError(Exception):
    """Raised when the taxonomy file is missing, malformed, or violates the contract."""


@dataclass(frozen=True)
class AntiPattern:
    """One anti-pattern entry from the canonical taxonomy."""

    id: str
    display_name: str
    category: str
    default_severity: str
    description: str
    trainable: bool = False


@dataclass(frozen=True)
class Taxonomy:
    """Loaded taxonomy: an ordered list of :class:`AntiPattern` entries."""

    entries: tuple[AntiPattern, ...]
    version: str

    def by_id(self, anti_pattern_id: str) -> AntiPattern | None:
        for entry in self.entries:
            if entry.id == anti_pattern_id:
                return entry
        return None

    def ids(self) -> list[str]:
        return [e.id for e in self.entries]

    def trainable_ids(self) -> list[str]:
        """Return the IDs of entries with ``trainable: true`` in taxonomy order."""
        return [e.id for e in self.entries if e.trainable]


def _default_yaml_parser():
    if yaml is None:
        raise TaxonomyError(
            "PyYAML is required to load the taxonomy. "
            "Install it with `pip install pyyaml`."
        )
    return yaml.safe_load


def load_taxonomy(path: Path | str | None = None) -> Taxonomy:
    """Load the canonical taxonomy from YAML.

    Performs strict validation:

    * YAML file must exist.
    * ``version`` must be a non-empty semantic-version string (e.g. ``1.0.0``).
    * ``anti_patterns`` must be a list with at least one entry.
    * Every entry must have all required fields: id, display_name, category,
      default_severity, trainable.
    * IDs must be unique.
    * IDs must match ``^[A-Z][A-Z0-9]+(_[A-Z0-9]+)+$``.
    * Categories must be one of the six allowed categories.
    * Severities must be one of the three allowed severities.
    * ``trainable`` must be a boolean.
    * At least one entry must have ``trainable: true``.
    * Trainable entries must appear in deterministic order (i.e., the YAML order).

    Args:
        path: Optional override path. Defaults to
            ``<repo-root>/taxonomy/anti_patterns.yaml``.

    Raises:
        FileNotFoundError: If the YAML file does not exist.
        TaxonomyError: If the YAML is malformed or violates the contract.
    """
    yaml_path = Path(path) if path else DEFAULT_TAXONOMY_PATH
    if not yaml_path.exists():
        raise FileNotFoundError(f"Taxonomy file not found: {yaml_path}")

    parser = _default_yaml_parser()
    with yaml_path.open("r", encoding="utf-8") as f:
        data = parser(f)

    if not isinstance(data, dict):
        raise TaxonomyError("Taxonomy YAML must be a top-level mapping")

    # --- version ---
    raw_version = data.get("version")
    if not raw_version or not isinstance(raw_version, str):
        raise TaxonomyError(
            f"Taxonomy 'version' must be a non-empty string, got {raw_version!r}"
        )
    raw_version = raw_version.strip()
    if not _SEMVER_RE.match(raw_version):
        raise TaxonomyError(
            f"Taxonomy 'version' must be a semantic version (e.g. '1.0.0'), "
            f"got {raw_version!r}"
        )

    # --- anti_patterns list ---
    raw_entries = data.get("anti_patterns", [])
    if not isinstance(raw_entries, list):
        raise TaxonomyError("'anti_patterns' must be a list")
    if not raw_entries:
        raise TaxonomyError("'anti_patterns' must contain at least one entry")

    entries: list[AntiPattern] = []
    seen_ids: set[str] = set()

    for raw in raw_entries:
        if not isinstance(raw, dict):
            raise TaxonomyError(f"Taxonomy entry must be a mapping, got {type(raw).__name__}: {raw}")

        for required in _REQUIRED_FIELDS:
            if required not in raw:
                raise TaxonomyError(
                    f"Taxonomy entry missing '{required}': {raw}"
                )

        ap_id = str(raw["id"]).strip()
        if not ap_id:
            raise TaxonomyError("Taxonomy entry 'id' must not be empty")

        if ap_id in seen_ids:
            raise TaxonomyError(f"Duplicate taxonomy entry ID: {ap_id!r}")
        seen_ids.add(ap_id)

        if not _ID_PATTERN.match(ap_id):
            raise TaxonomyError(
                f"Taxonomy entry ID {ap_id!r} does not match "
                f"required pattern {_ID_PATTERN.pattern!r}"
            )

        category = str(raw["category"]).strip().upper()
        if category not in _VALID_CATEGORIES:
            raise TaxonomyError(
                f"Taxonomy entry {ap_id!r} has invalid category {category!r}. "
                f"Allowed: {sorted(_VALID_CATEGORIES)}"
            )

        severity = str(raw["default_severity"]).strip().lower()
        if severity not in _VALID_SEVERITIES:
            raise TaxonomyError(
                f"Taxonomy entry {ap_id!r} has invalid default_severity "
                f"{severity!r}. Allowed: {sorted(_VALID_SEVERITIES)}"
            )

        trainable_raw = raw["trainable"]
        if not isinstance(trainable_raw, bool):
            raise TaxonomyError(
                f"Taxonomy entry {ap_id!r} 'trainable' must be a boolean, "
                f"got {type(trainable_raw).__name__}"
            )

        display_name = str(raw["display_name"]).strip()
        description = str(raw.get("description", "")).strip()

        entries.append(AntiPattern(
            id=ap_id,
            display_name=display_name,
            category=category,
            default_severity=severity,
            description=description,
            trainable=trainable_raw,
        ))

    trainable_count = sum(1 for e in entries if e.trainable)
    if trainable_count == 0:
        raise TaxonomyError(
            "Taxonomy must contain at least one entry with trainable: true"
        )

    return Taxonomy(entries=tuple(entries), version=raw_version)


def load_canonical_taxonomy(path: Path | str | None = None) -> dict:
    """Convenience wrapper returning the canonical taxonomy as a plain dict.

    Equivalent to ``{**{"version": t.version}, "entries": [...]}`` but
    the shape matches what training/eval tests expect.
    """
    tax = load_taxonomy(path)
    return {
        "version": tax.version,
        "entries": [
            {
                "id": e.id,
                "display_name": e.display_name,
                "category": e.category,
                "default_severity": e.default_severity,
                "trainable": e.trainable,
                "description": e.description,
            }
            for e in tax.entries
        ],
    }


def trainable_ids(path: Path | str | None = None) -> tuple[str, ...]:
    """Return the deterministic tuple of trainable label IDs, in YAML order."""
    return tuple(e.id for e in load_taxonomy(path).entries if e.trainable)
