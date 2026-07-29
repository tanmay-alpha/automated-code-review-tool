"""
automated-code-review-tool — Secret redaction (Phase 1A).

Lightweight, deterministic pre-storage scrubber that replaces leaked
credentials with the placeholder ``<REDACTED_SECRET>``. The pattern set
covers the most common accidental leaks (cloud keys, JWTs, password
assignments). The original value is never logged.

The replacement is intentional and visible (rather than blanking the
line) so downstream detection rules still see a hardcoded-secret
"shape" and can flag the finding.
"""
from __future__ import annotations

import re
from dataclasses import dataclass


REDACTED = "<REDACTED_SECRET>"
REDACTION_VERSION = "v1"


@dataclass(frozen=True)
class RedactionResult:
    text: str
    redaction_count: int
    redaction_version: str


# Common patterns
_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    # AWS access key id
    ("aws_access_key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    # AWS secret access key (40 chars base64-ish after "secret" / "access_key")
    ("aws_secret", re.compile(r"(?i)aws[_\-]?(?:secret)?[_\-]?access[_\-]?key[_\-]?[:=]\s*[\"']?([A-Za-z0-9/+=]{32,})[\"']?")),
    # GitHub personal access token
    ("github_pat", re.compile(r"\bghp_[A-Za-z0-9]{30,}\b")),
    ("github_fine", re.compile(r"\bgithub_pat_[A-Za-z0-9_]{30,}\b")),
    ("github_oauth", re.compile(r"\bgho_[A-Za-z0-9]{30,}\b")),
    # Slack tokens
    ("slack", re.compile(r"\bxox[abprs]-[A-Za-z0-9-]{10,}\b")),
    # Google API key
    ("google_api", re.compile(r"\bAIza[0-9A-Za-z\-_]{35}\b")),
    # Stripe live key
    ("stripe_live", re.compile(r"\bsk_live_[0-9A-Za-z]{20,}\b")),
    # Stripe test key
    ("stripe_test", re.compile(r"\bsk_test_[0-9A-Za-z]{20,}\b")),
    # JWT (3-segment base64url)
    ("jwt", re.compile(r"\beyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\b")),
    # Generic password / token assignment: 'password = "..."', 'api_key: "..."'
    ("generic", re.compile(
        r"(?i)\b(password|passwd|pwd|secret|api[_\-]?key|access[_\-]?key|auth[_\-]?token|token|bearer)\s*[:=]\s*[\"']([^\"'\n]{6,})[\"']"
    )),
    # Cookie: session=...
    ("cookie", re.compile(r"(?i)\b(session|sess|auth)_?(?:id|token)?\s*=\s*[A-Za-z0-9_\-=]{12,}")),
)


def redact_secrets(text: str) -> RedactionResult:
    """Replace detected secrets with ``<REDACTED_SECRET>``.

    The replacement preserves surrounding quotes so file structure stays
    parseable.
    """
    if not text:
        return RedactionResult(text=text, redaction_count=0, redaction_version=REDACTION_VERSION)

    count = 0
    out = text
    for _name, pattern in _PATTERNS:
        out, n = pattern.subn(lambda _m: REDACTED, out)
        count += n
    return RedactionResult(text=out, redaction_count=count, redaction_version=REDACTION_VERSION)


def looks_like_secret_line(line: str) -> bool:
    """Quick heuristic used by ingestion paths to flag suspicious lines."""
    if not line:
        return False
    return any(pattern.search(line) for _name, pattern in _PATTERNS)
