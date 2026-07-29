"""Dataset manifest model and hashing.

A dataset manifest is a JSON document that fully describes a
generated dataset. The manifest is the single source of truth
used to:

* detect content drift,
* reject mismatched checkpoints,
* reproduce training runs,
* audit dataset contents.

Manifests MUST NOT contain secrets, raw code, or credentials.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from typing import Any, Dict


@dataclass(frozen=True)
class DatasetManifest:
    dataset_name: str
    dataset_version: str
    taxonomy_version: str
    seed: int
    created_at: str
    source_commit: str
    sample_count: int
    split_counts: Dict[str, int]
    label_distribution: Dict[str, int]
    repository_counts: Dict[str, int]
    duplicate_count: int
    manifest_sha256: str = ""

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


def manifest_hash(manifest: DatasetManifest) -> str:
    """Stable SHA-256 over the canonical JSON encoding of the manifest.

    The hash excludes ``manifest_sha256`` itself to make the operation
    idempotent.
    """

    payload = manifest.to_dict()
    payload["manifest_sha256"] = ""
    raw = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


def manifest_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def empty_manifest() -> DatasetManifest:
    return DatasetManifest(
        dataset_name="",
        dataset_version="",
        taxonomy_version="",
        seed=0,
        created_at=manifest_now(),
        source_commit="",
        sample_count=0,
        split_counts={"train": 0, "validation": 0, "test": 0},
        label_distribution={},
        repository_counts={},
        duplicate_count=0,
        manifest_sha256="",
    )
