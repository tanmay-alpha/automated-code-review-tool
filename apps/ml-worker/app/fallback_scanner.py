"""
automated-code-review-tool — rule-based anti-pattern fallback scanner.

Used when the CodeBERT model is unavailable (e.g., ``MODEL_NAME=none``).
Runs zero-cost regex checks over the **added** lines of a diff and produces
findings in the same schema the rest of the pipeline expects.

**Only added lines are scanned.** Removed lines are not new problems.

Supported rules
---------------
SECURITY_HARDCODED_SECRET  — literal strings matching API-key patterns
SECURITY_SQL_INJECTION     — string concatenation inside execute() calls
RELIABILITY_BROAD_EXCEPTION — bare ``except:`` or ``except Exception:``
PERFORMANCE_QUADRATIC_LOOP  — nested loop depth ≥ 2 in Python/Java
READABILITY_MAGIC_NUMBER    — unexplained numeric literals in added code
READABILITY_LONG_METHOD     — single-line additions > 200 chars
MAINTAINABILITY_PRINT_STATEMENT             — bare ``print(`` in Python, ``console.log`` in JS/TS
MAINTAINABILITY_COMMENTED_CODE — blocks of commented-out code

The scanner is intentionally conservative: it favours precision over recall.

Language support: Python, JavaScript, TypeScript, Java.
"""
from __future__ import annotations

import re
from collections.abc import Callable
from typing import List

from app.model import compute_quality_score
from app.schemas import Finding, ReviewResponse


# ---------------------------------------------------------------------------
# Regex patterns for each rule
# ---------------------------------------------------------------------------

# Hardcoded secret: quoted string ≥ 32 chars that looks like a key/token.
# Matches things like: API_KEY = "sk_live_abc...",  token: "ghp_..."
_SECRET_RE = re.compile(
    r'(?i)(api_key|api_token|apikey|secret_key|private_key|'
    r'access_key|auth_token|password)\s*[=:]\s*["\'][A-Za-z0-9_\-]{32,}["\']'
)

# SQL injection: execute( with + concatenation.
_SQL_CONCAT_RE = re.compile(r'execute\s*\(.*\+.*\)', re.IGNORECASE)

# Broad exception: bare ``except:`` or ``except Exception:``.
_BROAD_EXC_RE = re.compile(r'^\s*except\s*(Exception\s*|$)', re.MULTILINE | re.IGNORECASE)

# Python print statement.
_PY_PRINT_RE = re.compile(r'^\s*print\s*\(', re.MULTILINE)

# JS/TS console.log.
_JS_CONSOLE_RE = re.compile(r'console\.log\s*\(', re.IGNORECASE)

# Commented-out code block (≥ 3 lines starting with # or //).
_COMMENTED_CODE_RE = re.compile(
    r'(?m)^(?:#{1,2}|\/\/)\s+.+\n(?:^(?:#{1,2}|\/\/)\s+.+\n){2,}',
)

# Magic number: standalone numeric literal (not 0/1) on a code line.
_MAGIC_NUMBER_RE = re.compile(
    r'[^A-Za-z_"\']\b(\d{2,})\b[^A-Za-z_"\']'
)

# Nested loop detection: tracks indentation depth of ``for`` / ``while``.
_LOOP_RE = re.compile(r'^\s*(?:for|while)\s+', re.MULTILINE)

# Java try-catch without specific exception type.
_JAVA_CATCH_RE = re.compile(
    r'catch\s*\(\s*(?:Exception|Throwable|\.\.\.\s*\w+)\s*\)',
    re.IGNORECASE,
)


# ---------------------------------------------------------------------------
# Finding metadata per rule
# ---------------------------------------------------------------------------

