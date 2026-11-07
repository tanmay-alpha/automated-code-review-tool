"""Deterministic, hunk-localized fallback detector."""

from __future__ import annotations

import re
from dataclasses import dataclass

from app.diff_parser import DiffLine, FileHunk, code_hunks
from app.model import compute_quality_score
from app.schemas import Finding, ReviewResponse
from app.taxonomy import AntiPattern, Taxonomy, load_taxonomy

_SECRET_RE = re.compile(
    r"(?i)(api_key|api_token|apikey|secret_key|private_key|access_key|"
    r"auth_token|password)\s*[=:]\s*['\"][A-Za-z0-9_+/=\-]{16,}['\"]"
)
# Match the call once, then inspect its suffix once; a trailing wildcard in an
# unanchored search rescanned the whole line for every repeated query opener.
_SQL_CALL_RE = re.compile(r"(?:execute|query)\s*+\(", re.I)
_SQL_VALUE_RE = re.compile(r"\+|f['\"]", re.I)
# Bare ``except:``, ``except Exception[ as e]:`` and ``except BaseException``.
_BROAD_EXC_RE = re.compile(
    r"^\s*+except\s*+(?:\(?\s*+(?:Base)?Exception"
    r"(?:\s*+\))?(?:\s++as\s++\w++)?\s*+)?:"
)
_JAVA_CATCH_RE = re.compile(r"catch\s*\(\s*(?:Exception|Throwable)\b")
_WEAK_CRYPTO_RE = re.compile(
    r"\b(?:md5|sha1|des|rc4)\s*\(|MessageDigest\.getInstance\s*\(\s*['\"]SHA-?1",
    re.I,
)
_PRINT_RE = re.compile(r"(?:^\s*print\s*\(|console\.log\s*\()", re.I)
_MAGIC_NUMBER_RE = re.compile(r"(?<![\w.'\"])(?:[2-9]\d|[1-9]\d{2,})(?![\w.'\"])")
# ``MAX_RETRIES = 50`` / ``static final int LIMIT = 100`` is the remedy, not the smell.
_CONSTANT_NAME_RE = re.compile(r"\b[A-Z][A-Z0-9_]*+\s*+(?::[\s\S]|$)")
_LOOP_RE = re.compile(r"^\s*(?:for|while)\b")
# Per-iteration DB/HTTP calls. ``.get(`` only counts on an HTTP client or ORM
# manager receiver, so a plain ``dict.get(key)`` inside a loop is not flagged.
_QUERY_RE = re.compile(
    r"\b(?:query|execute|findBy\w*|fetch|request)\s*\("
    r"|\b(?:requests|httpx|session|client|http|axios|objects)\.get\s*\(",
    re.I,
)
_COMMENT_LINE_RE = re.compile(r"^[ \t]*+(?:#|//)[ \t]*+\S")
_COMMENT_PREFIX_RE = re.compile(r"^[ \t]*(?:#|//)[ \t]*")
# A comment line "looks like code" when it ends in statement/block punctuation,
# contains an assignment, or opens with a common statement keyword.
_CODE_ENDING_RE = re.compile(r"[;{}):]$")
_ASSIGNMENT_RE = re.compile(r"[\w\]\)]\s*(?:[-+*/%|&^]|//|<<|>>)?=(?![=>])")
_CODE_KEYWORD_RE = re.compile(
    r"^(?:def|class|return|if|elif|else|for|while|try|except|catch|import|from"
    r"|const|let|var|function|public|private|protected|static)\b"
)


def _has_sql_concatenation(text: str) -> bool:
    call = _SQL_CALL_RE.search(text)
    return call is not None and _SQL_VALUE_RE.search(text, call.end()) is not None


def _is_constant_definition(text: str) -> bool:
    # Each '='-delimited segment is visited only once, including annotations.
    start = 0
    for match in re.finditer("=", text):
        end = match.start()
        if text[end + 1 : end + 2] != "=" and _CONSTANT_NAME_RE.search(text[start:end]):
            return True
        start = end + 1
    return False


