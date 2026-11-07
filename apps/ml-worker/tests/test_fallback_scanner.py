"""Rule-engine behaviour for the deterministic fallback detector."""

from __future__ import annotations

from app.fallback_scanner import fallback_findings
from app.taxonomy import load_taxonomy


def _diff(path: str, *added: str) -> str:
    body = "\n".join(f"+{line}" for line in added)
    return f"diff --git a/{path} b/{path}\n@@ -0,0 +1,{len(added)} @@\n{body}\n"


def _ids(diff: str) -> set[str]:
    findings, _, _ = fallback_findings(diff)
    return {finding.antiPattern for finding in findings}


def test_broad_exception_forms_are_detected() -> None:
    for line in ("except:", "except Exception:", "except Exception as exc:", "except BaseException as e:"):
        assert "RELIABILITY_BROAD_EXCEPTION" in _ids(_diff("a.py", "try:", "    x()", line, "    pass")), line


def test_specific_exception_is_not_broad() -> None:
    diff = _diff("a.py", "try:", "    x()", "except ValueError as exc:", "    pass")
    assert "RELIABILITY_BROAD_EXCEPTION" not in _ids(diff)


def test_java_catch_exception_is_broad() -> None:
    diff = _diff("A.java", "try { run(); }", "catch (Exception e) { }")
    assert "RELIABILITY_BROAD_EXCEPTION" in _ids(diff)


def test_named_constant_is_not_a_magic_number() -> None:
    assert "READABILITY_MAGIC_NUMBER" not in _ids(_diff("a.py", "MAX_RETRIES = 50"))
    assert "READABILITY_MAGIC_NUMBER" not in _ids(
        _diff("A.java", "private static final int LIMIT = 100;")
    )
    assert "READABILITY_MAGIC_NUMBER" in _ids(_diff("a.py", "if retries > 50:"))


def test_findings_are_localized_and_use_full_taxonomy_text() -> None:
    findings, hunks, version = fallback_findings(_diff("svc.py", "x = 1", "print(x)"))
    assert hunks == 1
    assert version == load_taxonomy().version
    [finding] = findings
    assert finding.antiPattern == "MAINTAINABILITY_PRINT_STATEMENT"
    assert finding.filePath == "svc.py"
    assert (finding.lineStart, finding.lineEnd) == (2, 2)
    expected = next(e for e in load_taxonomy().entries if e.id == finding.antiPattern)
    assert finding.explanation == " ".join(expected.description.split())


def test_dict_get_in_loop_is_not_n_plus_one() -> None:
    diff = _diff("a.py", "for key in keys:", "    value = cache.get(key)", "    totals.get(key, 0)")
    assert "PERFORMANCE_N_PLUS_ONE" not in _ids(diff)


def test_db_and_http_calls_in_loop_are_n_plus_one() -> None:
    for call in (
        "    db.execute(sql, (uid,))",
        "    User.objects.get(id=uid)",
        "    requests.get(url, timeout=5)",
        "    repo.findByOwnerId(uid)",
    ):
        assert "PERFORMANCE_N_PLUS_ONE" in _ids(_diff("a.py", "for uid in ids:", call)), call


def test_non_code_files_are_not_scanned_in_diff_mode() -> None:
    docs = _diff("docs/README.md", "Set timeout to 300 seconds.", "print(x)")
    findings, hunks, _ = fallback_findings(docs)
    assert findings == []
    assert hunks == 1  # nothing scanned; the response still reports the minimum of 1
    mixed = docs + _diff("app.py", "print(x)")
    findings, hunks, _ = fallback_findings(mixed)
    assert {f.filePath for f in findings} == {"app.py"}
    assert hunks == 1


def test_prose_comment_block_is_not_commented_code() -> None:
    python = _diff(
        "a.py",
        "# Retry the upstream call a few times before giving up,",
        "# because the payment provider occasionally drops the",
        "# first connection after a deploy.",
        "charge()",
    )
    java = _diff(
        "A.java",
        "// Retry the upstream call a few times before giving up,",
        "// because the payment provider occasionally drops the",
        "// first connection after a deploy.",
        "charge();",
    )
    for diff in (python, java):
        assert "MAINTAINABILITY_COMMENTED_CODE" not in _ids(diff)


def test_commented_out_function_is_commented_code() -> None:
    python = _diff(
        "a.py",
        "# def total(items):",
        "#     subtotal = sum(items)",
        "#     return subtotal",
    )
    java = _diff(
        "A.java",
        "// public int total(int[] items) {",
        "//     int subtotal = sum(items);",
        "//     return subtotal;",
        "// }",
    )
    js = _diff(
        "a.js",
        "// function total(items) {",
        "//   const subtotal = sum(items);",
        "//   return subtotal;",
        "// }",
    )
    for diff in (python, java, js):
        assert "MAINTAINABILITY_COMMENTED_CODE" in _ids(diff)
