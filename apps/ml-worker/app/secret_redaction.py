"""
Deterministic secret redaction for persisted ML data.

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
    # Private key blocks (PEM format)
    ("private_key", re.compile(r"-----BEGIN [A-Z0-9 ]+PRIVATE KEY-----[\s\S]*?-----END [A-Z0-9 ]+PRIVATE KEY-----")),
    # Database URL with password
    ("db_url_password", re.compile(r"(?i)\b([a-z0-9+.\-]+://[^:]+:)([^@\s\"'\n]+)(@[^:\s\"'\n/]+)")),
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
        r"(?i)\b(password|passwd|pwd|secret|api[_\-]?key|access[_\-]?key|auth[_\-]?token|token|bearer)\s*[:=]\s*([\"'])([^\"'\n]{4,})\2"
    )),
    # Cookie: session=...
    ("cookie", re.compile(r"(?i)\b(session|sess|auth)_?(?:id|token)?\s*=\s*[A-Za-z0-9_\-=]{12,}")),
)


def _is_placeholder_or_doc(val: str) -> bool:
    """Check if value is a placeholder, env var, or documentation example."""
    if not val:
        return False
    v = val.strip().lower()
    if REDACTED in val or "${" in val or val.startswith("$"):
        return True
    if val.startswith("<") and val.endswith(">"):
        return True
    # Explicit placeholder markers for generic values
    if v in ("sk_test_xxx", "your_api_key", "your_secret", "xxx", "placeholder", "dummy", "<redacted>"):
        return True
    if v.startswith("your_") or v.startswith("<"):
        return True
    return False


def redact_secrets(text: str) -> RedactionResult:
    """Replace detected secrets with ``<REDACTED_SECRET>``.

    The replacement preserves surrounding quotes so file structure stays
    parseable.
    """
    if not text:
        return RedactionResult(text=text, redaction_count=0, redaction_version=REDACTION_VERSION)

    count = 0
    out = text
    for name, pattern in _PATTERNS:
        if name == "db_url_password":
            def repl_db(m: re.Match[str]) -> str:
                nonlocal count
                prefix, pass_val, suffix = m.group(1), m.group(2), m.group(3)
                if _is_placeholder_or_doc(pass_val):
                    return m.group(0)
                count += 1
                return f"{prefix}{REDACTED}{suffix}"
            out = pattern.sub(repl_db, out)
        elif name == "generic":
            def repl_gen(m: re.Match[str]) -> str:
                nonlocal count
                key_part, quote, val = m.group(1), m.group(2), m.group(3)
                if _is_placeholder_or_doc(val):
                    return m.group(0)
                count += 1
                return f"{key_part} = {quote}{REDACTED}{quote}"
            out = pattern.sub(repl_gen, out)
        else:
            def repl_std(m: re.Match[str]) -> str:
                nonlocal count
                val = m.group(0)
                if _is_placeholder_or_doc(val):
                    return val
                count += 1
                return REDACTED
            out = pattern.sub(repl_std, out)

    return RedactionResult(text=out, redaction_count=count, redaction_version=REDACTION_VERSION)


def looks_like_secret_line(line: str) -> bool:
    """Quick heuristic used by ingestion paths to flag suspicious lines."""
    if not line:
        return False
    return any(pattern.search(line) for _name, pattern in _PATTERNS)
