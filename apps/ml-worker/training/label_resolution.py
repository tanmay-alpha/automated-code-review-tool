"""Deterministic, provenance-preserving resolution of per-label evidence."""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

TRUST_ORDER = (
    "human_adjudicated",
    "human_single",
    "finding_feedback",
    "import",
    "fallback",
    "model",
)
AUTOMATED_TRUST = frozenset({"fallback", "model"})


class Resolution(Enum):
    POSITIVE = "positive"
    NEGATIVE = "negative"
    UNCERTAIN_WEAK = "uncertain_weak"
    CONFLICT = "conflict"
    UNREVIEWED = "unreviewed"


@dataclass(frozen=True)
class AnnotationEvidence:
    annotation_id: str
    trust_level: str
    label: str
    reviewer_id: str | None
    source: str
    resolution_state: str = "active"
    is_adjudicated: bool = False


@dataclass(frozen=True)
class ReviewState:
    review_id: str
    review_status: str
    clean_confirmed: bool
    reviewed_labels: tuple[str, ...]
    reviewer_id: str | None = None


@dataclass(frozen=True)
class ResolutionResult:
    resolution: Resolution
    winning_trust: str | None
    annotation_ids: tuple[str, ...]
    conflict_reason: str | None = None


def resolve_label(evidence: list[AnnotationEvidence]) -> ResolutionResult:
    active_by_id = {
        item.annotation_id: item
        for item in evidence
        if item.resolution_state == "active" and item.label in {"positive", "negative", "uncertain"}
    }
    active = list(active_by_id.values())
    if not active:
        return ResolutionResult(Resolution.UNREVIEWED, None, (), "no active evidence")

    for trust in TRUST_ORDER:
        group = [item for item in active if item.trust_level == trust]
        if not group:
            continue
        annotation_ids = tuple(sorted(item.annotation_id for item in group))
        labels = {item.label for item in group if item.label != "uncertain"}
        if trust in AUTOMATED_TRUST:
            return ResolutionResult(
                Resolution.UNCERTAIN_WEAK,
                trust,
                annotation_ids,
                "automated evidence is not gold",
            )
        if labels == {"positive", "negative"}:
            return ResolutionResult(
                Resolution.CONFLICT,
                trust,
                annotation_ids,
                f"contradictory active evidence at {trust}",
            )
        if labels == {"positive"}:
            return ResolutionResult(Resolution.POSITIVE, trust, annotation_ids)
        if labels == {"negative"}:
            return ResolutionResult(Resolution.NEGATIVE, trust, annotation_ids)
        return ResolutionResult(
            Resolution.UNCERTAIN_WEAK,
            trust,
            annotation_ids,
            f"uncertain evidence at {trust}",
        )

    return ResolutionResult(Resolution.UNREVIEWED, None, (), "unknown trust levels")


def clean_review_evidence(
    reviews: list[ReviewState], anti_pattern_id: str
) -> tuple[str, ...]:
    """Return review IDs that explicitly support a negative label."""
    return tuple(
        sorted(
            review.review_id
            for review in reviews
            if review.review_status == "complete"
            and review.clean_confirmed
            and anti_pattern_id in review.reviewed_labels
        )
    )
