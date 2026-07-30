"""Canonical taxonomy loader (thin shim over app.taxonomy).

The canonical loader lives in ``apps/ml-worker/app/taxonomy.py``;
this shim re-exports the public API so callers can simply do::

    from taxonomy import load_canonical_taxonomy, trainable_ids

regardless of where the call site lives.
"""
from __future__ import annotations

# Re-export the canonical taxonomy API from the single source of truth.
from app.taxonomy import (  # noqa: F401
    AntiPattern,
    Taxonomy,
    TaxonomyError,
    load_canonical_taxonomy,
    load_taxonomy,
    trainable_ids,
)

__all__ = [
    "AntiPattern",
    "Taxonomy",
    "TaxonomyError",
    "load_taxonomy",
    "load_canonical_taxonomy",
    "trainable_ids",
]
