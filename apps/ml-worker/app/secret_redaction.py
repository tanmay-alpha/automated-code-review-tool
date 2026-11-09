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


# Scan delimiters once rather than repeatedly backtracking over an unfinished
# PEM block or URL. Missing closing delimiters must remain cheap on source data.
_PRIVATE_KEY_MARKER_RE = re.compile(
    r"-----(BEGIN|END) [A-Z0-9 ]+PRIVATE KEY-----"
)
_URL_SCHEME_RE = re.compile(r"(?i)(?<![a-z0-9+.\-])\b[a-z0-9+.\-]++://")
_URL_PASSWORD_END_RE = re.compile(r"[@\s\"']")
_URL_HOST_RE = re.compile(r"[^:\s\"'/]++")


def _redact_private_keys(text: str) -> tuple[str, int]:
    pieces: list[str] = []
    start: int | None = None
    consumed = 0
    count = 0
    for marker in _PRIVATE_KEY_MARKER_RE.finditer(text):
        if start is None:
            if marker.group(1) == "BEGIN":
                start = marker.start()
            continue
        if marker.group(1) != "END":
            continue
        value = text[start : marker.end()]
        placeholder = _is_placeholder_or_doc(value)
        pieces.extend((text[consumed:start], value if placeholder else REDACTED))
        count += not placeholder
        consumed = marker.end()
        start = None
    pieces.append(text[consumed:])
    return "".join(pieces), count


def _redact_database_urls(text: str) -> tuple[str, int]:
    pieces: list[str] = []
    consumed = 0
    count = 0
    boundary: re.Match[str] | None = None
    for scheme in _URL_SCHEME_RE.finditer(text):
        if scheme.start() < consumed:
            continue
        user_end = text.find(":", scheme.end())
        if user_end <= scheme.end():
            continue
        password_start = user_end + 1
        if boundary is None or boundary.start() < password_start:
            boundary = _URL_PASSWORD_END_RE.search(text, password_start)
        if boundary is None:
            break
        if boundary.start() == password_start or boundary.group() != "@":
            continue
        host = _URL_HOST_RE.match(text, boundary.end())
        if host is None:
            continue
        password = text[password_start : boundary.start()]
        placeholder = _is_placeholder_or_doc(password)
        pieces.extend((
            text[consumed:password_start],
            password if placeholder else REDACTED,
            text[boundary.start() : host.end()],
        ))
        count += not placeholder
        consumed = host.end()
    pieces.append(text[consumed:])
    return "".join(pieces), count

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
    # Generic password / token assignment: 'password = "..."', 'api_key: "..."'.
    # The key may carry a prefix such as DB_PASSWORD or client_secret, and
    # SECRET_KEY / private_key are matched whole because "_key" otherwise ends
    # the name before the separator.
    ("generic", re.compile(
        r"(?i)(?<![A-Za-z0-9])(password|passwd|pwd|secret(?:[_\-]?key)?|private[_\-]?key|api[_\-]?key|access[_\-]?key|auth[_\-]?token|token|bearer)(\s*[:=]\s*)([\"'])([^\"'\n]{4,})\3"
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


_PROVIDER_PATTERNS = tuple(
    pattern for name, pattern in _PATTERNS if name not in {"generic", "cookie"}
)


def is_provider_credential(value: str) -> bool:
    """True when ``value`` has a recognisable provider token format (AWS, GitHub,
    Slack, Google, Stripe, JWT), as opposed to an arbitrary assigned string."""
    return any(
        not _is_placeholder_or_doc(match.group(0))
        for pattern in _PROVIDER_PATTERNS
        for match in pattern.finditer(value)
    )


def redact_secrets(text: str) -> RedactionResult:
    """Replace detected secrets with ``<REDACTED_SECRET>``.

    The replacement preserves surrounding quotes so file structure stays
    parseable.
    """
    if not text:
        return RedactionResult(text=text, redaction_count=0, redaction_version=REDACTION_VERSION)

    out, count = _redact_private_keys(text)
    out, database_count = _redact_database_urls(out)
    count += database_count
    for name, pattern in _PATTERNS:
        if name == "generic":
            def repl_gen(m: re.Match[str]) -> str:
                nonlocal count
                key_part, separator, quote, val = m.group(1), m.group(2), m.group(3), m.group(4)
                if _is_placeholder_or_doc(val):
                    return m.group(0)
                count += 1
                return f"{key_part}{separator}{quote}{REDACTED}{quote}"
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
