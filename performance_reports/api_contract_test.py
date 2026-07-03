import httpx
import sys

BASE = "http://localhost:8080"
ML_BASE = "http://localhost:8000"
ML_SECRET = "localtestsecret123"

tests = [
  # ML Worker
  ("GET", f"{ML_BASE}/ml/health", None, None, 200),
  ("POST", f"{ML_BASE}/ml/review", {"diff": "+x=1", "language": "python", "mode": "diff"}, {"X-ML-Worker-Secret": ML_SECRET}, 200),
  ("POST", f"{ML_BASE}/ml/review", {"diff": "+x=1", "language": "python"}, {}, 403),  # missing secret
  ("POST", f"{ML_BASE}/ml/review", {"diff": "", "language": "python"}, {"X-ML-Worker-Secret": ML_SECRET}, 422),  # empty diff
  
  # Spring Boot - Auth
  ("GET", f"{BASE}/api/auth/me", None, {}, 401),  # no auth
  ("GET", f"{BASE}/api/auth/me", None, {"Authorization": "Bearer invalid.jwt.token"}, 401),
  ("GET", f"{BASE}/api/auth/github", None, {}, 302),  # redirects to GitHub
  
  # Spring Boot - Repos (no auth)
  ("GET", f"{BASE}/api/repos", None, {}, 401),
  ("POST", f"{BASE}/api/repos/connect", {"githubRepoFullName": "test/repo"}, {}, 401),
  ("DELETE", f"{BASE}/api/repos/00000000-0000-0000-0000-000000000001", None, {}, 401),
  
  # Spring Boot - Reviews (no auth)
  ("GET", f"{BASE}/api/reviews/00000000-0000-0000-0000-000000000001", None, {}, 401),
  
  # Spring Boot - Scan (no API key)
  ("POST", f"{BASE}/api/scan/file", {"content": "+x=1", "language": "python", "filePath": "test.py"}, {}, 401),
  
  # Spring Boot - Metrics (no auth)
  ("GET", f"{BASE}/api/metrics/quality-trend?repoId=test&days=30", None, {}, 401),
  
  # Health check (public)
  ("GET", f"{BASE}/actuator/health", None, {}, 200),
  
  # Webhook (no signature)
  ("POST", f"{BASE}/api/webhook/github", {"repository": {"id": 12345}, "action": "opened"}, {"X-GitHub-Event": "pull_request", "X-GitHub-Delivery": "test-delivery-id"}, 401),
]

passed = 0
failed = 0
for method, url, body, headers, expected_status in tests:
  try:
    resp = httpx.request(method, url, json=body, headers=headers, timeout=10, follow_redirects=False)
    if resp.status_code == expected_status or (expected_status == 302 and resp.status_code in [302, 307]):
      print(f"PASS {method} {url.replace(BASE,'').replace(ML_BASE,'')} -> {resp.status_code}")
      passed += 1
    else:
      print(f"FAIL {method} {url.replace(BASE,'').replace(ML_BASE,'')} -> got {resp.status_code} expected {expected_status}")
      failed += 1
  except Exception as e:
    print(f"ERROR {method} {url}: {e}")
    failed += 1

print(f"\nAPI Contract Tests: {passed} passed / {failed} failed")

if failed > 0:
    sys.exit(1)
sys.exit(0)
