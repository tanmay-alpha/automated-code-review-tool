"""Separate code from comments and string literals, one source line at a time.

Rules that look for calls (``console.log(``, ``md5(``, ``execute(``) must not
fire on the same text inside a comment or a string. :class:`LineLexer` keeps
block-comment and multi-line-string state across the lines of one hunk and
returns, per line, the code with comments removed and string bodies emptied,
plus the string contents for rules that need them (SQL text, algorithm names).

This is a tokenizer, not a parser: JavaScript regex literals, nested template
expressions and strings opened before the hunk starts are not modelled.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

# Emptied f-strings / template literals that interpolated a value keep this
# marker, so ``execute(f"…{x}…")`` still reads as ``execute(f"{}")``.
INTERPOLATION = "{}"

_TOKENS = {
    "python": re.compile(r"#|\"\"\"|'''|[\"']"),
    "javascript": re.compile(r"//|/\*|[`\"']"),
    "typescript": re.compile(r"//|/\*|[`\"']"),
    "java": re.compile(r"//|/\*|\"\"\"|[\"']"),
}
_MULTILINE_DELIMITERS = frozenset({'"""', "'''", "`"})
_CLOSERS = {
    delimiter: re.compile(r"\\.|" + re.escape(delimiter), re.S)
    for delimiter in ('"', "'", "`", '"""', "'''")
}
_PY_PREFIX_CHARS = frozenset("rRbBuUfF")


@dataclass(frozen=True)
class LexedLine:
    code: str
    strings: tuple[str, ...]
    comment: str


def _find_close(text: str, start: int, delimiter: str) -> int:
    closer = _CLOSERS[delimiter]
    position = start
    while True:
        match = closer.search(text, position)
        if match is None:
            return -1
        if match.group() == delimiter:
            return match.start()
        position = match.end()


def _python_prefix(text: str, quote_at: int) -> str:
    start = quote_at
    while start > 0 and quote_at - start < 2 and text[start - 1] in _PY_PREFIX_CHARS:
        start -= 1
    if start == quote_at or (start > 0 and (text[start - 1].isalnum() or text[start - 1] == "_")):
        return ""
    return text[start:quote_at]


class LineLexer:
    """Stateful lexer for consecutive lines of one file."""

    def __init__(self, language: str) -> None:
        if language not in _TOKENS:
            raise ValueError(f"unsupported language {language!r}")
        self._python = language == "python"
        self._tokens = _TOKENS[language]
        self._in_block_comment = False
        self._open_string: str | None = None

    def line(self, text: str) -> LexedLine:
        code: list[str] = []
        strings: list[str] = []
        comment = ""
        index = 0

        if self._open_string is not None:
            delimiter = self._open_string
            end = _find_close(text, 0, delimiter)
            if end < 0:
                return LexedLine("", (text,), "")
            strings.append(text[:end])
            code.append(delimiter)
            index = end + len(delimiter)
            self._open_string = None
        elif self._in_block_comment:
            end = text.find("*/")
            if end < 0:
                return LexedLine("", (), text)
            comment = text[:end]
            index = end + 2
            self._in_block_comment = False

        while index < len(text):
            match = self._tokens.search(text, index)
            if match is None:
                code.append(text[index:])
                break
            code.append(text[index : match.start()])
            token = match.group()
            if token in ("#", "//"):
                comment = f"{comment} {text[match.end():]}".strip()
                break
            if token == "/*":
                end = text.find("*/", match.end())
                if end < 0:
                    comment = f"{comment} {text[match.end():]}".strip()
                    self._in_block_comment = True
                    break
                comment = f"{comment} {text[match.end():end]}".strip()
                code.append(" ")
                index = end + 2
                continue

            prefix = _python_prefix(text, match.start()) if self._python else ""
            end = _find_close(text, match.end(), token)
            if end < 0:
                strings.append(text[match.end() :])
                if token in _MULTILINE_DELIMITERS:
                    self._open_string = token
                    code.append(token)
                else:
                    code.append(token + token)  # unterminated single-line literal
                break
            content = text[match.end() : end]
            strings.append(content)
            interpolated = (
                ("f" in prefix.lower() and "{" in content.replace("{{", ""))
                or (token == "`" and "${" in content)
            )
            code.append(token + (INTERPOLATION if interpolated else "") + token)
            index = end + len(token)

        return LexedLine("".join(code), tuple(strings), comment)
