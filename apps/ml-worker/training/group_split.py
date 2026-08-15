"""Deterministic grouped splitting with exact and near-duplicate isolation."""

from __future__ import annotations

import hashlib
import random
import re
from collections import defaultdict
from dataclasses import dataclass
from typing import Iterable

from training.dataset_contract import DatasetRecord

SPLITS = ("train", "validation", "test")
DEFAULT_RATIOS = {"train": 0.70, "validation": 0.15, "test": 0.15}
_TOKEN_RE = re.compile(r"[A-Za-z_][A-Za-z_0-9]*|\d+(?:\.\d+)?|[^\s]")
_LINE_COMMENT_RE = re.compile(r"(?m)^\s*(?:#|//).*$")


@dataclass(frozen=True)
class DuplicatePair:
    left_sample_id: str
    right_sample_id: str
    kind: str
    similarity: float

    def to_dict(self) -> dict[str, object]:
        return {
            "left_sample_id": self.left_sample_id,
            "right_sample_id": self.right_sample_id,
            "kind": self.kind,
            "similarity": round(self.similarity, 6),
        }


def normalize_code(text: str) -> str:
    without_comments = _LINE_COMMENT_RE.sub("", text.replace("\r\n", "\n").replace("\r", "\n"))
    return " ".join(without_comments.split())


def token_shingles(text: str, size: int = 5) -> frozenset[str]:
    tokens = _TOKEN_RE.findall(normalize_code(text))
    if not tokens:
        return frozenset()
    if len(tokens) < size:
        return frozenset({"\x1f".join(tokens)})
    return frozenset("\x1f".join(tokens[i : i + size]) for i in range(len(tokens) - size + 1))


def jaccard(left: frozenset[str], right: frozenset[str]) -> float:
    if not left and not right:
        return 1.0
    union = left | right
    return len(left & right) / len(union) if union else 0.0


class _UnionFind:
    def __init__(self, values: Iterable[str]) -> None:
        self.parent = {value: value for value in values}

    def find(self, value: str) -> str:
        parent = self.parent[value]
        if parent != value:
            self.parent[value] = self.find(parent)
        return self.parent[value]

    def union(self, left: str, right: str) -> None:
        lroot, rroot = self.find(left), self.find(right)
        if lroot != rroot:
            low, high = sorted((lroot, rroot))
            self.parent[high] = low


def duplicate_components(
    records: list[DatasetRecord],
    *,
    near_threshold: float = 0.85,
    shingle_size: int = 5,
) -> tuple[dict[str, str], list[DuplicatePair]]:
    """Return sample-to-component IDs and all suspicious duplicate pairs."""
    if not 0 < near_threshold <= 1:
        raise ValueError("near_threshold must be in (0, 1]")
    groups = _UnionFind(record.group_key for record in records)
    normalized_hashes: dict[str, list[int]] = defaultdict(list)
    shingles: list[frozenset[str]] = []
    inverted: dict[str, list[int]] = defaultdict(list)

    for idx, record in enumerate(records):
        normalized = normalize_code(record.raw_hunk)
        normalized_hashes[hashlib.sha256(normalized.encode("utf-8")).hexdigest()].append(idx)
        item_shingles = token_shingles(record.raw_hunk, shingle_size)
        shingles.append(item_shingles)
        for shingle in item_shingles:
            inverted[shingle].append(idx)

    exact_pairs: set[tuple[int, int]] = set()
    for bucket in normalized_hashes.values():
        for pos, left in enumerate(bucket):
            for right in bucket[pos + 1 :]:
                exact_pairs.add((left, right))

    candidates: set[tuple[int, int]] = set()
    for bucket in inverted.values():
        for pos, left in enumerate(bucket):
            for right in bucket[pos + 1 :]:
                candidates.add((left, right))

    pairs: list[DuplicatePair] = []
    for left, right in sorted(exact_pairs | candidates):
        similarity = 1.0 if (left, right) in exact_pairs else jaccard(shingles[left], shingles[right])
        if similarity < near_threshold:
            continue
        kind = "exact" if (left, right) in exact_pairs else "near"
        lrec, rrec = records[left], records[right]
        groups.union(lrec.group_key, rrec.group_key)
        pairs.append(DuplicatePair(lrec.sample_id, rrec.sample_id, kind, similarity))

    roots: dict[str, list[str]] = defaultdict(list)
    for record in records:
        roots[groups.find(record.group_key)].append(record.group_key)
    root_ids = {
        root: "leak-" + hashlib.sha256("\n".join(sorted(set(keys))).encode("utf-8")).hexdigest()[:16]
        for root, keys in roots.items()
    }
    return {
        record.sample_id: root_ids[groups.find(record.group_key)] for record in records
    }, pairs


def assign_group_splits(
    group_keys: Iterable[str],
    seed: int,
    ratios: dict[str, float] | None = None,
    weights: dict[str, int] | None = None,
) -> dict[str, str]:
    ratios = ratios or DEFAULT_RATIOS
    if set(ratios) != set(SPLITS) or any(value <= 0 for value in ratios.values()):
        raise ValueError(f"ratios must contain positive values for exactly {SPLITS}")
    if abs(sum(ratios.values()) - 1.0) > 1e-9:
        raise ValueError("ratios must sum to 1.0")

    unique = sorted(set(group_keys))
    rng = random.Random(seed)
    rng.shuffle(unique)
    effective_weights = {key: max(1, (weights or {}).get(key, 1)) for key in unique}
    total = sum(effective_weights.values())
    targets = {split: ratios[split] * total for split in SPLITS}
    counts = {split: 0 for split in SPLITS}
    assignments: dict[str, str] = {}
    for index, key in enumerate(unique):
        split = (
            SPLITS[index]
            if index < len(SPLITS)
            else max(
                SPLITS,
                key=lambda candidate: (
                    targets[candidate] - counts[candidate],
                    -SPLITS.index(candidate),
                ),
            )
        )
        assignments[key] = split
        counts[split] += effective_weights[key]
    return assignments


def build_split_plan(
    records: list[DatasetRecord],
    *,
    seed: int,
    near_threshold: float = 0.85,
) -> tuple[dict[str, str], dict[str, str], list[DuplicatePair]]:
    components, pairs = duplicate_components(records, near_threshold=near_threshold)
    weights: dict[str, int] = defaultdict(int)
    for component in components.values():
        weights[component] += 1
    component_splits = assign_group_splits(
        components.values(), seed=seed, weights=dict(weights)
    )
    sample_splits = {
        sample_id: component_splits[component]
        for sample_id, component in components.items()
    }
    return sample_splits, component_splits, pairs


def summarize(sample_splits: dict[str, str]) -> dict[str, int]:
    counts = {split: 0 for split in SPLITS}
    for split in sample_splits.values():
        counts[split] += 1
    return counts