_RULE_CONFIG: dict[str, dict] = {
    "SECURITY_HARDCODED_SECRET": {
        "severity": "critical",
        "confidence": 0.70,
        "explanation": (
            "Possible API key or secret token hard-coded in source. "
            "Rotate the credential immediately and load it from an environment "
            "variable or a secrets manager."
        ),
    },
    "SECURITY_SQL_INJECTION": {
        "severity": "major",
        "confidence": 0.65,
        "explanation": (
            "String concatenation inside a database execute() call risks SQL "
            "injection. Use parameterised queries or an ORM."
        ),
    },
    "RELIABILITY_BROAD_EXCEPTION": {
        "severity": "major",
        "confidence": 0.75,
        "explanation": (
            "Catching a broad exception (or bare except) swallows SystemExit, "
            "KeyboardInterrupt, and unexpected errors. Catch specific "
            "exceptions instead."
        ),
    },
    "PERFORMANCE_QUADRATIC_LOOP": {
        "severity": "major",
        "confidence": 0.60,
        "explanation": (
            "Nested loops with O(n^2) or worse complexity risk performance "
            "degradation. Consider a lookup table, set, or vectorised approach."
        ),
    },
    "READABILITY_MAGIC_NUMBER": {
        "severity": "minor",
        "confidence": 0.55,
        "explanation": (
            "Unexplained numeric literal in code — extract it into a named "
            "constant so its purpose is clear."
        ),
    },
    "READABILITY_LONG_METHOD": {
        "severity": "minor",
        "confidence": 0.40,
        "explanation": (
            "A single added line exceeds 200 characters, which may indicate a "
            "long or complex statement worth refactoring."
        ),
    },
    "MAINTAINABILITY_PRINT_STATEMENT": {
        "severity": "minor",
        "confidence": 0.65,
        "explanation": (
            "Direct print or console output should use structured logging "
            "(logging module / winston / pino) so output is controllable in "
            "production."
        ),
    },
    "MAINTAINABILITY_COMMENTED_CODE": {
        "severity": "minor",
        "confidence": 0.60,
        "explanation": (
            "Commented-out code should be removed — version control preserves "
            "the old logic if you need to revert."
        ),
    },
}


# ---------------------------------------------------------------------------
# Per-rule detectors
# ---------------------------------------------------------------------------

def _check_hardcoded_secret(added: list[str]) -> list[tuple[int, int]]:
    hits: list[tuple[int, int]] = []
    for i, line in enumerate(added, 1):
        if _SECRET_RE.search(line):
            hits.append((i, i))
    return hits


def _check_sql_injection(added: list[str]) -> list[tuple[int, int]]:
    hits: list[tuple[int, int]] = []
    for i, line in enumerate(added, 1):
        if _SQL_CONCAT_RE.search(line):
            hits.append((i, i))
    return hits


def _check_broad_exception(added: list[str]) -> list[tuple[int, int]]:
    hits: list[tuple[int, int]] = []
    for i, line in enumerate(added, 1):
        if _BROAD_EXC_RE.search(line):
            hits.append((i, i))
    return hits


def _check_nested_loops(added: list[str]) -> list[tuple[int, int]]:
    """Flag when nested loop depth ≥ 2 is found in added lines."""
    max_depth = 0
    current_depth = 0
    start_line = 0
    hits: list[tuple[int, int]] = []
    for i, line in enumerate(added, 1):
        if _LOOP_RE.match(line):
            current_depth += 1
            if current_depth == 2 and max_depth < 2:
                max_depth = 2
                start_line = i
        elif line.strip() == "" or line.startswith("#"):
            continue
        else:
            if current_depth > max_depth:
                max_depth = current_depth
            current_depth = 0
    if max_depth >= 2:
        hits.append((start_line, start_line + max_depth - 1))
    return hits


def _check_magic_number(added: list[str]) -> list[tuple[int, int]]:
    hits: list[tuple[int, int]] = []
    for i, line in enumerate(added, 1):
        for m in _MAGIC_NUMBER_RE.finditer(line):
            val = m.group(1)
            if val != "0" and val != "1":
                hits.append((i, i))
                break  # one finding per line
    return hits


def _check_print_to_stdout(added: list[str], language: str) -> list[tuple[int, int]]:
    hits: list[tuple[int, int]] = []
    if language in ("python", "unknown", None):
        for i, line in enumerate(added, 1):
            if _PY_PRINT_RE.match(line):
                hits.append((i, i))
    if language in ("javascript", "typescript", "unknown", None):
        for i, line in enumerate(added, 1):
            if _JS_CONSOLE_RE.search(line):
                hits.append((i, i))
    return hits


def _check_commented_code(added: list[str]) -> list[tuple[int, int]]:
    hits: list[tuple[int, int]] = []
    block = "\n".join(added)
    for m in _COMMENTED_CODE_RE.finditer(block):
        start = block[: m.start()].count("\n") + 1
        end = start + m.group(0).count("\n") - 1
        hits.append((start, end))
    return hits


