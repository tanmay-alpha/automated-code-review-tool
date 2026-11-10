"""Reviewer-facing guidance attached to every finding.

``explanation`` says why the anti-pattern matters, ``remediation`` how to fix
it, and ``caveat`` when the detector's verdict may not apply. Rule caveats
describe the heuristic's blind spots; model findings share one caveat because
the classifier scores a whole hunk. ``evidence`` is the triggering added line,
secret-redacted and bounded, so a reviewer can judge the finding without
opening the diff.
"""

from __future__ import annotations

from dataclasses import dataclass

from app.secret_redaction import redact_secrets
from app.taxonomy import Taxonomy

EVIDENCE_MAX_CHARS = 200
GUIDANCE_MAX_CHARS = 300
_ELLIPSIS = "…"


@dataclass(frozen=True)
class Guidance:
    explanation: str
    remediation: str
    # None when no rule implements the anti-pattern (model-only label).
    rule_caveat: str | None


MODEL_CAVEAT = (
    "Hunk-level score from a promoted classifier checkpoint. The line range "
    "covers the whole hunk, so confirm which added line is responsible."
)

GUIDANCE: dict[str, Guidance] = {
    "SECURITY_HARDCODED_SECRET": Guidance(
        explanation=(
            "A credential in source code is readable by everyone with repository "
            "access, stays in Git history after it is deleted, and cannot be "
            "rotated without a code change."
        ),
        remediation=(
            "Load the value from an environment variable or a secrets manager, "
            "rotate the exposed credential, and purge it from history if it was pushed."
        ),
        rule_caveat=(
            "Matched on a credential-like name assigned a long quoted literal; "
            "fake test values and public identifiers can also match."
        ),
    ),
    "SECURITY_SQL_INJECTION": Guidance(
        explanation=(
            "SQL text built from runtime values lets crafted input change what the "
            "query does, which can expose or modify data the caller must not reach."
        ),
        remediation=(
            "Pass values as bound parameters (placeholders with a parameter tuple, "
            "PreparedStatement setters, or ORM filters) instead of concatenating or "
            "interpolating them into the SQL string."
        ),
        rule_caveat=(
            "The rule sees string building next to a query call on one line; it "
            "cannot tell whether the value is user-controlled or a trusted constant."
        ),
    ),
    "SECURITY_WEAK_CRYPTO": Guidance(
        explanation=(
            "MD5, SHA-1, DES and RC4 are broken for security use: hashes can be "
            "collided or brute-forced and ciphers decrypted, so the data they "
            "protect is not protected."
        ),
        remediation=(
            "Use SHA-256 or stronger for integrity, bcrypt/scrypt/Argon2/PBKDF2 for "
            "passwords, and AES-GCM or a vetted library for encryption."
        ),
        rule_caveat=(
            "Non-security uses such as cache keys, ETags or checksums of trusted "
            "data can be acceptable; the rule cannot see the purpose."
        ),
    ),
    "PERFORMANCE_N_PLUS_ONE": Guidance(
        explanation=(
            "One database or network call per loop iteration makes latency and "
            "load grow with the data size and is a common cause of slow endpoints."
        ),
        remediation=(
            "Fetch everything in one batched call before the loop (IN query, bulk "
            "endpoint, JOIN, select_related/prefetch_related) or cache the results."
        ),
        rule_caveat=(
            "Detected from a query- or HTTP-like call a few lines below a loop "
            "header; small fixed-size loops or cached calls may be fine."
        ),
    ),
    "PERFORMANCE_QUADRATIC_LOOP": Guidance(
        explanation=(
            "Nested iteration costs O(n*m) time, which becomes noticeable once "
            "inputs grow."
        ),
        remediation=(
            "Index one collection in a dict/map or set first, sort and merge, or "
            "restructure the work into a single pass."
        ),
        rule_caveat=(
            "Detected from a loop nested inside another loop; the rule does not "
            "know collection sizes, and small bounded inputs are harmless."
        ),
    ),
    "RELIABILITY_BROAD_EXCEPTION": Guidance(
        explanation=(
            "Catching every exception hides programming errors and unexpected "
            "failures, so bugs surface later as wrong results instead of clear errors."
        ),
        remediation=(
            "Catch only the exceptions the block expects; log with context and "
            "re-raise, or let anything else propagate."
        ),
        rule_caveat=(
            "Top-level handlers that log and re-raise, or process boundaries that "
            "must not crash, may legitimately catch broadly."
        ),
    ),
    "RELIABILITY_MISSING_TIMEOUT": Guidance(
        explanation=(
            "A network call without a timeout can block a thread or request "
            "indefinitely when the remote side hangs, exhausting capacity during "
            "an outage."
        ),
        remediation=(
            "Set an explicit timeout: requests(..., timeout=...), urlopen(..., "
            "timeout=...), fetch with signal: AbortSignal.timeout(ms), an axios "
            "timeout, or HttpClient.newBuilder().connectTimeout(...)."
        ),
        rule_caveat=(
            "Only direct calls on known HTTP clients are checked; a timeout set on "
            "a shared session, client instance or wrapper is invisible to the rule."
        ),
    ),
    "READABILITY_MAGIC_NUMBER": Guidance(
        explanation=(
            "An unnamed numeric literal hides its intent and invites inconsistent "
            "edits when the same value is used in several places."
        ),
        remediation=(
            "Extract the value into a named constant or configuration entry that "
            "says what it represents."
        ),
        rule_caveat=(
            "Any literal of two or more digits outside a constant definition is "
            "reported; well-known values such as HTTP status codes may be clear "
            "enough in context."
        ),
    ),
    "READABILITY_LONG_METHOD": Guidance(
        explanation=(
            "Very long statements are hard to read, review and diff, and often "
            "pack several operations into one line."
        ),
        remediation=(
            "Split the statement into named intermediate values or helper "
            "functions, and let the formatter wrap long literals."
        ),
        rule_caveat=(
            "Measured as a line longer than 200 characters; long string literals, "
            "URLs and generated code also trigger it."
        ),
    ),
    "MAINTAINABILITY_DUPLICATE_CODE": Guidance(
        explanation=(
            "Copies of the same logic drift apart when one copy is fixed and the "
            "other is not."
        ),
        remediation=(
            "Extract the shared logic into one helper and call it from each place."
        ),
        rule_caveat=None,
    ),
    "MAINTAINABILITY_COMMENTED_CODE": Guidance(
        explanation=(
            "Commented-out code is never compiled or tested, goes stale, and "
            "leaves readers guessing whether it should come back."
        ),
        remediation=(
            "Delete it; version control keeps the old code. Use a feature flag if "
            "it must be switchable."
        ),
        rule_caveat=(
            "Detected from three or more consecutive comment lines that mostly "
            "look like statements; code examples in documentation comments can match."
        ),
    ),
    "MAINTAINABILITY_PRINT_STATEMENT": Guidance(
        explanation=(
            "Print and console output bypasses log levels, structured fields and "
            "redaction, so it is noisy in production and can leak data."
        ),
        remediation=(
            "Use the project logger (logging, SLF4J, pino/winston) at a suitable "
            "level, or remove the debugging output."
        ),
        rule_caveat=(
            "Command-line tools and scripts whose job is to write to stdout use "
            "print legitimately."
        ),
    ),
}


def guidance_for(anti_pattern_id: str) -> Guidance:
    try:
        return GUIDANCE[anti_pattern_id]
    except KeyError:
        raise RuntimeError(f"No reviewer guidance for anti-pattern {anti_pattern_id!r}") from None


def validate_guidance(taxonomy: Taxonomy) -> None:
    """Fail fast when guidance and the canonical taxonomy disagree."""
    ids = set(taxonomy.ids())
    missing = sorted(ids - set(GUIDANCE))
    unknown = sorted(set(GUIDANCE) - ids)
    if missing or unknown:
        raise RuntimeError(
            f"Guidance out of sync with taxonomy: missing={missing} unknown={unknown}"
        )


def _truncate(text: str, limit: int) -> str:
    return text if len(text) <= limit else text[: limit - len(_ELLIPSIS)] + _ELLIPSIS


def evidence_from_line(text: str) -> str:
    """Return the triggering line, secret-redacted, trimmed and bounded.

    Redaction runs on the whole line before truncation so a cut can never leave
    an unrecognised fragment of a secret behind.
    """
    redacted = redact_secrets(text).text
    return _truncate(" ".join(redacted.split()), EVIDENCE_MAX_CHARS)
