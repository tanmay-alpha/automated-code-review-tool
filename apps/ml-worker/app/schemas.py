"""
CodeLens — Pydantic v2 request/response schemas (Issue #9).

Defines the wire contract for /ml/review and /ml/health. All field
constraints (length, literal types) are enforced by FastAPI at the
edge, so the rest of the code can trust the types.
"""
from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field


# ----------------------------------------------------------------------
# Requests
# ----------------------------------------------------------------------
class ReviewRequest(BaseModel):
    """Body for POST /ml/review."""

    diff: str = Field(..., min_length=1, description="Unified diff text to analyze")
    language: Literal["python", "javascript", "java", "unknown"] = Field(
        "unknown", description="Primary language of the diff"
    )
    mode: Literal["diff", "file"] = Field(
        "diff", description="'diff' = analyze only the changed lines; 'file' = full file context"
    )


# ----------------------------------------------------------------------
# Findings
# ----------------------------------------------------------------------
class Finding(BaseModel):
    """A single code-review issue surfaced by the model."""

    lineStart: int | None = Field(None, description="First line in the file (1-indexed)")
    lineEnd: int | None = Field(None, description="Last line in the file (1-indexed, inclusive)")
    antiPattern: str = Field(..., description="Machine-readable anti-pattern ID, e.g. PERFORMANCE_N_PLUS_1")
    category: str = Field(..., description="High-level category, e.g. PERFORMANCE")
    severity: Literal["critical", "major", "minor"]
    confidence: float = Field(..., ge=0.0, le=1.0, description="Model sigmoid confidence")
    explanation: str = Field(..., description="Human-readable explanation of the issue")


# ----------------------------------------------------------------------
# Responses
# ----------------------------------------------------------------------
class ReviewResponse(BaseModel):
    """Body returned by POST /ml/review."""

    findings: list[Finding]
    qualityScore: float = Field(..., ge=0.0, le=100.0)
    processingTimeMs: int
    windowsProcessed: int = Field(..., ge=1)


class HealthResponse(BaseModel):
    """Body returned by GET /ml/health."""

    status: str
    modelLoaded: bool
    modelName: str
    device: str
