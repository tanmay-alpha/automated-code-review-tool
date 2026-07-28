"""
automated-code-review-tool — Taxonomy loader (Phase 0).

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

from dataclasses import dataclass
from pathlib import Path

try:
    import yaml  # type: ignore[import-not-found]
except ImportError:  # pragma: no cover - yaml is in requirements-train.txt
    yaml = None  # type: ignore[assignment]


REPO_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_TAXONOMY_PATH = REPO_ROOT / "taxonomy" / "anti_patterns.yaml"


@dataclass(frozen=True)
class AntiPattern:
    """One anti-pattern entry from the canonical taxonomy."""

    id: str
    display_name: str
    category: str
    default_severity: str
    description: str


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


def _default_yaml_parser():
    if yaml is None:
        raise RuntimeError(
            "PyYAML is required to load the taxonomy. "
            "Install it with `pip install pyyaml`."
        )
    return yaml.safe_load


def load_taxonomy(path: Path | str | None = None) -> Taxonomy:
    """Load the canonical taxonomy from YAML.

    Args:
        path: Optional override path. Defaults to
            ``<repo-root>/taxonomy/anti_patterns.yaml``.

    Raises:
        FileNotFoundError: If the YAML file does not exist.
        RuntimeError: If PyYAML is not installed or the YAML is malformed.
        ValueError: If a required field is missing from an entry.
    """
    yaml_path = Path(path) if path else DEFAULT_TAXONOMY_PATH
    if not yaml_path.exists():
        raise FileNotFoundError(f"Taxonomy file not found: {yaml_path}")
    parser = _default_yaml_parser()
    with yaml_path.open("r", encoding="utf-8") as f:
        data = parser(f)
    raw_entries = data.get("anti_patterns", [])
    entries: list[AntiPattern] = []
    for raw in raw_entries:
        for required in ("id", "display_name", "category", "default_severity"):
            if required not in raw:
                raise ValueError(
                    f"Taxonomy entry missing '{required}': {raw}"
                )
        entries.append(
            AntiPattern(
                id=raw["id"],
                display_name=raw["display_name"],
                category=raw["category"],
                default_severity=raw["default_severity"],
                description=raw.get("description", "").strip(),
            )
        )
    return Taxonomy(entries=tuple(entries), version="phase-0")