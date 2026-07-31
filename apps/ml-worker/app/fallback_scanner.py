"""
automated-code-review-tool — rule-based anti-pattern fallback scanner.

Used when the CodeBERT model is unavailable (e.g., ``MODEL_NAME=none``).
Runs zero-cost regex checks over the **added** lines of each diff hunk and
produces findings mapped to actual file paths, hunk hashes, and 1-indexed
new-file line numbers.

Requirements:
- Preserve destination path for renames
- Skip binary files
- Skip removed-only hunks
- Scan only added lines
- Map each finding to its actual new-file line
- Deduplicate within a hunk
"""
from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass
from typing import List

from app.model import compute_quality_score
from app.schemas import Finding, ReviewResponse


@dataclass
class HunkLine:
    real_line: int
    content: str


@dataclass
class FileHunk:
    file_path: str
    hunk_hash: str
    added_lines: list[HunkLine]
    is_binary: bool = False


# ---------------------------------------------------------------------------
# Regex patterns for rules
# ---------------------------------------------------------------------------

_SECRET_RE = re.compile(
    r'(?i)(api_key|api_token|apikey|secret_key|private_key|'
    r'access_key|auth_token|password)\s*[=:]\s*["\'][A-Za-z0-9_\-]{32,}["\']'
)

_SQL_CONCAT_RE = re.compile(r'execute\s*\(.*\+.*\)', re.IGNORECASE)
_BROAD_EXC_RE = re.compile(r'^\s*except\s*(Exception\s*|$)', re.MULTILINE | re.IGNORECASE)
_PY_PRINT_RE = re.compile(r'^\s*print\s*\(', re.MULTILINE)
_JS_CONSOLE_RE = re.compile(r'console\.log\s*\(', re.IGNORECASE)

_COMMENTED_CODE_RE = re.compile(
    r'(?m)^(?:#{1,2}|\/\/)\s+.+\n(?:^(?:#{1,2}|\/\/)\s+.+\n){2,}',
)

_MAGIC_NUMBER_RE = re.compile(
    r'[^A-Za-z_"\']\b(\d{2,})\b[^A-Za-z_"\']'
)

_LOOP_RE = re.compile(r'^\s*(?:for|while)\s+', re.MULTILINE)

_JAVA_CATCH_RE = re.compile(
    r'catch\s*\(\s*(?:Exception|Throwable|\.\.\.\s*\w+)\s*\)',
    re.IGNORECASE,
)


_RULE_CONFIG: dict[str, dict] = {
    "SECURITY_HARDCODED_SECRET": {
        "severity": "critical",
        "confidence": 0.70,
        "explanation": (
            "Possible API key or secret token hard-coded in source. "
            "Rotate the credential immediately and load it from an environment variable."
        ),
    },
    "SECURITY_SQL_INJECTION": {
        "severity": "major",
        "confidence": 0.65,
        "explanation": (
            "String concatenation inside a database execute() call risks SQL injection. "
            "Use parameterised queries or an ORM."
        ),
    },
    "RELIABILITY_BROAD_EXCEPTION": {
        "severity": "major",
        "confidence": 0.75,
        "explanation": (
            "Catching a broad exception swallows unexpected errors. Catch specific exceptions."
        ),
    },
    "PERFORMANCE_QUADRATIC_LOOP": {
        "severity": "major",
        "confidence": 0.60,
        "explanation": (
            "Nested loops with O(n^2) complexity risk performance degradation."
        ),
    },
    "READABILITY_MAGIC_NUMBER": {
        "severity": "minor",
        "confidence": 0.55,
        "explanation": (
            "Unexplained numeric literal in code — extract into a named constant."
        ),
    },
    "READABILITY_LONG_METHOD": {
        "severity": "minor",
        "confidence": 0.40,
        "explanation": (
            "Single added line exceeds 200 characters."
        ),
    },
    "MAINTAINABILITY_PRINT_STATEMENT": {
        "severity": "minor",
        "confidence": 0.65,
        "explanation": (
            "Direct print statement — use structured logging."
        ),
    },
    "MAINTAINABILITY_COMMENTED_CODE": {
        "severity": "minor",
        "confidence": 0.60,
        "explanation": (
            "Commented-out code block should be removed."
        ),
    },
}


# ---------------------------------------------------------------------------
# Diff Parsing (Hunk-Aware)
# ---------------------------------------------------------------------------

_DIFF_GIT_RE = re.compile(r'^diff --git a/(.*) b/(.*)$')
_HUNK_HEADER_RE = re.compile(r'^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@')


def parse_diff_hunks(diff: str) -> list[FileHunk]:
    """Parse a unified diff into FileHunk objects."""
    hunks: list[FileHunk] = []
    if not diff or not diff.strip():
        return hunks

    lines = diff.splitlines()
    current_file = "unknown"
    is_binary = False
    i = 0
    n = len(lines)

    while i < n:
        line = lines[i]

        # diff --git header
        m_git = _DIFF_GIT_RE.match(line)
        if m_git:
            current_file = m_git.group(2)
            is_binary = False
            i += 1
            continue

        if line.startswith("rename to "):
            current_file = line[10:].strip()
            i += 1
            continue

        if line.startswith("+++ b/"):
            current_file = line[6:].strip()
            i += 1
            continue
        elif line.startswith("+++ /dev/null"):
            current_file = "/dev/null"
            i += 1
            continue

        if line.startswith("Binary files ") or line.startswith("GIT binary patch"):
            is_binary = True
            i += 1
            continue

        # Hunk header
        m_hunk = _HUNK_HEADER_RE.match(line)
        if m_hunk:
            new_start = int(m_hunk.group(3))
            hunk_raw_lines: list[str] = [line]
            added_hunk_lines: list[HunkLine] = []
            curr_new_line = new_start

            i += 1
            while i < n:
                hline = lines[i]
                if hline.startswith("diff --git") or hline.startswith("@@"):
                    break

                hunk_raw_lines.append(hline)

                if hline.startswith("+") and not hline.startswith("+++"):
                    added_hunk_lines.append(HunkLine(real_line=curr_new_line, content=hline[1:]))
                    curr_new_line += 1
                elif hline.startswith("-") and not hline.startswith("---"):
                    pass
                elif not hline.startswith("\\"):
                    curr_new_line += 1

                i += 1

            # Ignore deleted files (/dev/null)
            if current_file != "/dev/null":
                raw_block = "\n".join(hunk_raw_lines)
                h_hash = hashlib.sha256(raw_block.encode("utf-8")).hexdigest()[:16]
                hunks.append(FileHunk(
                    file_path=current_file,
                    hunk_hash=h_hash,
                    added_lines=added_hunk_lines,
                    is_binary=is_binary,
                ))
            continue

        i += 1

    return hunks


