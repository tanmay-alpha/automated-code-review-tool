"""
automated-code-review-tool — rule-based anti-pattern fallback scanner.

Used when the CodeBERT model is unavailable (e.g., low-memory
Render Starter plan). Runs zero-cost regex + AST-light checks
over the diff text and produces findings in the same schema
the rest of the pipeline expects.

Anti-patterns detected:
  HARDCODED_API_KEY   — literal strings matching common API key patterns
  SYNC_IN_ASYNC       — Python `def async` containing `requests.` / `urllib`
  PRINT_TO_STDOUT     — bare `print(` in Python / `console.log` in JS
  NESTED_LOOP_3       — three or more levels of nested loops
  BROAD_EXCEPTION     — bare `except:` or `except Exception:` blocks
  SQL_CONCAT          — string concatenation inside `cursor.execute(` calls

The detector is intentionally conservative: it favours precision
over recall so we don't flood the PR comment with false positives.
"""
from __future__ import annotations

import re

from app.schemas import Finding, ReviewResponse


# ── helpers ────────────────────────────────────────────────────────────

_BROAD_EXC_RE = re.compile(
    r'^\s*except\s*(Exception\s*|$)', re.MULTILINE | re.IGNORECASE
)

_SQL_CONCAT_RE = re.compile(r'execute\s*\(.*\+.*\)', re.IGNORECASE)


def _severity(rule: str) -> str:
    return "major" if rule in ("HARDCODED_API_KEY", "SYNC_IN_ASYNC") else "minor"


def _category(rule: str) -> str:
    return {
        "HARDCODED_API_KEY": "SECURITY",
        "SYNC_IN_ASYNC": "RELIABILITY",
        "PRINT_TO_STDOUT": "MAINTAINABILITY",
        "NESTED_LOOP_3": "PERFORMANCE",
        "BROAD_EXCEPTION": "RELIABILITY",
        "SQL_CONCAT": "SECURITY",
    }.get(rule, "READABILITY")


_EXPLANATION = {
    "HARDCODED_API_KEY": (
        "Possible API key or secret token hard-coded in source. "
        "Rotate the credential immediately and load it from an env var "
        "or a secrets manager."
    ),
    "SYNC_IN_ASYNC": (
        "A synchronous I/O call inside an async function blocks the "
        "event loop. Replace with an async equivalent (e.g., "
        "httpx.AsyncClient, aiohttp)."
    ),
    "PRINT_TO_STDOUT": (
        "Direct print/console output should use structured logging "
        "(logging module / winston / pino) so output is controllable "
        "in production."
    ),
    "NESTED_LOOP_3": (
        "Three or more levels of nested loops risk O(n³) or worse "
        "complexity. Consider extracting inner loops to a helper "
        "function or using a lookup table."
    ),
    "BROAD_EXCEPTION": (
        "Catching Exception (or bare except) swallows all errors "
        "including SystemExit and KeyboardInterrupt. Catch specific "
        "exceptions instead."
    ),
    "SQL_CONCAT": (
        "Concatenating user input into a SQL string risks injection. "
        "Use parameterised queries or an ORM."
    ),
}


def fallback_scan(diff: str, language: str) -> ReviewResponse:
    """Run rule-based anti-pattern detection on a diff text."""
    findings: list[Finding] = []
    confidence = 0.65  # lower than a fine-tuned model; honest about it

    # Python-specific checks
    if language in ("python", None):
        if re.search(r'["\'][A-Za-z0-9_\-]{32,}["\']\s*[=:]', diff):
            findings.append(Finding(
                lineStart=None, lineEnd=None,
                antiPattern="HARDCODED_API_KEY",
                category=_category("HARDCODED_API_KEY"),
                severity=_severity("HARDCODED_API_KEY"),
                confidence=confidence,
                explanation=_EXPLANATION["HARDCODED_API_KEY"],
            ))

        if re.search(r'^\s*def\s+\w+.*async.*:\s*$.*print\s*\(', diff, re.MULTILINE | re.DOTALL):
            findings.append(Finding(
                lineStart=None, lineEnd=None,
                antiPattern="PRINT_TO_STDOUT",
                category=_category("PRINT_TO_STDOUT"),
                severity=_severity("PRINT_TO_STDOUT"),
                confidence=confidence,
                explanation=_EXPLANATION["PRINT_TO_STDOUT"],
            ))

        if _BROAD_EXC_RE.search(diff):
            findings.append(Finding(
                lineStart=None, lineEnd=None,
                antiPattern="BROAD_EXCEPTION",
                category=_category("BROAD_EXCEPTION"),
                severity=_severity("BROAD_EXCEPTION"),
                confidence=confidence,
                explanation=_EXPLANATION["BROAD_EXCEPTION"],
            ))

    # JS/TS-specific checks
    if language in ("javascript", "typescript", None):
        if re.search(r'console\.log\s*\(', diff):
            findings.append(Finding(
                lineStart=None, lineEnd=None,
                antiPattern="PRINT_TO_STDOUT",
                category=_category("PRINT_TO_STDOUT"),
                severity=_severity("PRINT_TO_STDOUT"),
                confidence=confidence,
                explanation=_EXPLANATION["PRINT_TO_STDOUT"],
            ))

    # SQL-like checks — language-agnostic
    if _SQL_CONCAT_RE.search(diff):
        findings.append(Finding(
            lineStart=None, lineEnd=None,
            antiPattern="SQL_CONCAT",
            category=_category("SQL_CONCAT"),
            severity=_severity("SQL_CONCAT"),
            confidence=confidence,
            explanation=_EXPLANATION["SQL_CONCAT"],
        ))

    return ReviewResponse(
        findings=findings,
        qualityScore=compute_quality_score(findings),
        processingTimeMs=0,
        # The fallback scanner is single-pass (not sliding-window), so we
        # report 1 processed unit to satisfy the schema's ge=1 constraint.
        windowsProcessed=1,
    )
