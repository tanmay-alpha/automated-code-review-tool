"""Rule-engine behaviour for the deterministic fallback detector."""

from __future__ import annotations

from app.fallback_scanner import _CONFIDENCE, fallback_findings
from app.finding_guidance import EVIDENCE_MAX_CHARS, GUIDANCE, GUIDANCE_MAX_CHARS
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


def test_findings_are_localized_and_carry_reviewer_guidance() -> None:
    findings, hunks, version = fallback_findings(_diff("svc.py", "x = 1", "print(x)"))
    assert hunks == 1
    assert version == load_taxonomy().version
    [finding] = findings
    assert finding.antiPattern == "MAINTAINABILITY_PRINT_STATEMENT"
    assert finding.filePath == "svc.py"
    assert (finding.lineStart, finding.lineEnd) == (2, 2)
    guidance = GUIDANCE[finding.antiPattern]
    assert finding.explanation == guidance.explanation
    assert finding.remediation == guidance.remediation
    assert finding.caveat == guidance.rule_caveat
    assert finding.evidence == "print(x)"


_EVERY_RULE_DIFF = (
    _diff(
        "svc.py",
        'password = "Zx9vQ2mL7pR4tY8wK3nB"',
        'cursor.execute("SELECT * FROM t WHERE id = " + uid)',
        "digest = hashlib.md5(data).hexdigest()",
        "for uid in ids:",
        "    for other in ids:",
        "        db.execute(sql, (uid,))",
        "try:",
        "    run()",
        "except Exception:",
        "    pass",
        "if retries > 50:",
        "    print(retries)",
        "x = " + "a + " * 60 + "a",
        "# def total(items):",
        "#     subtotal = sum(items)",
        "#     return subtotal",
        "",
    )
    + _diff("Client.java", "HttpClient client = HttpClient.newHttpClient();")
)


def test_every_rule_finding_populates_all_guidance_fields() -> None:
    findings, _, _ = fallback_findings(_EVERY_RULE_DIFF)
    rule_ids = {finding.antiPattern for finding in findings}
    # Every rule implemented by the scanner fires on the fixture above.
    assert rule_ids == set(_CONFIDENCE)
    assert "RELIABILITY_MISSING_TIMEOUT" in rule_ids
    for finding in findings:
        payload = finding.model_dump()
        for field in ("explanation", "evidence", "remediation", "caveat"):
            value = payload[field]
            assert isinstance(value, str) and value.strip(), (finding.antiPattern, field)
        assert len(payload["evidence"]) <= EVIDENCE_MAX_CHARS
        assert len(payload["remediation"]) <= GUIDANCE_MAX_CHARS
        assert len(payload["caveat"]) <= GUIDANCE_MAX_CHARS


def test_evidence_is_the_triggering_added_line() -> None:
    findings, _, _ = fallback_findings(_EVERY_RULE_DIFF)
    by_rule = {finding.antiPattern: finding for finding in findings}
    assert by_rule["SECURITY_WEAK_CRYPTO"].evidence == "digest = hashlib.md5(data).hexdigest()"
    assert by_rule["PERFORMANCE_N_PLUS_ONE"].evidence == "db.execute(sql, (uid,))"
    assert by_rule["PERFORMANCE_QUADRATIC_LOOP"].evidence == "for other in ids:"
    assert by_rule["MAINTAINABILITY_COMMENTED_CODE"].evidence == "# def total(items):"
    long_line = by_rule["READABILITY_LONG_METHOD"].evidence
    assert long_line is not None
    assert len(long_line) == EVIDENCE_MAX_CHARS and long_line.endswith("…")


def test_secret_evidence_never_echoes_the_value() -> None:
    for name in ("password", "api_key", "secret_key", "private_key", "myapi_key", "auth_token"):
        value = "Zx9vQ2mL7pR4tY8wK3nB"
        findings, _, _ = fallback_findings(_diff("a.py", f'{name} = "{value}"'))
        [finding] = [f for f in findings if f.antiPattern == "SECURITY_HARDCODED_SECRET"]
        assert finding.evidence is not None
        assert value not in finding.evidence, name
        assert "<REDACTED_SECRET>" in finding.evidence


def test_guidance_covers_the_whole_taxonomy() -> None:
    assert set(GUIDANCE) == set(load_taxonomy().ids())
    for anti_pattern, guidance in GUIDANCE.items():
        assert guidance.explanation.strip(), anti_pattern
        assert 0 < len(guidance.remediation) <= GUIDANCE_MAX_CHARS, anti_pattern
        if guidance.rule_caveat is not None:
            assert 0 < len(guidance.rule_caveat) <= GUIDANCE_MAX_CHARS, anti_pattern


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


def test_sql_concatenation_and_fstrings_remain_detected() -> None:
    for expression in ("query(sql + user)", 'execute(f"SELECT {user}")', "query(x); execute(y + z)"):
        assert "SECURITY_SQL_INJECTION" in _ids(_diff("a.py", expression))
    assert "SECURITY_SQL_INJECTION" not in _ids(_diff("a.py", 'query("SELECT ?", user)'))


