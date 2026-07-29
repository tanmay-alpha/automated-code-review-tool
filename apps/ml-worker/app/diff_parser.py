"""
automated-code-review-tool — Unified diff parser (Phase 1A).

The previous parser collapsed a file into a single :class:`FileHunk`,
hiding per-hunk source line numbers and missing new-file line ranges.

This parser produces one :class:`FileHunk` per real unified-diff hunk,
preserves per-line old/new line numbers, and gives every hunk a
deterministic SHA-256 hash for dataset deduplication.
"""
from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Literal, Sequence

DiffLineKind = Literal["added", "removed", "context"]

_BINARY_PATH_TOKENS = (
    ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp", ".ico", ".svg",
    ".pdf", ".zip", ".tar", ".gz", ".bz2", ".xz", ".7z", ".rar",
    ".exe", ".dll", ".so", ".dylib", ".class", ".jar",
    ".woff", ".woff2", ".ttf", ".otf",
    ".mp3", ".mp4", ".mov", ".avi", ".wav", ".flac",
)

_LANGUAGE_BY_EXT: dict[str, str] = {
    ".py": "python",
    ".pyi": "python",
    ".js": "javascript",
    ".mjs": "javascript",
    ".cjs": "javascript",
    ".jsx": "javascript",
    ".ts": "javascript",
    ".tsx": "javascript",
    ".java": "java",
    ".kt": "java",
    ".kts": "java",
    ".scala": "java",
    ".groovy": "java",
    ".cs": "java",
    ".rs": "rust",
    ".go": "go",
    ".rb": "ruby",
    ".php": "php",
}


_HUNK_HEADER_RE = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@")
_FILE_HEADER_RE = re.compile(r"^diff --git a/(?P<src>.+) b/(?P<dst>.+)$")
_NEW_FILE_RE = re.compile(r"^new file mode")
_DELETED_FILE_RE = re.compile(r"^deleted file mode")
_BINARY_RE = re.compile(r"^Binary files")


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
    is_new_file: bool = False
    is_deleted_file: bool = False
    hunk_sha256: str = ""
    file_sha256: str = ""  # hash of added code only — for near-dup detection

    def __post_init__(self) -> None:
        # Compute deterministic hashes if not provided.
        if not self.hunk_sha256:
            object.__setattr__(self, "hunk_sha256", _hash_text(self.raw_hunk))
        if not self.file_sha256:
            object.__setattr__(
                self, "file_sha256", _hash_text(self.added_code())
            )

    def added_code(self) -> str:
        return "\n".join(line.text for line in self.added_lines)

    def context_code(self) -> str:
        return "\n".join(line.text for line in self.context_lines)


def _hash_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def detect_language(file_path: str) -> str:
    ext = Path(file_path).suffix.lower()
    return _LANGUAGE_BY_EXT.get(ext, "unknown")


def is_binary_path(file_path: str) -> bool:
    lower = file_path.lower()
    return any(lower.endswith(token) for token in _BINARY_PATH_TOKENS)


