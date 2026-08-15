"""Typed records shared by dataset build, validation, training, and evaluation."""

from __future__ import annotations

import json
import re
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Iterable, Literal

from app.preprocessing import SUPPORTED_LANGUAGES

LabelState = Literal["positive", "negative"]
ALLOWED_DATA_USE = frozenset({"allowed_public", "allowed_owner_consent"})
ALLOWED_TRUST_LEVELS = frozenset(
    {
        "human_adjudicated",
        "human_single",
        "finding_feedback",
        "import",
        "fallback",
        "model",
    }
)
_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


@dataclass(frozen=True)
class LabelEvidence:
    anti_pattern_id: str
    state: LabelState
    trust_level: str
    annotation_ids: tuple[str, ...]
    review_ids: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if self.state not in {"positive", "negative"}:
            raise ValueError(f"invalid label state: {self.state!r}")
        if self.trust_level not in ALLOWED_TRUST_LEVELS:
            raise ValueError(f"invalid trust level: {self.trust_level!r}")
        if not self.annotation_ids and not self.review_ids:
            raise ValueError("label evidence must reference an annotation or review")

    @classmethod
    def from_dict(cls, raw: dict[str, Any]) -> "LabelEvidence":
        return cls(
            anti_pattern_id=str(raw["anti_pattern_id"]),
            state=str(raw["state"]),  # type: ignore[arg-type]
            trust_level=str(raw["trust_level"]),
            annotation_ids=tuple(str(v) for v in raw.get("annotation_ids", [])),
            review_ids=tuple(str(v) for v in raw.get("review_ids", [])),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "anti_pattern_id": self.anti_pattern_id,
            "state": self.state,
            "trust_level": self.trust_level,
            "annotation_ids": list(self.annotation_ids),
            "review_ids": list(self.review_ids),
        }


@dataclass(frozen=True)
class DatasetRecord:
    sample_id: str
    repository_id: str
    pull_request_id: str
    commit_sha: str
    file_path: str
    language: str
    old_start: int
    old_count: int
    new_start: int
    new_count: int
    hunk_sha256: str
    content_sha256: str
    group_key: str
    raw_hunk: str
    added_code: str
    context_code: str
    repository_visibility: str
    license_spdx: str | None
    data_use_status: str
    redaction_version: str
    taxonomy_version: str
    labels: tuple[LabelEvidence, ...]
    split: str = ""
    synthetic: bool = False

    def __post_init__(self) -> None:
        if not self.sample_id or not self.repository_id or not self.pull_request_id:
            raise ValueError("sample, repository, and pull-request IDs are required")
        if self.language not in SUPPORTED_LANGUAGES:
            raise ValueError(f"unsupported language: {self.language!r}")
        if not _SHA256_RE.fullmatch(self.hunk_sha256):
            raise ValueError("hunk_sha256 must be lowercase 64-hex SHA-256")
        if not _SHA256_RE.fullmatch(self.content_sha256):
            raise ValueError("content_sha256 must be lowercase 64-hex SHA-256")
        if self.data_use_status not in ALLOWED_DATA_USE:
            raise ValueError(f"sample is not approved for ML use: {self.data_use_status}")
        if self.repository_visibility not in {"public", "private", "internal"}:
            raise ValueError("invalid repository visibility")
        if not self.redaction_version:
            raise ValueError("redaction_version is required")
        if not self.raw_hunk.strip() or not self.added_code.strip():
            raise ValueError("raw_hunk and added_code must be non-empty")
        if self.split and self.split not in {"train", "validation", "test"}:
            raise ValueError(f"invalid split: {self.split!r}")
        if not self.labels:
            raise ValueError("record must contain explicit positive or negative evidence")

    @property
    def positive_labels(self) -> tuple[str, ...]:
        return tuple(item.anti_pattern_id for item in self.labels if item.state == "positive")

    @property
    def negative_labels(self) -> tuple[str, ...]:
        return tuple(item.anti_pattern_id for item in self.labels if item.state == "negative")

    def label_vectors(self, label_order: tuple[str, ...]) -> tuple[list[float], list[float]]:
        states = {item.anti_pattern_id: item.state for item in self.labels}
        targets: list[float] = []
        mask: list[float] = []
        for label in label_order:
            state = states.get(label)
            targets.append(1.0 if state == "positive" else 0.0)
            mask.append(1.0 if state in {"positive", "negative"} else 0.0)
        return targets, mask

    @classmethod
    def from_dict(cls, raw: dict[str, Any]) -> "DatasetRecord":
        values = dict(raw)
        raw_labels = raw.get("labels", [])
        if not isinstance(raw_labels, list):
            raise ValueError("labels must be a list")
        values["labels"] = tuple(LabelEvidence.from_dict(v) for v in raw_labels)
        return cls(**values)

    def to_dict(self) -> dict[str, Any]:
        payload = asdict(self)
        payload["labels"] = [item.to_dict() for item in self.labels]
        return payload


def read_records(path: Path) -> list[DatasetRecord]:
    if not path.is_file():
        raise FileNotFoundError(f"dataset samples not found: {path}")
    records: list[DatasetRecord] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            records.append(DatasetRecord.from_dict(json.loads(line)))
        except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
            raise ValueError(f"invalid dataset record at {path}:{line_number}: {exc}") from exc
    return records


def write_records(path: Path, records: Iterable[DatasetRecord]) -> None:
    ordered = sorted(records, key=lambda item: item.sample_id)
    text = "".join(
        json.dumps(item.to_dict(), sort_keys=True, separators=(",", ":")) + "\n"
        for item in ordered
    )
    path.write_text(text, encoding="utf-8")
