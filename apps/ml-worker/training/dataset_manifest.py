"""Immutable dataset-manifest model and deterministic hashing helpers."""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import asdict, dataclass, replace
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
_GIT_SHA_RE = re.compile(r"^[0-9a-f]{40}$")


@dataclass(frozen=True)
class DatasetManifest:
    dataset_name: str
    dataset_version: str
    taxonomy_version: str
    source_git_sha: str
    seed: int
    sample_count: int
    repository_count: int
    split_counts: dict[str, int]
    label_distribution: dict[str, int]
    negative_label_distribution: dict[str, int]
    language_distribution: dict[str, int]
    redaction_versions: list[str]
    created_at: str
    samples_sha256: str
    splits_sha256: str
    frozen: bool = False
    synthetic: bool = False
    near_duplicate_threshold: float = 0.85
    manifest_sha256: str = ""
    schema_version: int = 1

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, raw: dict[str, Any]) -> "DatasetManifest":
        try:
            return cls(**raw)
        except TypeError as exc:
            raise ValueError(f"invalid manifest fields: {exc}") from exc

    def with_hash(self) -> "DatasetManifest":
        return replace(self, manifest_sha256=manifest_hash(self))

    def validate_shape(self) -> None:
        if not self.dataset_name or not self.dataset_version or not self.taxonomy_version:
            raise ValueError("dataset name, version, and taxonomy version are required")
        if self.sample_count < 0 or self.repository_count < 0:
            raise ValueError("manifest counts cannot be negative")
        if self.schema_version != 1:
            raise ValueError("unsupported manifest schema_version")
        if not _GIT_SHA_RE.fullmatch(self.source_git_sha):
            raise ValueError("source_git_sha must be lowercase 40-hex Git SHA")
        if self.sample_count == 0:
            raise ValueError("dataset must contain at least one sample")
        if set(self.split_counts) != {"train", "validation", "test"}:
            raise ValueError("split_counts must contain train, validation, and test")
        if sum(self.split_counts.values()) != self.sample_count:
            raise ValueError("split_counts must sum to sample_count")
        if any(isinstance(value, bool) or value < 0 for value in self.split_counts.values()):
            raise ValueError("split_counts values must be non-negative integers")
        if not 0 < self.near_duplicate_threshold <= 1:
            raise ValueError("near_duplicate_threshold must be in (0, 1]")
        for name, value in {
            "samples_sha256": self.samples_sha256,
            "splits_sha256": self.splits_sha256,
            "manifest_sha256": self.manifest_sha256,
        }.items():
            if value and not _SHA256_RE.fullmatch(value):
                raise ValueError(f"{name} must be lowercase 64-hex SHA-256")


def canonical_json_bytes(payload: dict[str, Any]) -> bytes:
    return json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")


def manifest_hash(manifest: DatasetManifest) -> str:
    payload = manifest.to_dict()
    payload.pop("manifest_sha256", None)
    return hashlib.sha256(canonical_json_bytes(payload)).hexdigest()


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def manifest_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def read_manifest(path: Path) -> DatasetManifest:
    if not path.is_file():
        raise FileNotFoundError(f"manifest not found: {path}")
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        raise ValueError("manifest must be a JSON object")
    manifest = DatasetManifest.from_dict(raw)
    manifest.validate_shape()
    if manifest.manifest_sha256 != manifest_hash(manifest):
        raise ValueError("manifest_sha256 does not match canonical manifest content")
    return manifest


def write_manifest(path: Path, manifest: DatasetManifest) -> DatasetManifest:
    hashed = manifest.with_hash()
    hashed.validate_shape()
    path.write_text(
        json.dumps(hashed.to_dict(), indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return hashed
