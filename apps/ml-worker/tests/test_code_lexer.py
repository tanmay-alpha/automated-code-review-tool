"""Comment and string masking used by the rule engine."""

from __future__ import annotations

import pytest

from app.code_lexer import INTERPOLATION, LineLexer


def _lex(language: str, *lines: str):
    lexer = LineLexer(language)
    return [lexer.line(line) for line in lines]


def test_python_comments_strings_and_prefixes() -> None:
    [line] = _lex("python", "x = f'a{b}' + r\"c\\\"d\" + 'e'  # print(x)")
    assert line.code == "x = f'" + INTERPOLATION + "' + r\"\" + ''  "
    assert line.strings == ("a{b}", 'c\\"d', "e")
    assert line.comment == "print(x)"
    [escaped] = _lex("python", "s = f'{{literal}}'")
    assert escaped.code == "s = f''"


def test_python_triple_quoted_strings_span_lines() -> None:
    lines = _lex("python", 'doc = """start', "print(x)", 'end""" + y', "print(z)")
    assert [line.code for line in lines] == ['doc = """', "", '""" + y', "print(z)"]


def test_c_family_block_comments_and_templates() -> None:
    lines = _lex(
        "typescript",
        "a(); /* one */ b(); /* two",
        " console.log(x)",
        "*/ c(`v=${v}`); d('x') // e()",
    )
    assert lines[0].code == "a();   b(); "
    assert lines[1].code == ""
    assert lines[2].code == " c(`" + INTERPOLATION + "`); d('') "
    assert lines[2].comment == "e()"


def test_java_text_blocks_and_char_literals() -> None:
    lines = _lex("java", 'String q = """', "  SELECT 1", '  """;', "char c = '\"';")
    assert [line.code for line in lines] == ['String q = """', "", '""";', "char c = '';"]


def test_unterminated_single_line_string_resets_at_end_of_line() -> None:
    lines = _lex("javascript", 'const s = "open', "console.log(x);")
    assert lines[1].code == "console.log(x);"


def test_unknown_language_is_rejected() -> None:
    with pytest.raises(ValueError):
        LineLexer("go")
