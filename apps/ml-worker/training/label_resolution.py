"""Deterministic label-resolution policy.

Given all annotation evidence for one sample and one anti-pattern,
this module produces a resolved label. The policy is:

1. human_adjudicated > human_single > finding_feedback > import_verified > fallback > model
2. Multiple agreeing human reviewers produce resolved human evidence.
3. Conflicting humans produce ``conflict``.
4. One human decision overrides automated weak labels.
5. Fallback/model agreement without human review remains ``uncertain_weak``.
6. No evidence remains ``unreviewed``.
7. Completed clean review may produce explicit negatives for reviewed trainable labels.

No confidence averaging is used to resolve conflicts.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass
from enum import Enum
from typing import List, Optional

log = logging.getLogger("label_resolution")

TRUST_ORDER = [
    "human_adjudicated",
    "human_single",
    "finding_feedback",
    "import_verified",
    "fallback",
    "model",
]


class Resolution(Enum):
    POSITIVE = "positive"
    NEGATIVE = "negative"
    UNCERTAIN_WEAK = "uncertain_weak"
    CONFLICT = "conflict"
    UNREVIEWED = "unreviewed"


@dataclass(frozen=True)
class AnnotationEvidence:
    """One piece of annotation evidence for a sample + anti-pattern."""
    annotation_id: str
    trust_level: str
    label: str  # "positive", "negative", "uncertain"
    reviewer_id: Optional[str]
    source: str
    is_adjudicated: bool = False


@dataclass(frozen=True)
class ReviewState:
    """Sample review completion state."""
    review_status: str  # unreviewed, in_progress, complete, needs_adjudication
    clean_confirmed: bool
    reviewer_count: int = 0


@dataclass(frozen=True)
class ResolutionResult:
    resolution: Resolution
    winning_trust: Optional[str]
    evidence_count: int
    conflict_reason: Optional[str]


def resolve_label(
    evidence: List[AnnotationEvidence],
    review_state: Optional[ReviewState] = None,
    trainable: bool = True,
) -> ResolutionResult:
    """Resolve a label from all evidence for one sample + anti-pattern.

    :param evidence: All annotation evidence for this (sample, anti-pattern).
    :param review_state: Optional sample review completion state.
    :param trainable: Whether this anti-pattern is in the trainable set.
    :return: ResolutionResult with the resolved label and metadata.
    """
    if not evidence:
        return ResolutionResult(
            resolution=Resolution.UNREVIEWED,
            winning_trust=None,
            evidence_count=0,
            conflict_reason="no evidence",
        )

    # Group evidence by trust level.
    trust_groups: dict[str, List[AnnotationEvidence]] = {}
    for e in evidence:
        trust_groups.setdefault(e.trust_level, []).append(e)

    # Check for human conflicts at each human level.
    human_levels = {"human_adjudicated", "human_single", "finding_feedback"}
    for level in human_levels:
        group = trust_groups.get(level, [])
        if len(group) >= 2:
            positives = [e for e in group if e.label == "positive"]
            negatives = [e for e in group if e.label == "negative"]
            if positives and negatives:
                # Different reviewers disagree.
                if level == "human_adjudicated":
                    # Adjudicated takes precedence; use the adjudicated label.
                    adjudicated = [e for e in group if e.is_adjudicated]
                    if adjudicated:
                        return ResolutionResult(
                            resolution=Resolution.POSITIVE if adjudicated[0].label == "positive" else Resolution.NEGATIVE,
                            winning_trust=level,
                            evidence_count=len(group),
                            conflict_reason=None,
                        )
                return ResolutionResult(
                    resolution=Resolution.CONFLICT,
                    winning_trust=level,
                    evidence_count=len(group),
                    conflict_reason=f"{len(positives)} positive vs {len(negatives)} negative at {level}",
                )

    # Find the highest-trust non-conflicting evidence.
    for trust in TRUST_ORDER:
        group = trust_groups.get(trust, [])
        if not group:
            continue
        # All evidence at this level agrees (conflict already checked above).
        representative = group[0]
        if representative.label == "positive":
            return ResolutionResult(
                resolution=Resolution.POSITIVE,
                winning_trust=trust,
                evidence_count=len(group),
                conflict_reason=None,
            )
        if representative.label == "negative":
            return ResolutionResult(
                resolution=Resolution.NEGATIVE,
                winning_trust=trust,
                evidence_count=len(group),
                conflict_reason=None,
            )
        return ResolutionResult(
            resolution=Resolution.UNCERTAIN_WEAK,
            winning_trust=trust,
            evidence_count=len(group),
            conflict_reason="uncertain label at " + trust,
        )

    return ResolutionResult(
        resolution=Resolution.UNREVIEWED,
        winning_trust=None,
        evidence_count=len(evidence),
        conflict_reason="no resolvable label",
    )


def is_clean_negative(
    review_state: Optional[ReviewState],
    trainable_labels: List[str],
    reviewed_labels: List[str],
) -> bool:
    """A sample is a clean negative only when review is complete AND
    clean_confirmed is true AND all trainable labels were reviewed."""
    if review_state is None:
        return False
    if review_state.review_status != "complete":
        return False
    if not review_state.clean_confirmed:
        return False
    # All trainable labels must be in the reviewed set.
    return all(label in reviewed_labels for label in trainable_labels)