def _looks_like_code(comment_body: str) -> bool:
    return bool(
        _CODE_ENDING_RE.search(comment_body)
        or _ASSIGNMENT_RE.search(comment_body)
        or _CODE_KEYWORD_RE.match(comment_body)
    )


def _is_commented_code(comment_block: str) -> bool:
    bodies = [
        _COMMENT_PREFIX_RE.sub("", line).rstrip()
        for line in comment_block.splitlines()
        if line.strip()
    ]
    code_like = sum(1 for body in bodies if _looks_like_code(body))
    return code_like * 2 > len(bodies)


@dataclass(frozen=True)
class RuleHit:
    anti_pattern_id: str
    line_start: int
    line_end: int


# Fixed per-rule confidence. These are hand-set priors, not measured precision.
_CONFIDENCE: dict[str, float] = {
    "SECURITY_HARDCODED_SECRET": 0.70,
    "SECURITY_SQL_INJECTION": 0.65,
    "SECURITY_WEAK_CRYPTO": 0.65,
    "PERFORMANCE_N_PLUS_ONE": 0.60,
    "PERFORMANCE_QUADRATIC_LOOP": 0.60,
    "RELIABILITY_BROAD_EXCEPTION": 0.75,
    "READABILITY_MAGIC_NUMBER": 0.55,
    "READABILITY_LONG_METHOD": 0.40,
    "MAINTAINABILITY_PRINT_STATEMENT": 0.65,
    "MAINTAINABILITY_COMMENTED_CODE": 0.60,
}


def _taxonomy_rules(taxonomy: Taxonomy) -> dict[str, AntiPattern]:
    by_id = {entry.id: entry for entry in taxonomy.entries}
    unknown = sorted(set(_CONFIDENCE) - set(by_id))
    if unknown:
        raise RuntimeError(f"Fallback rules reference unknown taxonomy IDs: {unknown}")
    return by_id


def _line_number(line: DiffLine, default: int) -> int:
    return line.new_line if line.new_line is not None else default


def _scan_hunk(hunk: FileHunk) -> list[RuleHit]:
    lines = list(hunk.added_lines)
    hits: list[RuleHit] = []

    for index, diff_line in enumerate(lines):
        text = diff_line.text
        line_no = _line_number(diff_line, hunk.new_start)
        if _SECRET_RE.search(text):
            hits.append(RuleHit("SECURITY_HARDCODED_SECRET", line_no, line_no))
        if _has_sql_concatenation(text):
            hits.append(RuleHit("SECURITY_SQL_INJECTION", line_no, line_no))
        if _WEAK_CRYPTO_RE.search(text):
            hits.append(RuleHit("SECURITY_WEAK_CRYPTO", line_no, line_no))
        if _BROAD_EXC_RE.search(text) or (
            hunk.language in {"java", "csharp"} and _JAVA_CATCH_RE.search(text)
        ):
            hits.append(RuleHit("RELIABILITY_BROAD_EXCEPTION", line_no, line_no))
        if _PRINT_RE.search(text):
            hits.append(RuleHit("MAINTAINABILITY_PRINT_STATEMENT", line_no, line_no))
        if len(text.rstrip()) > 200:
            hits.append(RuleHit("READABILITY_LONG_METHOD", line_no, line_no))
        if _MAGIC_NUMBER_RE.search(text) and not _is_constant_definition(text):
            hits.append(RuleHit("READABILITY_MAGIC_NUMBER", line_no, line_no))

        if _QUERY_RE.search(text):
            recent = lines[max(0, index - 5) : index]
            loop_line = next((item for item in reversed(recent) if _LOOP_RE.match(item.text)), None)
            if loop_line is not None:
                start = _line_number(loop_line, line_no)
                hits.append(RuleHit("PERFORMANCE_N_PLUS_ONE", start, line_no))

    for index, line in enumerate(lines):
        if not _LOOP_RE.match(line.text):
            continue
        indent = len(line.text) - len(line.text.lstrip())
        for nested_index in range(index + 1, len(lines)):
            nested = lines[nested_index]
            if not nested.text.strip():
                continue
            nested_indent = len(nested.text) - len(nested.text.lstrip())
            if nested_indent <= indent:
                break
            if _LOOP_RE.match(nested.text):
                start = _line_number(line, hunk.new_start)
                end = _line_number(nested, start)
                hits.append(RuleHit("PERFORMANCE_QUADRATIC_LOOP", start, end))
                break

    # Group comment lines directly instead of a nested repetition whose
    # backtracking grew exponentially on a long whitespace-only comment.
    block_start: int | None = None
    for index in range(len(lines) + 1):
        if index < len(lines) and _COMMENT_LINE_RE.match(lines[index].text):
            if block_start is None:
                block_start = index
            continue
        if block_start is not None:
            if index - block_start >= 3 and _is_commented_code(
                "\n".join(line.text for line in lines[block_start:index])
            ):
                hits.append(
                    RuleHit(
                        "MAINTAINABILITY_COMMENTED_CODE",
                        _line_number(lines[block_start], hunk.new_start),
                        _line_number(lines[index - 1], hunk.new_start),
                    )
                )
            block_start = None

    unique = {(hit.anti_pattern_id, hit.line_start, hit.line_end): hit for hit in hits}
    return list(unique.values())