def _check_long_lines(added: list[str]) -> list[tuple[int, int]]:
    hits: list[tuple[int, int]] = []
    for i, line in enumerate(added, 1):
        if len(line.rstrip()) > 200:
            hits.append((i, i))
    return hits


def _check_java_broad_catch(added: list[str]) -> list[tuple[int, int]]:
    hits: list[tuple[int, int]] = []
    for i, line in enumerate(added, 1):
        if _JAVA_CATCH_RE.search(line):
            hits.append((i, i))
    return hits


# ---------------------------------------------------------------------------
# Main entry point
# ---------------------------------------------------------------------------

def fallback_scan(diff: str, language: str = "unknown") -> ReviewResponse:
    """Run rule-based anti-pattern detection on a diff text.

    Only **added** lines (lines starting with ``+``) are scanned.
    Removed lines are not new problems and are ignored.

    Args:
        diff: Unified diff text.
        language: Primary language hint ("python", "javascript", "typescript",
            "java", or "unknown").
    """
    findings: List[Finding] = []

    added, _removed = _split_added_removed(diff)

    if not added:
        return ReviewResponse(
            findings=[],
            qualityScore=compute_quality_score([]),
            processingTimeMs=0,
            windowsProcessed=1,
        )

    # Dispatch to rule checks.
    rule_checks: list[tuple[str, Callable[[], list[tuple[int, int]]]]] = [
        ("SECURITY_HARDCODED_SECRET", lambda: _check_hardcoded_secret(added)),
        ("SECURITY_SQL_INJECTION", lambda: _check_sql_injection(added)),
        ("RELIABILITY_BROAD_EXCEPTION", lambda: _check_broad_exception(added)),
        ("PERFORMANCE_QUADRATIC_LOOP", lambda: _check_nested_loops(added)),
        ("READABILITY_MAGIC_NUMBER", lambda: _check_magic_number(added)),
        ("READABILITY_LONG_METHOD", lambda: _check_long_lines(added)),
        ("MAINTAINABILITY_PRINT_STATEMENT", lambda: _check_print_to_stdout(added, language)),
        ("MAINTAINABILITY_COMMENTED_CODE", lambda: _check_commented_code(added)),
    ]

    if language == "java":
        rule_checks.append(
            ("RELIABILITY_BROAD_EXCEPTION", lambda: _check_java_broad_catch(added))
        )

    seen_ranges: set[tuple] = set()  # deduplicate overlapping ranges

    for rule_id, check_fn in rule_checks:
        hits = check_fn()
        cfg = _RULE_CONFIG[rule_id]
        for line_start, line_end in hits:
            key = (rule_id, line_start, line_end)
            if key in seen_ranges:
                continue
            seen_ranges.add(key)
            findings.append(
                Finding(
                    lineStart=line_start,
                    lineEnd=line_end,
                    antiPattern=rule_id,
                    category=rule_id.split("_", 1)[0],  # SECURITY, PERFORMANCE, etc.
                    severity=cfg["severity"],
                    confidence=cfg["confidence"],
                    explanation=cfg["explanation"],
                )
            )

    return ReviewResponse(
        findings=findings,
        qualityScore=compute_quality_score(findings),
        processingTimeMs=0,
        windowsProcessed=1,
    )


def _split_added_removed(diff: str) -> tuple[list[str], list[str]]:
    """Split a unified diff into added and removed lines (excluding headers).

    Returns ``(added_lines, removed_lines)`` where each entry is the line
    content **without** the leading ``+`` / ``-`` prefix.

    Lines like ``+++ b/foo.py`` or ``--- a/foo.py`` are excluded.
    Hunk headers and ``diff --git`` lines are excluded.
    Context lines (no prefix) are excluded from both lists.
    """
    added: list[str] = []
    removed: list[str] = []
    in_hunk = False
    for raw_line in diff.splitlines():
        # Detect hunk start — everything after is content.
        if raw_line.startswith("@@"):
            in_hunk = True
            continue
        if raw_line.startswith("diff "):
            in_hunk = False
            continue
        if raw_line.startswith("index "):
            continue
        if raw_line.startswith("---") or raw_line.startswith("+++"):
            continue
        if raw_line.startswith("\\"):
            # "\ No newline at end of file" marker — skip.
            continue
        if not in_hunk:
            continue
        if raw_line.startswith("+"):
            added.append(raw_line[1:])
        elif raw_line.startswith("-"):
            removed.append(raw_line[1:])
        # Context lines (no prefix) are ignored.
    return added, removed
