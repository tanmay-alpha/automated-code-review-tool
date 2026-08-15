"""Deterministic, hunk-localized fallback detector."""

from __future__ import annotations

import re
from dataclasses import dataclass

from app.diff_parser import DiffLine, FileHunk, parse_diff
from app.model import compute_quality_score
from app.schemas import Finding, ReviewResponse
from app.taxonomy import AntiPattern, Taxonomy, load_taxonomy

_SECRET_RE = re.compile(
    r"(?i)(api_key|api_token|apikey|secret_key|private_key|access_key|"
    r"auth_token|password)\s*[=:]\s*['\"][A-Za-z0-9_+/=\-]{16,}['\"]"
)
_SQL_CONCAT_RE = re.compile(r"(?:execute|query)\s*\(.*(?:\+|f['\"])", re.I)
_BROAD_EXC_RE = re.compile(r"^\s*except\s*(?:Exception\s*)?(?::|$)", re.I)
_JAVA_CATCH_RE = re.compile(r"catch\s*\(\s*(?:Exception|Throwable)\b", re.I)
_WEAK_CRYPTO_RE = re.compile(
    r"\b(?:md5|sha1|des|rc4)\s*\(|MessageDigest\.getInstance\s*\(\s*['\"]SHA-?1",
    re.I,
)
_PRINT_RE = re.compile(r"(?:^\s*print\s*\(|console\.log\s*\()", re.I)
_MAGIC_NUMBER_RE = re.compile(r"(?<![\w.'\"])(?:[2-9]\d|[1-9]\d{2,})(?![\w.'\"])")
_LOOP_RE = re.compile(r"^\s*(?:for|while)\b")
_QUERY_RE = re.compile(r"\b(?:query|execute|findBy|fetch|request|get)\s*\(", re.I)
_COMMENTED_CODE_RE = re.compile(
    r"(?m)^(?:\s*#|\s*//)\s*\S.+\n(?:^(?:\s*#|\s*//)\s*\S.+\n?){2,}"
)


@dataclass(frozen=True)
class RuleHit:
    anti_pattern_id: str
    line_start: int
    line_end: int
    confidence: float


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
            hits.append(RuleHit("SECURITY_HARDCODED_SECRET", line_no, line_no, 0.70))
        if _SQL_CONCAT_RE.search(text):
            hits.append(RuleHit("SECURITY_SQL_INJECTION", line_no, line_no, 0.65))
        if _WEAK_CRYPTO_RE.search(text):
            hits.append(RuleHit("SECURITY_WEAK_CRYPTO", line_no, line_no, 0.65))
        if _BROAD_EXC_RE.search(text) or (
            hunk.language == "java" and _JAVA_CATCH_RE.search(text)
        ):
            hits.append(RuleHit("RELIABILITY_BROAD_EXCEPTION", line_no, line_no, 0.75))
        if _PRINT_RE.search(text):
            hits.append(RuleHit("MAINTAINABILITY_PRINT_STATEMENT", line_no, line_no, 0.65))
        if len(text.rstrip()) > 200:
            hits.append(RuleHit("READABILITY_LONG_METHOD", line_no, line_no, 0.40))
        if _MAGIC_NUMBER_RE.search(text):
            hits.append(RuleHit("READABILITY_MAGIC_NUMBER", line_no, line_no, 0.55))

        if _QUERY_RE.search(text):
            recent = lines[max(0, index - 5) : index]
            loop_line = next((item for item in reversed(recent) if _LOOP_RE.match(item.text)), None)
            if loop_line is not None:
                start = _line_number(loop_line, line_no)
                hits.append(RuleHit("PERFORMANCE_N_PLUS_ONE", start, line_no, 0.60))

    for index, line in enumerate(lines):
        if not _LOOP_RE.match(line.text):
            continue
        indent = len(line.text) - len(line.text.lstrip())
        for nested in lines[index + 1 :]:
            if not nested.text.strip():
                continue
            nested_indent = len(nested.text) - len(nested.text.lstrip())
            if nested_indent <= indent:
                break
            if _LOOP_RE.match(nested.text):
                start = _line_number(line, hunk.new_start)
                end = _line_number(nested, start)
                hits.append(RuleHit("PERFORMANCE_QUADRATIC_LOOP", start, end, 0.60))
                break

    block = "\n".join(line.text for line in lines)
    for match in _COMMENTED_CODE_RE.finditer(block):
        start_index = block[: match.start()].count("\n")
        end_index = min(
            len(lines) - 1,
            start_index + max(0, match.group(0).rstrip("\n").count("\n")),
        )
        if lines:
            hits.append(
                RuleHit(
                    "MAINTAINABILITY_COMMENTED_CODE",
                    _line_number(lines[start_index], hunk.new_start),
                    _line_number(lines[end_index], hunk.new_start),
                    0.60,
                )
            )

    unique: dict[tuple[str, int, int], RuleHit] = {}
    for hit in hits:
        unique[(hit.anti_pattern_id, hit.line_start, hit.line_end)] = hit
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
        is_new_file=True,
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
        else parse_diff(diff)
    )
    findings: list[Finding] = []

    for hunk in hunks:
        if not hunk.added_lines:
            continue
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
                    confidence=hit.confidence,
                    explanation=(
                        anti_pattern.description.strip().splitlines()[0]
                        if anti_pattern.description
                        else f"{anti_pattern.display_name} detected."
                    ),
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
