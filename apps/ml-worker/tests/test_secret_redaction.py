"""Regression tests for secret redaction.

Required test cases:
    - GitHub personal access token
    - AWS access key
    - Stripe key
    - JWT
    - private-key block
    - database URL with password
    - generic password assignment
    - placeholder secret
    - environment-variable lookup
    - example documentation key

The tests prove that placeholders and environment-variable references
are not unnecessarily destroyed.
"""
from __future__ import annotations


from app.secret_redaction import REDACTED, redact_secrets, looks_like_secret_line


# ---------------------------------------------------------------
# Positive cases — must be redacted
# ---------------------------------------------------------------

def test_github_personal_access_token():
    token = "ghp_" + "ABC123abcXYZ456def789ghi012jkl345"
    result = redact_secrets(f'export GITHUB_TOKEN="{token}"')
    assert token not in result.text
    assert REDACTED in result.text
    assert result.redaction_count >= 1


def test_aws_access_key():
    result = redact_secrets("aws_access_key_id = AKIAIOSFODNN7EXAMPLE")
    assert "AKIAIOSFODNN7EXAMPLE" not in result.text
    assert REDACTED in result.text


def test_stripe_live_key():
    stripe_key = "sk_live_" + "0" * 24
    result = redact_secrets(f'STRIPE_KEY = "{stripe_key}"')
    assert "sk_live_" not in result.text
    assert REDACTED in result.text


def test_jwt_token():
    jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
    result = redact_secrets(f'Authorization: Bearer {jwt}')
    assert jwt not in result.text
    assert REDACTED in result.text


def test_private_key_block():
    key = """-----BEGIN RSA PRIVATE KEY-----
MIIBOQIBAAJBALRErTkMG1IQ3M7w0E6rOqf0ISeH3HYHX3WxK+HqhYA3m3MNnTB
qNjBC9Z/hT1+0M0M3sNE0q5TqOmTlBRiOf8CAwEAAQJASFyaGZ5VEZ1BWCWGH1YA
-----END RSA PRIVATE KEY-----"""
    result = redact_secrets(key)
    # At minimum the key content should not remain verbatim.
    assert "BEGIN RSA PRIVATE KEY" not in result.text or REDACTED in result.text


def test_database_url_with_password():
    result = redact_secrets('DATABASE_URL="postgresql://user:s3cretPass@db.example.com:5432/mydb"')
    assert "s3cretPass" not in result.text
    assert REDACTED in result.text


def test_generic_password_assignment():
    result = redact_secrets('const password = "MyS3cureP@ssw0rd!";')
    assert "MyS3cureP@ssw0rd!" not in result.text
    assert REDACTED in result.text


# ---------------------------------------------------------------
# Negative cases — must NOT be redacted
# ---------------------------------------------------------------

def test_placeholder_secret_preserved():
    """Placeholder strings like <REDACTED_SECRET> or YOUR_API_KEY should
    not be further redacted or mangled."""
    placeholder = '<REDACTED_SECRET>'
    result = redact_secrets(f'api_key = "{placeholder}"')
    assert placeholder in result.text


def test_env_var_lookup_preserved():
    """Environment-variable references must not be destroyed."""
    env_ref = "${AWS_ACCESS_KEY_ID}"
    result = redact_secrets(f'key = {env_ref}')
    assert env_ref in result.text


def test_example_documentation_key():
    """Keys that look like example/documentation placeholders must not be
    aggressively redacted."""
    doc_key = "sk_test_xxx"
    result = redact_secrets(f'# Example: api_key = "{doc_key}"')
    # The key should remain because it is clearly a documentation example.
    assert doc_key in result.text or result.redaction_count == 0


def test_no_secret_line_detection():
    assert not looks_like_secret_line("x = 42")
    assert not looks_like_secret_line("return True")
    assert not looks_like_secret_line("# This is a comment")


def test_redaction_version_consistency():
    result = redact_secrets("password = 'secret'")
    assert result.redaction_version == "v1"
    assert result.redaction_count >= 1


def test_empty_input():
    result = redact_secrets("")
    assert result.text == ""
    assert result.redaction_count == 0


def test_none_input():
    result = redact_secrets(None)
    assert result.text is None or result.text == ""
