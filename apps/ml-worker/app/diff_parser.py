"""Parse unified diffs into independently identifiable file hunks.

``hunk_sha256`` is the lowercase SHA-256 hex digest of the UTF-8 bytes of
``raw_hunk``: the hunk header and body with line endings normalized to ``\n``
and no trailing newline (see ``app.preprocessing.canonicalize_hunk``). The path
is excluded; line ranges are included in the header. For a well-formed
``git diff`` the Java API's ``HunkParser`` produces the same ``raw_hunk`` and
digest (``contracts/hunk_identity_cases.json``), so a finding's ``hunkHash``
identifies the hunk on both sides.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import PurePosixPath
from typing import Literal, Sequence

from app.preprocessing import canonicalize_hunk, hunk_sha256

DiffLineKind = Literal["added", "removed", "context"]

# Mirrors HunkParser.detectLanguage in the Java API so the language tag in the
# model input is identical at training time (Java-ingested samples) and at
# serving time (hunks parsed here).
_LANGUAGE_BY_EXT: dict[str, str] = {
    ".py": "python",
    ".js": "javascript",
    ".jsx": "javascript",
    ".ts": "typescript",
    ".tsx": "typescript",
    ".java": "java",
    ".go": "go",
    ".rs": "rust",
    ".c": "c",
    ".h": "c",
    ".cpp": "cpp",
    ".hpp": "cpp",
    ".cc": "cpp",
    ".cs": "csharp",
    ".rb": "ruby",
    ".php": "php",
}

_GIT_HEADER_PREFIX = "diff --git "
_HUNK_HEADER_RE = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@")


@dataclass(frozen=True)
class DiffLine:
    kind: DiffLineKind
    text: str
    old_line: int | None
    new_line: int | None


@dataclass(frozen=True)
class FileHunk:
    file_path: str  # new file path (or renamed destination)
    language: str
    old_start: int
    old_count: int
    new_start: int
    new_count: int
    added_lines: tuple[DiffLine, ...]
    removed_lines: tuple[DiffLine, ...]
    context_lines: tuple[DiffLine, ...]
    raw_hunk: str
    hunk_sha256: str = ""

    def __post_init__(self) -> None:
        if not self.hunk_sha256:
            object.__setattr__(self, "hunk_sha256", hunk_sha256(self.raw_hunk))


def detect_language(file_path: str) -> str:
    return _LANGUAGE_BY_EXT.get(PurePosixPath(file_path).suffix.lower(), "unknown")


_C_ESCAPES = {
    "a": b"\a", "b": b"\b", "t": b"\t", "n": b"\n", "v": b"\v",
    "f": b"\f", "r": b"\r", '"': b'"', "\\": b"\\",
}


def _unquote_path(path: str) -> str:
    """Decode a path git C-quoted because it has non-ASCII or special bytes.

    ``core.quotePath`` (on by default) writes ``"b/caf\\303\\251.py"``; read
    literally, the quotes hide the extension and the file is never reviewed.
    """
    if len(path) < 2 or not (path.startswith('"') and path.endswith('"')):
        return path
    body = path[1:-1]
    out = bytearray()
    i = 0
    while i < len(body):
        char = body[i]
        octal = body[i + 1 : i + 4]
        if char != "\\" or i + 1 == len(body):
            out += char.encode("utf-8")
            i += 1
        elif len(octal) == 3 and all(digit in "01234567" for digit in octal):
            out.append(int(octal, 8) & 0xFF)
            i += 4
        else:
            out += _C_ESCAPES.get(body[i + 1], body[i + 1].encode("utf-8"))
            i += 2
    return out.decode("utf-8", errors="replace")


def _strip_side_prefix(path: str) -> str:
    return path[2:] if path.startswith(("a/", "b/")) else path


def _path_from_git_header(line: str) -> str:
    """Return the new path of a ``diff --git`` line, or ``""``.

    Unquoted paths use the first `` b/`` like the Java ``HunkParser``; a quoted
    new path (``"b/…"``) is decoded.
    """
    rest = line[len(_GIT_HEADER_PREFIX) :]
    quoted = rest.rfind(' "b/')
    if rest.endswith('"') and quoted >= 0:
        return _strip_side_prefix(_unquote_path(rest[quoted + 1 :]))
    marker = line.find(" b/")
    return line[marker + 3 :] if marker >= 0 else ""


def _path_from_file_header(line: str) -> str:
    """Return the path of a ``+++ b/x`` line; ``""`` for ``/dev/null``."""
    raw = line[4:].strip()
    path = _unquote_path(raw) if raw.startswith('"') else raw.split("\t", 1)[0].strip()
    if path == "/dev/null":
        return ""
    return _strip_side_prefix(path)


def parse_diff(diff_text: str) -> list[FileHunk]:
    """Parse a unified diff into a flat list of :class:`FileHunk` in document order.

    Hunk bodies are bounded by the line counts in the ``@@`` header, as in the
    Java ``HunkParser``, so an added line such as ``+++ x`` is content, not a
    file header. Malformed hunk headers are skipped, as in Java. Binary files
    and hunks without added lines are skipped: only added code can contain a
    new defect. Unlike the Java parser, plain ``diff -u`` output (no
    ``diff --git`` line) is also accepted.
    """
    lines = diff_text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    hunks: list[FileHunk] = []
    file_path = ""
    skip_file = False
    i = 0
    n = len(lines)

    while i < n:
        line = lines[i]
        i += 1
        if line.startswith(_GIT_HEADER_PREFIX):
            file_path = _path_from_git_header(line)
            skip_file = False
            continue
        if line.startswith("Binary files"):
            skip_file = True
            continue
        if line.startswith("+++ "):
            # A text file header (plain ``diff -ru`` has no ``diff --git``
            # line) starts a new file, so an earlier binary marker no longer applies.
            file_path = _path_from_file_header(line) or file_path
            skip_file = False
            continue
        match = _HUNK_HEADER_RE.match(line)
        if not match:
            continue

        old_start, new_start = int(match.group(1)), int(match.group(3))
        old_count, new_count = int(match.group(2) or 1), int(match.group(4) or 1)
        old_left, new_left = old_count, new_count
        body: list[str] = []
        while i < n and (old_left > 0 or new_left > 0) and not lines[i].startswith(_GIT_HEADER_PREFIX):
            marker = lines[i][:1]
            if marker == "+":
                new_left -= 1
            elif marker == "-":
                old_left -= 1
            elif marker != "\\":
                old_left -= 1
                new_left -= 1
            body.append(lines[i])
            i += 1
        # "\ No newline at end of file" may follow the last counted line.
        while i < n and lines[i].startswith("\\"):
            body.append(lines[i])
            i += 1
        if skip_file:
            continue
        hunk = _build_hunk(file_path, (old_start, old_count, new_start, new_count), body, line)
        if hunk.added_lines:
            hunks.append(hunk)

    return hunks


def code_hunks(diff_text: str) -> list[FileHunk]:
    """Hunks of files in a recognised code language.

    Docs, config and other ``unknown``-language files are not reviewed: the
    rules and the model target source code, and running them on prose turns
    ``Set timeout to 300 seconds.`` into a magic-number finding.
    """
    return [hunk for hunk in parse_diff(diff_text) if hunk.language != "unknown"]


def _build_hunk(
    file_path: str,
    header: tuple[int, int, int, int],
    body: Sequence[str],
    header_line: str,
) -> FileHunk:
    old_start, old_count, new_start, new_count = header
    added: list[DiffLine] = []
    removed: list[DiffLine] = []
    context: list[DiffLine] = []
    old_l = old_start
    new_l = new_start
    for raw in body:
        marker = raw[:1]
        if marker == "+":
            added.append(DiffLine("added", raw[1:], None, new_l))
            new_l += 1
        elif marker == "-":
            removed.append(DiffLine("removed", raw[1:], old_l, None))
            old_l += 1
        elif marker == "\\":
            # "\ No newline at end of file" — metadata, not a source line.
            continue
        else:
            # " " context, or an empty line from a diff whose trailing
            # whitespace was stripped. Same rule as the Java parser.
            text = raw[1:] if marker == " " else raw
            context.append(DiffLine("context", text, old_l, new_l))
            old_l += 1
            new_l += 1
    return FileHunk(
        file_path=file_path,
        language=detect_language(file_path),
        old_start=old_start,
        old_count=old_count,
        new_start=new_start,
        new_count=new_count,
        added_lines=tuple(added),
        removed_lines=tuple(removed),
        context_lines=tuple(context),
        raw_hunk=canonicalize_hunk("\n".join([header_line, *body])),
    )


def hunk_added_line_range(hunk: FileHunk) -> tuple[int, int]:
    """Return (line_start, line_end) for a hunk-level model finding.

    Starts at the hunk's new-file start and ends at the last added line.
    """
    if hunk.added_lines:
        end = hunk.added_lines[-1].new_line or hunk.new_start
    else:
        end = hunk.new_start
    return hunk.new_start, max(end, hunk.new_start)
