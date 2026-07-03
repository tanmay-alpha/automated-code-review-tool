from __future__ import annotations
from fastapi.testclient import TestClient

SECRET_HEADER = {"X-ML-Worker-Secret": "testsecret"}

def test_empty_diff_after_filtering(client: TestClient):
    """A diff that looks non-empty but is all header lines should not crash"""
    header_only_diff = "@@ -1,3 +1,3 @@\n--- a/file.py\n+++ b/file.py"
    response = client.post(
        "/ml/review", 
        json={"diff": header_only_diff, "language": "python", "mode": "diff"}, 
        headers=SECRET_HEADER
    )
    # The API will call the fake model's predict which returns []
    # If the app doesn't crash on parsing/filtering, it should return 200
    assert response.status_code == 200

def test_unicode_in_diff(client: TestClient):
    """Code with unicode characters should not crash tokenizer"""
    unicode_diff = "+# 你好世界\n+password = '密码123'"
    response = client.post(
        "/ml/review", 
        json={"diff": unicode_diff, "language": "python", "mode": "diff"}, 
        headers=SECRET_HEADER
    )
    assert response.status_code == 200

def test_null_bytes_in_diff(client: TestClient):
    """Null bytes in diff should be handled gracefully"""
    null_diff = "+code\x00null\x00bytes"
    response = client.post(
        "/ml/review", 
        json={"diff": null_diff, "language": "python", "mode": "diff"}, 
        headers=SECRET_HEADER
    )
    assert response.status_code in [200, 422]

def test_extremely_long_single_line(client: TestClient):
    """A single line diff that is 10,000 characters should not OOM"""
    long_line = "+" + "x = " * 2500  # 10,000 char line
    response = client.post(
        "/ml/review", 
        json={"diff": long_line, "language": "python", "mode": "diff"}, 
        headers=SECRET_HEADER
    )
    assert response.status_code in [200, 422]

def test_max_payload_size_enforced(client: TestClient):
    """Diff at exactly max_length should work, just over should be rejected"""
    at_max = "+" + "x = 1\n" * (200_000 // 7)  # approximately 200K chars
    
    # At limit: should process (or return 422 if tokenization fails gracefully)
    r1 = client.post(
        "/ml/review", 
        json={"diff": at_max[:200_000], "language": "python"}, 
        headers=SECRET_HEADER
    )
    assert r1.status_code in [200, 422]
    
    # Over limit: must be 422 (Pydantic validation)
    r2 = client.post(
        "/ml/review", 
        json={"diff": "x" * 200_001, "language": "python"}, 
        headers=SECRET_HEADER
    )
    assert r2.status_code == 422