def test_annotated_constant_and_parenthesized_exception_remain_detected() -> None:
    assert "READABILITY_MAGIC_NUMBER" not in _ids(_diff("a.py", "LIMIT: int = 100"))
    for expression in ("except (Exception):", "except ( Exception ) as exc:", "except Exception   :"):
        assert "RELIABILITY_BROAD_EXCEPTION" in _ids(_diff("a.py", expression))


def test_adversarial_lines_at_request_limit_are_bounded() -> None:
    import time

    for text in (
        ("query(" * 40_000)[:200_000],
        "except " + " " * 199_992 + "!",
        "except Exception" + " " * 199_983 + "!",
        ("A:" * 99_999) + "99",
        "# x\n# x\n#" + " " * 199_988,
        ("while x:\n" * 25_000)[:200_000],
    ):
        started = time.perf_counter()
        findings, _, _ = fallback_findings(text, mode="file", language="python")
        assert isinstance(findings, list)
        assert time.perf_counter() - started < 5.0


def _lines(diff: str, anti_pattern: str) -> list[tuple[int | None, int | None]]:
    findings, _, _ = fallback_findings(diff)
    return [(f.lineStart, f.lineEnd) for f in findings if f.antiPattern == anti_pattern]


def test_print_inside_comments_and_strings_is_ignored() -> None:
    # Reproduction from main: both decoys used to be reported.
    diff = _diff(
        "src/api/profile.ts",
        "// Docs: never call console.log(token) in production code.",
        'export const HELP_TEXT = "Use console.log( only while debugging";',
        "/*",
        " * console.log(formatBytes(1024))",
        " */",
        'console.log("profile response", res.status);',
    )
    assert _lines(diff, "MAINTAINABILITY_PRINT_STATEMENT") == [(6, 6)]
    assert _lines(diff, "READABILITY_MAGIC_NUMBER") == []
    python = _diff("a.py", '"""Usage:', "    print(report(1024))", '"""', "print(x)")
    assert _lines(python, "MAINTAINABILITY_PRINT_STATEMENT") == [(4, 4)]


def test_weak_crypto_and_sql_in_comments_or_strings_are_ignored() -> None:
    diff = _diff(
        "a.js",
        "// md5(...) is not used here",
        'const HELP = "Run md5(file) to compare downloads";',
        'logger.info("will execute(" + name + ")");',
    )
    findings, _, _ = fallback_findings(diff)
    assert findings == []


def test_test_files_skip_print_and_magic_numbers_but_keep_real_tokens() -> None:
    diff = _diff(
        "tests/test_billing.py",
        'API_TOKEN = "test-token-0123456789abcdef"',
        "assert total(250) == 225",
        'print("checking")',
        'access_key = "AKIAIOSFODNN7REALKEY"',
    )
    ids = [f.antiPattern for f in fallback_findings(diff)[0]]
    assert ids == ["SECURITY_HARDCODED_SECRET"]
    assert _lines(diff, "SECURITY_HARDCODED_SECRET") == [(4, 4)]
    for path in ("src/__tests__/a.ts", "web/a.spec.js", "src/test/java/ATest.java", "pkg/x_test.py"):
        assert "READABILITY_MAGIC_NUMBER" not in _ids(_diff(path, "x = total(250);"))
    assert "READABILITY_MAGIC_NUMBER" in _ids(_diff("src/latest.py", "x = total(250)"))


def test_broad_exception_that_rethrows_is_not_reported() -> None:
    python = _diff(
        "a.py",
        "try:",
        "    run()",
        "except Exception as exc:",
        "    logger.exception('failed: %s', exc)",
        "    raise",
        "try:",
        "    run()",
        "except Exception:",
        "    return 0",
    )
    assert _lines(python, "RELIABILITY_BROAD_EXCEPTION") == [(8, 8)]
    java = _diff(
        "A.java",
        "try {",
        "    run();",
        "} catch (Exception e) {",
        "    throw new IllegalStateException(e);",
        "}",
    )
    assert _lines(java, "RELIABILITY_BROAD_EXCEPTION") == []


def test_magic_number_exemptions() -> None:
    assert _ids(_diff("a.py", "if resp.status_code != 200:")) == set()
    assert _ids(_diff("a.py", 'return {}, 404')) == set()
    assert _ids(_diff("a.py", 'message = "retry after 30 seconds"')) == set()
    assert "READABILITY_MAGIC_NUMBER" in _ids(_diff("a.py", "if len(batch) > 250:"))


def test_explicit_stream_print_and_long_literal_constants() -> None:
    assert _ids(_diff("cli.py", 'print("done", file=sys.stderr)')) == set()
    url = 'DOCS_URL = "https://docs.example.com/' + "a" * 220 + '"'
    assert _ids(_diff("a.py", url)) == set()
    call = "send(" + ", ".join(f"field{i}=value{i}" for i in range(30)) + ")"
    assert "READABILITY_LONG_METHOD" in _ids(_diff("a.py", call))