# ---------------------------------------------------------------------------
# Per-hunk detectors
# ---------------------------------------------------------------------------

def _scan_hunk_lines(hunk_lines: list[HunkLine], language: str) -> list[tuple[str, int, int]]:
    """Scan added lines of a single hunk. Returns list of (rule_id, line_start, line_end)."""
    hits: list[tuple[str, int, int]] = []
    lines_text = [hl.content for hl in hunk_lines]

    for idx, hl in enumerate(hunk_lines):
        line = hl.content
        line_no = hl.real_line

        if _SECRET_RE.search(line):
            hits.append(("SECURITY_HARDCODED_SECRET", line_no, line_no))
        if _SQL_CONCAT_RE.search(line):
            hits.append(("SECURITY_SQL_INJECTION", line_no, line_no))
        if _BROAD_EXC_RE.search(line):
            hits.append(("RELIABILITY_BROAD_EXCEPTION", line_no, line_no))
        if language == "java" and _JAVA_CATCH_RE.search(line):
            hits.append(("RELIABILITY_BROAD_EXCEPTION", line_no, line_no))

        if language in ("python", "unknown", None) and _PY_PRINT_RE.match(line):
            hits.append(("MAINTAINABILITY_PRINT_STATEMENT", line_no, line_no))
        if language in ("javascript", "typescript", "unknown", None) and _JS_CONSOLE_RE.search(line):
            hits.append(("MAINTAINABILITY_PRINT_STATEMENT", line_no, line_no))

        if len(line.rstrip()) > 200:
            hits.append(("READABILITY_LONG_METHOD", line_no, line_no))

        for m in _MAGIC_NUMBER_RE.finditer(line):
            val = m.group(1)
            if val not in ("0", "1"):
                hits.append(("READABILITY_MAGIC_NUMBER", line_no, line_no))
                break

    # Commented code over hunk
    block = "\n".join(lines_text)
    for m in _COMMENTED_CODE_RE.finditer(block):
        start_idx = block[: m.start()].count("\n")
        end_idx = start_idx + m.group(0).count("\n") - 1
        if start_idx < len(hunk_lines) and end_idx < len(hunk_lines):
            hits.append(("MAINTAINABILITY_COMMENTED_CODE", hunk_lines[start_idx].real_line, hunk_lines[end_idx].real_line))

    # Quadratic loops
    current_depth = 0
    max_depth = 0
    start_line = 0
    for hl in hunk_lines:
        line = hl.content
        if _LOOP_RE.match(line):
            current_depth += 1
            if current_depth == 2 and max_depth < 2:
                max_depth = 2
                start_line = hl.real_line
        elif line.strip() == "" or line.startswith("#"):
            continue
        else:
            if current_depth > max_depth:
                max_depth = current_depth
            current_depth = 0

    if max_depth >= 2 and start_line > 0:
        hits.append(("PERFORMANCE_QUADRATIC_LOOP", start_line, start_line + max_depth - 1))

    return hits


# ---------------------------------------------------------------------------
# Entry Point
# ---------------------------------------------------------------------------

def fallback_scan(diff: str, language: str = "unknown") -> ReviewResponse:
    """Run rule-based anti-pattern detection on a unified diff.

    Processes each file hunk independently and maps findings to the real line number
    and file path.
    """
    findings: List[Finding] = []
    hunks = parse_diff_hunks(diff)
    windows_processed = max(1, len(hunks))

    for hunk in hunks:
        if hunk.is_binary or not hunk.added_lines:
            continue

        hunk_hits = _scan_hunk_lines(hunk.added_lines, language)
        seen_hunk_keys: set[tuple] = set()

        for rule_id, l_start, l_end in hunk_hits:
            key = (rule_id, l_start, l_end)
            if key in seen_hunk_keys:
                continue
            seen_hunk_keys.add(key)

            cfg = _RULE_CONFIG[rule_id]
            findings.append(
                Finding(
                    filePath=hunk.file_path,
                    hunkHash=hunk.hunk_hash,
                    lineStart=l_start,
                    lineEnd=l_end,
                    antiPattern=rule_id,
                    category=rule_id.split("_", 1)[0],
                    severity=cfg["severity"],
                    confidence=cfg["confidence"],
                    explanation=cfg["explanation"],
                )
            )

    return ReviewResponse(
        findings=findings,
        qualityScore=compute_quality_score(findings),
        processingTimeMs=0,
        windowsProcessed=windows_processed,
        engine="fallback",
        modelVersion="rule-baseline-v1",
        taxonomyVersion="1.0.0",
    )
