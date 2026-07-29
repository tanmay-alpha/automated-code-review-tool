"""Group-aware split assignment.

The split assignment prevents the same PR (or repository) from
appearing in more than one split. Without this guarantee a model
could trivially memorise PR-level artefacts and look correct on
the test split while failing in production.

Strategy:

* Seeded shuffle of group keys (PRs by default).
* Round-robin bucket assignment with target 70/15/15 ratios.
* Same group → same split.
"""

from __future__ import annotations

import random
from typing import Dict, Iterable


SPLITS = ("train", "validation", "test")
DEFAULT_RATIOS = {"train": 0.70, "validation": 0.15, "test": 0.15}


def assign_group_splits(
    group_keys: Iterable[str],
    seed: int,
    ratios: Dict[str, float] | None = None,
) -> Dict[str, str]:
    """Assign each group key to exactly one split.

    The function is deterministic — same inputs + same seed produce
    the same output.
    """

    ratios = ratios or DEFAULT_RATIOS
    if set(ratios) != set(SPLITS):
        raise ValueError(f"ratios must contain exactly {SPLITS}")
    if any(v <= 0 for v in ratios.values()):
        raise ValueError("split ratios must be positive")
    total = sum(ratios.values())
    if abs(total - 1.0) > 1e-6:
        raise ValueError(f"ratios must sum to 1.0 (got {total})")

    unique = sorted(set(group_keys))
    rng = random.Random(seed)
    rng.shuffle(unique)

    n = len(unique)
    if n == 0:
        return {}

    # Round-robin bucket assignment by cumulative ratio targets.
    targets = {k: int(round(v * n)) for k, v in ratios.items()}
    diff = n - sum(targets.values())
    if diff != 0:
        targets["train"] += diff

    assignment: Dict[str, str] = {}
    idx = 0
    for split in SPLITS:
        for _ in range(targets[split]):
            if idx >= n:
                break
            assignment[unique[idx]] = split
            idx += 1
    return assignment


def summarize(assignments: Dict[str, str]) -> Dict[str, int]:
    counts = {s: 0 for s in SPLITS}
    for v in assignments.values():
        counts[v] = counts.get(v, 0) + 1
    return counts
