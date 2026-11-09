"""Deterministic, hunk-localized fallback detector.

Rules run on Python, JavaScript, TypeScript and Java hunks. Each hunk's
new-side lines (added and context) are lexed in order so a rule that looks
for a call ignores the same text inside comments and string literals, while
only added lines can produce a finding.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from app.code_lexer import INTERPOLATION, LexedLine, LineLexer
from app.diff_parser import DiffLine, FileHunk, code_hunks, detect_language
from app.finding_guidance import evidence_from_line, guidance_for, validate_guidance
from app.model import compute_quality_score
from app.schemas import Finding, ReviewResponse
from app.secret_redaction import REDACTED, is_provider_credential
from app.taxonomy import AntiPattern, Taxonomy, load_taxonomy

RULE_ENGINE_VERSION = "rule-baseline-v2"
# The languages the rules were written and benchmarked for. Other code
# languages are still parsed (and seen by a model) but not rule-scanned.
RULE_LANGUAGES = frozenset({"python", "javascript", "typescript", "java"})

# Groups: 1 = name, separator and opening quote; 2 = value; 3 = closing quote.
_SECRET_RE = re.compile(
    r"(?i)((?:api_key|api_token|apikey|secret_key|private_key|access_key|"
    r"auth_token|password)\s*[=:]\s*['\"])([A-Za-z0-9_+/=\-]{16,})(['\"])"
)
# Query calls, including JDBC executeQuery/executeUpdate and JPA createQuery.
# Matched once, then the suffix is inspected once (no rescanning per opener).
_SQL_CALL_RE = re.compile(
    r"\b(?:execute\w*|query|prepareStatement|create(?:Native)?Query)\s*+\(|\.raw\s*+\(",
    re.I,
)
# String building on code whose literals are emptied: concatenation,
# %-formatting, str.format, or an interpolating f-string / template literal.
_SQL_VALUE_RE = re.compile(r"\+|%\s*+[\w(]|\.format\s*+\(|" + re.escape(INTERPOLATION))
_SQL_TEXT_RE = re.compile(
    r"^\s*(?:SELECT|INSERT|UPDATE|DELETE|MERGE|REPLACE|WITH)\b"
    r"[^;]{0,400}?\b(?:FROM|INTO|SET|WHERE|VALUES|JOIN)\b",
    re.I,
)
# Bare ``except:``, ``except Exception[ as e]:`` and ``except BaseException``.
_BROAD_EXC_RE = re.compile(
    r"^\s*+except\s*+(?:\(?\s*+(?:Base)?Exception"
    r"(?:\s*+\))?(?:\s++as\s++\w++)?\s*+)?:"
)
_JAVA_CATCH_RE = re.compile(r"catch\s*\(\s*(?:Exception|Throwable)\b")
_RETHROW_RE = re.compile(r"^\s*(?:raise|throw)\b")
_WEAK_CRYPTO_CALL_RE = re.compile(r"\b(?:md5|sha1|des|rc4)\s*\(", re.I)
_ALGORITHM_API_RE = re.compile(
    r"\b(?:getInstance|createHash|createCipheriv|createDecipheriv|new)\s*\("
)
_WEAK_ALGORITHM_RE = re.compile(r"^(?:md5|sha-?1|des(?:ede)?(?:/.*)?|rc4|arcfour)$", re.I)
_NOT_FOR_SECURITY_RE = re.compile(r"usedforsecurity\s*=\s*False")
_PRINT_RE = re.compile(r"(?:^\s*print\s*\(|\bconsole\.log\s*\()", re.I)
_PRINT_TO_STREAM_RE = re.compile(r"\bfile\s*=")
_MAGIC_NUMBER_RE = re.compile(r"(?<![\w.'\"])(?:[2-9]\d|[1-9]\d{2,})(?![\w.'\"])")
# Well-known HTTP status codes read as names, not magic numbers.
_HTTP_STATUS_CODES = frozenset({
    100, 101, 200, 201, 202, 204, 206, 301, 302, 303, 304, 307, 308, 400, 401,
    403, 404, 405, 409, 410, 412, 415, 422, 429, 500, 501, 502, 503, 504,
})
# ``MAX_RETRIES = 50`` / ``static final int LIMIT = 100`` is the remedy, not the smell.
_CONSTANT_NAME_RE = re.compile(r"\b[A-Z][A-Z0-9_]*+\s*+(?::[\s\S]|$)")
# ``NAME = "<one long literal>"``: the length is the literal (a URL, a message).
_LITERAL_ASSIGNMENT_RE = re.compile(r"^[^=(]*=\s*[A-Za-z]{0,2}([\"'`])\1\s*[;,]?\s*$")
_LOOP_RE = re.compile(r"^\s*(?:for|while)\b")
# Per-iteration DB/HTTP calls. ``.get(`` only counts on an HTTP client or ORM
# manager receiver, so a plain ``dict.get(key)`` inside a loop is not flagged.
_QUERY_RE = re.compile(
    r"\b(?:query|execute|findBy\w*|fetch|request)\s*\("
    r"|\b(?:requests|httpx|session|client|http|axios|objects)"
    r"\.(?:get|post|put|patch|delete|head|request)\s*\(",
    re.I,
)
# HTTP calls with no default timeout. httpx (5 s default) is deliberately absent.
_TIMEOUT_CALL_RE = {
    "python": re.compile(
        r"\brequests\.(?:get|post|put|patch|delete|head|options|request)\s*\(|\burlopen\s*\("
    ),
    "javascript": re.compile(
        r"(?<![\w.$])fetch\s*\(|\baxios(?:\.(?:get|post|put|patch|delete|head|options|request))?\s*\("
    ),
    "java": re.compile(r"\bHttpClient\.newHttpClient\s*\(\s*\)"),
}
_TIMEOUT_CALL_RE["typescript"] = _TIMEOUT_CALL_RE["javascript"]
# How a call can carry a timeout: a keyword, an AbortSignal, or forwarded options.
_TIMEOUT_ARG_RE = {
    "python": re.compile(r"\btimeout\b|\*\*"),
    "javascript": re.compile(r"\bsignal\b|\btimeout\b|\.\.\."),
}
_TIMEOUT_ARG_RE["typescript"] = _TIMEOUT_ARG_RE["javascript"]
_CALL_LOOKAHEAD_LINES = 15
_TEST_PATH_RE = re.compile(
    r"(?:^|/)(?:tests?|__tests__|specs?|src/test)/"
    r"|(?:^|/)(?:test_[^/]*|[^/]*_test|conftest)\.py$"
    r"|\.(?:test|spec)\.[cm]?[jt]sx?$"
    r"|Tests?\.java$"
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


@dataclass(frozen=True)
class RuleHit:
    anti_pattern_id: str
    line_start: int
    line_end: int
    # The added line that triggered the rule (raw; redacted when reported).
    evidence: str


@dataclass(frozen=True)
class _Line:
    number: int
    text: str
    added: bool
    lexed: LexedLine

    @property
    def indent(self) -> int:
        return len(self.text) - len(self.text.lstrip())


# Fixed per-rule confidence. These are hand-set priors, not measured precision.
_CONFIDENCE: dict[str, float] = {
    "SECURITY_HARDCODED_SECRET": 0.70,
    "SECURITY_SQL_INJECTION": 0.65,
    "SECURITY_WEAK_CRYPTO": 0.65,
    "PERFORMANCE_N_PLUS_ONE": 0.60,
    "PERFORMANCE_QUADRATIC_LOOP": 0.60,
    "RELIABILITY_BROAD_EXCEPTION": 0.75,
    "RELIABILITY_MISSING_TIMEOUT": 0.60,
    "READABILITY_MAGIC_NUMBER": 0.55,
    "READABILITY_LONG_METHOD": 0.40,
    "MAINTAINABILITY_PRINT_STATEMENT": 0.65,
    "MAINTAINABILITY_COMMENTED_CODE": 0.60,
}


def _is_test_path(path: str) -> bool:
    return bool(_TEST_PATH_RE.search(path.replace("\\", "/")))


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


def _is_secret(line: _Line, test_file: bool) -> bool:
    match = _SECRET_RE.search(line.text)
    if match is None:
        return False
    # Test fixtures are full of made-up passwords; there only a value with a
    # real provider token format (AKIA…, ghp_…, sk_live_…) counts.
    return not test_file or is_provider_credential(match.group(2))


def _is_sql_injection(lexed: LexedLine) -> bool:
    code = lexed.code
    call = _SQL_CALL_RE.search(code)
    if call is not None and _SQL_VALUE_RE.search(code, call.end()) is not None:
        return True
    # SQL text assembled from values on this line and executed later.
    return _SQL_VALUE_RE.search(code) is not None and any(
        _SQL_TEXT_RE.match(literal) for literal in lexed.strings
    )


def _is_weak_crypto(lexed: LexedLine) -> bool:
    code = lexed.code
    if _NOT_FOR_SECURITY_RE.search(code):
        return False
    if _WEAK_CRYPTO_CALL_RE.search(code):
        return True
    return _ALGORITHM_API_RE.search(code) is not None and any(
        _WEAK_ALGORITHM_RE.match(literal.strip()) for literal in lexed.strings
    )


def _rethrows(lines: list[_Line], index: int) -> bool:
    """True when the handler body (deeper-indented lines) re-raises or throws."""
    handler_indent = lines[index].indent
    for line in lines[index + 1 :]:
        if not line.text.strip():
            continue
        if line.indent <= handler_indent:
            return False
        if _RETHROW_RE.match(line.lexed.code):
            return True
    return False


def _is_magic_number(code: str) -> bool:
    if _is_constant_definition(code):
        return False
    return any(int(match.group()) not in _HTTP_STATUS_CODES for match in _MAGIC_NUMBER_RE.finditer(code))


def _call_arguments(lines: list[_Line], index: int, open_paren_end: int) -> tuple[str, int] | None:
    """Return the argument text of a call and the line closing it, if found."""
    depth = 1
    parts: list[str] = []
    for line in lines[index : index + _CALL_LOOKAHEAD_LINES]:
        code = line.lexed.code[open_paren_end:] if line is lines[index] else line.lexed.code
        for position, char in enumerate(code):
            if char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
                if depth == 0:
                    parts.append(code[:position])
                    return " ".join(parts), line.number
        parts.append(code)
    return None


def _missing_timeout(lines: list[_Line], index: int, language: str) -> RuleHit | None:
    line = lines[index]
    match = _TIMEOUT_CALL_RE[language].search(line.lexed.code)
    if match is None:
        return None
    if language == "java":
        return RuleHit("RELIABILITY_MISSING_TIMEOUT", line.number, line.number, line.text)
    call = _call_arguments(lines, index, match.end())
    if call is None or _TIMEOUT_ARG_RE[language].search(call[0]):
        return None
    return RuleHit("RELIABILITY_MISSING_TIMEOUT", line.number, call[1], line.text)


def _n_plus_one(lines: list[_Line], index: int) -> RuleHit | None:
    line = lines[index]
    if not _QUERY_RE.search(line.lexed.code):
        return None
    for previous in reversed(lines[max(0, index - 5) : index]):
        if previous.indent < line.indent and _LOOP_RE.match(previous.lexed.code):
            return RuleHit("PERFORMANCE_N_PLUS_ONE", previous.number, line.number, line.text)
    return None


def _taxonomy_rules(taxonomy: Taxonomy) -> dict[str, AntiPattern]:
    by_id = {entry.id: entry for entry in taxonomy.entries}
    unknown = sorted(set(_CONFIDENCE) - set(by_id))
    if unknown:
        raise RuntimeError(f"Fallback rules reference unknown taxonomy IDs: {unknown}")
    validate_guidance(taxonomy)
    uncaveated = sorted(rule for rule in _CONFIDENCE if guidance_for(rule).rule_caveat is None)
    if uncaveated:
        raise RuntimeError(f"Fallback rules without a caveat: {uncaveated}")
    return by_id


def _evidence(hit: RuleHit) -> str:
    # The rule's own secret match is masked as well as the shared redaction,
    # so the value that triggered a secret finding is never echoed back.
    return evidence_from_line(_SECRET_RE.sub(rf"\1{REDACTED}\3", hit.evidence))


def _line_number(line: DiffLine, default: int) -> int:
    return line.new_line if line.new_line is not None else default


def _new_side_lines(hunk: FileHunk) -> list[_Line]:
    """Added and context lines in new-file order, lexed with carried state."""
    ordered = sorted(
        [*((line, True) for line in hunk.added_lines), *((line, False) for line in hunk.context_lines)],
        key=lambda item: _line_number(item[0], hunk.new_start),
    )
    lexer = LineLexer(hunk.language)
    return [
        _Line(_line_number(line, hunk.new_start), line.text, added, lexer.line(line.text))
        for line, added in ordered
    ]


def _scan_hunk(hunk: FileHunk) -> list[RuleHit]:
    lines = _new_side_lines(hunk)
    test_file = _is_test_path(hunk.file_path)
    hits: list[RuleHit] = []

    for index, line in enumerate(lines):
        if not line.added:
            continue
        text, code, number = line.text, line.lexed.code, line.number
        if _is_secret(line, test_file):
            hits.append(RuleHit("SECURITY_HARDCODED_SECRET", number, number, text))
        if _is_sql_injection(line.lexed):
            hits.append(RuleHit("SECURITY_SQL_INJECTION", number, number, text))
        if _is_weak_crypto(line.lexed):
            hits.append(RuleHit("SECURITY_WEAK_CRYPTO", number, number, text))
        if (
            _BROAD_EXC_RE.search(code)
            or (hunk.language == "java" and _JAVA_CATCH_RE.search(code))
        ) and not _rethrows(lines, index):
            hits.append(RuleHit("RELIABILITY_BROAD_EXCEPTION", number, number, text))
        if (
            not test_file
            and _PRINT_RE.search(code)
            and not (hunk.language == "python" and _PRINT_TO_STREAM_RE.search(code))
        ):
            hits.append(RuleHit("MAINTAINABILITY_PRINT_STATEMENT", number, number, text))
        if len(text.rstrip()) > 200 and not _LITERAL_ASSIGNMENT_RE.match(code):
            hits.append(RuleHit("READABILITY_LONG_METHOD", number, number, text))
        if not test_file and _is_magic_number(code):
            hits.append(RuleHit("READABILITY_MAGIC_NUMBER", number, number, text))
        for hit in (_missing_timeout(lines, index, hunk.language), _n_plus_one(lines, index)):
            if hit is not None:
                hits.append(hit)

    added = [line for line in lines if line.added]
    for index, line in enumerate(added):
        if not _LOOP_RE.match(line.lexed.code):
            continue
        for nested in added[index + 1 :]:
            if not nested.text.strip():
                continue
            if nested.indent <= line.indent:
                break
            if _LOOP_RE.match(nested.lexed.code):
                hits.append(RuleHit("PERFORMANCE_QUADRATIC_LOOP", line.number, nested.number, nested.text))
                break

    # Group comment lines directly instead of a nested repetition whose
    # backtracking grew exponentially on a long whitespace-only comment.
    block_start: int | None = None
    for index in range(len(added) + 1):
        if index < len(added) and _COMMENT_LINE_RE.match(added[index].text):
            if block_start is None:
                block_start = index
            continue
        if block_start is not None:
            if index - block_start >= 3 and _is_commented_code(
                "\n".join(line.text for line in added[block_start:index])
            ):
                hits.append(
                    RuleHit(
                        "MAINTAINABILITY_COMMENTED_CODE",
                        added[block_start].number,
                        added[index - 1].number,
                        added[block_start].text,
                    )
                )
            block_start = None

    unique = {(hit.anti_pattern_id, hit.line_start, hit.line_end): hit for hit in hits}
    return list(unique.values())


def _file_mode_hunk(content: str, language: str, file_path: str | None) -> FileHunk | None:
    rule_language = language if language in RULE_LANGUAGES else detect_language(file_path or "")
    if rule_language not in RULE_LANGUAGES:
        return None
    normalized = content.replace("\r\n", "\n").replace("\r", "\n")
    source_lines = normalized.split("\n")
    raw_hunk = "\n".join(
        [f"@@ -0,0 +1,{len(source_lines)} @@", *[f"+{line}" for line in source_lines]]
    )
    return FileHunk(
        file_path=file_path or "input",
        language=rule_language,
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


def reviewable_hunks(diff: str) -> list[FileHunk]:
    """Diff hunks the rule engine inspects: code in :data:`RULE_LANGUAGES`."""
    return [hunk for hunk in code_hunks(diff) if hunk.language in RULE_LANGUAGES]


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
    if mode == "file":
        file_hunk = _file_mode_hunk(diff, language, file_path)
        hunks = [file_hunk] if file_hunk is not None else []
    else:
        hunks = reviewable_hunks(diff)
    findings: list[Finding] = []

    for hunk in hunks:
        for hit in _scan_hunk(hunk):
            anti_pattern = metadata[hit.anti_pattern_id]
            guidance = guidance_for(hit.anti_pattern_id)
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
                    explanation=guidance.explanation,
                    evidence=_evidence(hit),
                    remediation=guidance.remediation,
                    caveat=guidance.rule_caveat,
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
        modelVersion=RULE_ENGINE_VERSION,
        taxonomyVersion=taxonomy_version,
    )