def test_only_rule_languages_are_scanned() -> None:
    go = _diff("main.go", "for i := 0; i < 100; i++ {", '    fmt.Println("tick")', "}")
    findings, hunks, _ = fallback_findings(go)
    assert findings == [] and hunks == 1
    # File mode uses the request language, or the path when it is "unknown".
    ts, _, _ = fallback_findings("console.log(x);", mode="file", language="unknown", file_path="a.ts")
    assert [f.antiPattern for f in ts] == ["MAINTAINABILITY_PRINT_STATEMENT"]
    none, _, _ = fallback_findings("console.log(x);", mode="file", language="unknown")
    assert none == []


def test_missing_timeout_rule() -> None:
    flagged = {
        "a.py": ["resp = requests.get(url)"],
        "b.py": ["urllib.request.urlopen(url)"],
        "c.js": ["const r = await fetch(url);"],
        "d.ts": ["const r = await axios.post(url, body);"],
        "E.java": ["HttpClient c = HttpClient.newHttpClient();"],
    }
    for path, lines in flagged.items():
        assert "RELIABILITY_MISSING_TIMEOUT" in _ids(_diff(path, *lines)), path
    clean = {
        "a.py": ["resp = requests.get(url, timeout=5)"],
        "b.py": ["resp = requests.get(url, **options)"],
        "c.py": ["resp = httpx.get(url)"],
        "d.js": ["const r = await fetch(url, { signal: AbortSignal.timeout(5000) });"],
        "e.js": ["const r = await this.fetch(url);"],
        "f.js": ["const r = await axios.get(url, { timeout: 5000 });"],
        "g.js": ['// await fetch("https://example.com")'],
        "h.py": ['requests.get(url, timeout=TIMEOUT)  # "requests.get(" in a comment'],
    }
    for path, lines in clean.items():
        assert "RELIABILITY_MISSING_TIMEOUT" not in _ids(_diff(path, *lines)), path


def test_missing_timeout_spans_a_multi_line_call() -> None:
    open_call = _diff("a.ts", "const res = await fetch(url, {", "  headers,", "});")
    assert _lines(open_call, "RELIABILITY_MISSING_TIMEOUT") == [(1, 3)]
    with_signal = _diff("a.ts", "const res = await fetch(url, {", "  signal,", "});")
    assert _lines(with_signal, "RELIABILITY_MISSING_TIMEOUT") == []


def test_sql_building_forms() -> None:
    flagged = [
        ("a.js", ["db.query(`SELECT * FROM t WHERE s = '${status}'`);"]),
        ("a.py", ["cursor.execute(\"SELECT * FROM t WHERE n LIKE '%s'\" % term)"]),
        ("a.py", ['cursor.execute("SELECT * FROM t WHERE n = {}".format(term))']),
        ("A.java", ['String sql = "UPDATE users SET a = 1 WHERE s = \'" + s + "\'";']),
        ("A.java", ['connection.prepareStatement("SELECT COUNT(*) FROM " + table);']),
    ]
    for path, lines in flagged:
        assert "SECURITY_SQL_INJECTION" in _ids(_diff(path, *lines)), lines
    clean = [
        ("a.js", ['db.query("SELECT * FROM users WHERE id = $1", [id]);']),
        ("a.py", ['cursor.execute("SELECT * FROM t WHERE id = %s", (uid,))']),
        ("a.py", ['# cursor.execute("SELECT * FROM t WHERE n = " + name)']),
        ("a.js", ["const label = `SELECT ${n} items`;"]),
    ]
    for path, lines in clean:
        assert "SECURITY_SQL_INJECTION" not in _ids(_diff(path, *lines)), lines


def test_weak_algorithm_names_passed_to_crypto_apis() -> None:
    for path, line in (
        ("A.java", 'MessageDigest.getInstance("MD5");'),
        ("A.java", 'Cipher.getInstance("DES/ECB/PKCS5Padding");'),
        ("a.js", 'crypto.createHash("md5").update(x);'),
        ("a.py", 'hashlib.new("sha1", data)'),
    ):
        assert "SECURITY_WEAK_CRYPTO" in _ids(_diff(path, line)), line
    for path, line in (
        ("A.java", 'MessageDigest.getInstance("SHA-256");'),
        ("a.js", 'crypto.createHmac("sha1", key);'),
        ("a.py", "hashlib.md5(content, usedforsecurity=False).hexdigest()"),
    ):
        assert "SECURITY_WEAK_CRYPTO" not in _ids(_diff(path, line)), line


def test_n_plus_one_requires_the_call_inside_the_loop_body() -> None:
    after_loop = _diff("a.py", "for x in xs:", "    total += x", "rows = db.execute(sql)")
    assert "PERFORMANCE_N_PLUS_ONE" not in _ids(after_loop)
    # A loop on a context line still encloses an added per-iteration call.
    context_loop = (
        "diff --git a/a.py b/a.py\n@@ -1,2 +1,3 @@\n"
        " for order in orders:\n"
        "     total += order.amount\n"
        "+    requests.post(url, json=order, timeout=5)\n"
    )
    assert _lines(context_loop, "PERFORMANCE_N_PLUS_ONE") == [(1, 3)]