def _file_mode_hunk(content: str, language: str, file_path: str | None) -> FileHunk:
    normalized = content.replace("\r\n", "\n").replace("\r", "\n")
    source_lines = normalized.split("\n")
    raw_hunk = "\n".join(
        [f"@@ -0,0 +1,{len(source_lines)} @@", *[f"+{line}" for line in source_lines]]
    )
    return FileHunk(
        file_path=file_path or "input",
        language=language or "unknown",
        old_start=0,
        old_count=0,
        new_start=1,
        new_count=len(source_lines),
        added_lines=tuple(
            DiffLine("added", line, None, index)
            for index, line in enumerate(source_lines, start=1)
        ),
        removed_lines=(),
        context_lines=(),
        raw_hunk=raw_hunk,
    )


def fallback_findings(
    diff: str,
    *,
    mode: str = "diff",
    language: str = "unknown",
    file_path: str | None = None,
) -> tuple[list[Finding], int, str]:
    """Return localized findings, processed-hunk count, and taxonomy version."""
    taxonomy = load_taxonomy()
    metadata = _taxonomy_rules(taxonomy)
    hunks = (
        [_file_mode_hunk(diff, language, file_path)]
        if mode == "file"
        else code_hunks(diff)
    )
    findings: list[Finding] = []

    for hunk in hunks:
        for hit in _scan_hunk(hunk):
            anti_pattern = metadata[hit.anti_pattern_id]
            findings.append(
                Finding(
                    filePath=hunk.file_path,
                    hunkHash=hunk.hunk_sha256,
                    lineStart=hit.line_start,
                    lineEnd=hit.line_end,
                    antiPattern=anti_pattern.id,
                    category=anti_pattern.category,
                    severity=anti_pattern.default_severity,
                    confidence=_CONFIDENCE[hit.anti_pattern_id],
                    explanation=anti_pattern.explanation,
                )
            )
    return findings, max(1, len(hunks)), taxonomy.version


def fallback_scan(
    diff: str,
    language: str = "unknown",
    *,
    mode: str = "diff",
    file_path: str | None = None,
) -> ReviewResponse:
    """Analyze a unified diff without a model checkpoint."""
    findings, hunks_processed, taxonomy_version = fallback_findings(
        diff,
        mode=mode,
        language=language,
        file_path=file_path,
    )
    return ReviewResponse(
        findings=findings,
        qualityScore=compute_quality_score(findings),
        processingTimeMs=0,
        windowsProcessed=hunks_processed,
        engine="fallback",
        modelVersion="rule-baseline-v1",
        taxonomyVersion=taxonomy_version,
    )