def parse_diff(
    diff_text: str,
    *,
    include_removed_only: bool = False,
) -> list[FileHunk]:
    """Parse a unified diff into a flat list of :class:`FileHunk`.

    The list is in document order (file order × hunk order).

    Args:
        diff_text: A unified diff payload (the body of a GitHub ``patch`` URL,
            a raw ``git format-patch``, etc.).
        include_removed_only: If ``False``, hunks that contain only removed
            lines (no added code) are skipped — they cannot represent a new
            defect in the changed code.

    Returns:
        A list of :class:`FileHunk` records.

    Raises:
        ValueError: If a hunk header is malformed.
    """
    hunks: list[FileHunk] = []
    lines = diff_text.splitlines()
    n = len(lines)
    i = 0
    current_src = ""
    current_dst = ""
    is_new = False
    is_deleted = False
    is_binary = False
    skip_file = False

    while i < n:
        line = lines[i]
        m = _FILE_HEADER_RE.match(line)
        if m:
            current_src = m.group("src")
            current_dst = m.group("dst")
            is_new = False
            is_deleted = False
            is_binary = False
            skip_file = False
            i += 1
            # Look ahead for mode / rename / binary markers
            while i < n and lines[i].startswith(("index ", "new file", "deleted file", "rename ", "copy ", "similarity ", "old mode", "new mode", "Binary files")):
                if _NEW_FILE_RE.match(lines[i]):
                    is_new = True
                elif _DELETED_FILE_RE.match(lines[i]):
                    is_deleted = True
                elif _BINARY_RE.match(lines[i]):
                    is_binary = True
                    skip_file = True
                # Detect pure renames (no content) — treat as skipped
                if lines[i].startswith("rename ") and "100%" in lines[i]:
                    skip_file = True
                i += 1
            # Detect "Binary files ... differ" lines already handled
            continue

        if line.startswith("@@"):
            h = _HUNK_HEADER_RE.match(line)
            if not h:
                raise ValueError(f"Malformed hunk header: {line!r}")
            old_start = int(h.group(1))
            old_count = int(h.group(2) or 1)
            new_start = int(h.group(3))
            new_count = int(h.group(4) or 1)
            # collect hunk body until next "@@" or "diff --git"
            j = i + 1
            body: list[str] = []
            while j < n and not lines[j].startswith("@@") and not _FILE_HEADER_RE.match(lines[j]):
                # Stop at the next "diff --git"
                body.append(lines[j])
                j += 1
            i = j
            if skip_file or is_binary:
                continue
            file_path = current_dst or current_src
            hunk = _build_hunk(
                file_path=file_path,
                is_new=is_new,
                is_deleted=is_deleted,
                old_start=old_start,
                old_count=old_count,
                new_start=new_start,
                new_count=new_count,
                body=body,
                header=line,
            )
            if not include_removed_only and not hunk.added_lines:
                # hunk has no added code → cannot represent a new defect
                continue
            hunks.append(hunk)
            continue

        # any other line — skip
        i += 1

    return hunks


def _build_hunk(
    *,
    file_path: str,
    is_new: bool,
    is_deleted: bool,
    old_start: int,
    old_count: int,
    new_start: int,
    new_count: int,
    body: Sequence[str],
    header: str,
) -> FileHunk:
    added: list[DiffLine] = []
    removed: list[DiffLine] = []
    context: list[DiffLine] = []
    old_l = old_start
    new_l = new_start
    for raw in body:
        if not raw:
            # diff often ends each hunk with a blank line; ignore.
            continue
        marker = raw[0]
        text = raw[1:]
        if marker == "+":
            added.append(DiffLine(kind="added", text=text, old_line=None, new_line=new_l))
            new_l += 1
        elif marker == "-":
            removed.append(DiffLine(kind="removed", text=text, old_line=old_l, new_line=None))
            old_l += 1
        elif marker == " ":
            context.append(DiffLine(kind="context", text=text, old_line=old_l, new_line=new_l))
            old_l += 1
            new_l += 1
        elif marker == "\\":
            # "\ No newline at end of file" — metadata, skip.
            continue
        else:
            # Unknown marker — treat as context for resilience.
            context.append(DiffLine(kind="context", text=text, old_line=old_l, new_line=new_l))
            old_l += 1
            new_l += 1
    raw_hunk = header + "\n" + "\n".join(body)
    language = detect_language(file_path)
    return FileHunk(
        file_path=file_path,
        language=language,
        old_start=old_start,
        old_count=old_count,
        new_start=new_start,
        new_count=new_count,
        added_lines=tuple(added),
        removed_lines=tuple(removed),
        context_lines=tuple(context),
        raw_hunk=raw_hunk,
        is_new_file=is_new,
        is_deleted_file=is_deleted,
    )


def hunks_to_text(hunks: Sequence[FileHunk]) -> str:
    """Re-serialise a list of hunks to a pseudo-unified-diff text.

    Used by the model to turn multiple hunks into one model-input string.
    """
    parts: list[str] = []
    for h in hunks:
        parts.append(h.raw_hunk)
    return "\n\n".join(parts)


def hunk_added_line_range(hunk: FileHunk) -> tuple[int, int]:
    """Return (line_start, line_end) for the classifier finding default.

    Uses the new-file start as the anchor, and the last added line's new
    number as the end (or ``new_start`` if there are no added lines).
    """
    if hunk.added_lines:
        end = hunk.added_lines[-1].new_line or hunk.new_start
    else:
        end = hunk.new_start
    return hunk.new_start, max(end, hunk.new_start)