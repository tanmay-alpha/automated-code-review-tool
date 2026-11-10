"""Exercise the real GitHub -> API -> worker -> GitHub review loop.

Never use a production database: this runner recreates the configured database.
Only the disposable sandbox named by --repo is pushed to. No OAuth browser flow
or fabricated webhook payload is used. Exit 1 means failed assertions; exit 2
means an infrastructure/setup error. Evidence is written in either case.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import uuid
from datetime import datetime, timezone
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
import psycopg2
from psycopg2 import sql
from psycopg2.extras import RealDictCursor

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SCENARIOS = ROOT / "examples" / "demo-repo" / "scenarios"
SECRETS: list[str] = []


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def clean(value):
    if isinstance(value, str):
        for secret in sorted(SECRETS, key=len, reverse=True):
            if secret:
                value = value.replace(secret, "[REDACTED]")
        return re.sub(r"\b(?:gh[pousr]_[A-Za-z0-9_]+|github_pat_[A-Za-z0-9_]+)\b", "[REDACTED]", value)
    if isinstance(value, dict):
        return {k: clean(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [clean(v) for v in value]
    return value


def command(args, *, cwd=None, env=None, timeout=120) -> str:
    result = subprocess.run([str(x) for x in args], cwd=cwd, env=env, text=True,
                            encoding="utf-8", errors="replace", capture_output=True, timeout=timeout)
    if result.returncode:
        # Arguments may contain SQL or credentials; do not echo them.
        raise RuntimeError(f"{Path(str(args[0])).name} exited {result.returncode}: "
                           + clean(result.stderr[-4000:] or result.stdout[-4000:]))
    return result.stdout.strip()


def gh(path: str, *, method="GET", paginate=False):
    args = ["gh", "api", path, "--method", method]
    if paginate:
        args += ["--paginate", "--slurp"]
    text = command(args)
    result = json.loads(text) if text else None
    if paginate:
        return [item for page in result for item in page]
    return result


def http(url: str, *, bearer=None, body=None):
    headers = {"Accept": "application/json"}
    if bearer:
        headers["Authorization"] = "Bearer " + bearer
    data = None if body is None else json.dumps(body).encode()
    if data is not None:
        headers["Content-Type"] = "application/json"
    req = Request(url, data=data, headers=headers)
    try:
        with urlopen(req, timeout=10) as response:
            raw = response.read().decode()
            return response.status, json.loads(raw) if raw else None
    except HTTPError as exc:
        raw = exc.read().decode(errors="replace")
        try:
            payload = json.loads(raw)
        except ValueError:
            payload = {"error": raw[:1000]}
        return exc.code, payload


def poll(check, timeout, label, interval=1):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            result = check()
            if result:
                return result
        except (OSError, URLError, psycopg2.OperationalError) as exc:
            last = type(exc).__name__
        time.sleep(interval)
    raise TimeoutError(f"Timed out waiting for {label}" + (f" ({last})" if last else ""))


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def b64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode()


def access_jwt(secret, user_id, login, token_version=0):
    # JwtService: Base64 decoder first, raw UTF-8 fallback; JJWT selects HS256
    # for our exactly 32-byte key. Times are NumericDate seconds, not millis.
    try:
        key = base64.b64decode(secret, validate=True)
    except ValueError:
        key = secret.encode()
    issued = int(time.time())
    claims = {"sub": str(user_id), "type": "access", "username": login, "ver": token_version,
              "jti": str(uuid.uuid4()), "iat": issued, "exp": issued + 900}
    parts = [b64url(json.dumps({"alg": "HS256"}, separators=(",", ":")).encode()),
             b64url(json.dumps(claims, separators=(",", ":")).encode())]
    signed = ".".join(parts)
    token = signed + "." + b64url(hmac.new(key, signed.encode(), hashlib.sha256).digest())
    SECRETS.append(token)
    return token


def encrypted_token(key: bytes, token: str):
    # EncryptionService: standard base64(12-byte IV || ciphertext || 16-byte tag).
    iv = os.urandom(12)
    return base64.b64encode(iv + AESGCM(key).encrypt(iv, token.encode(), None)).decode()


class Runner:
    def __init__(self, args):
        self.args = args
        self.out = HERE / "out" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S-%fZ")
        self.out.mkdir(parents=True)
        self.processes = []
        self.logs = []
        self.api = None
        self.repo_id = None
        self.hook_id = None
        self.jwt_secret = base64.b64encode(os.urandom(32)).decode()
        self.encryption_key = os.urandom(32)
        self.worker_secret = base64.b64encode(os.urandom(32)).decode()
        SECRETS.extend([self.jwt_secret, base64.b64encode(self.encryption_key).decode(),
                        self.worker_secret, args.pg_password, args.redis_password])
        self.user_id = None
        self.login = None
        self.report = {"startedAt": now(), "repo": args.repo,
                       "repoUrl": f"https://github.com/{args.repo}",
                       "apiJarGitSha": args.api_sha, "assertions": [], "scenarios": [],
                       "errors": [], "processes": [], "pollIntervalSeconds": args.poll_interval,
                       "observationSeconds": args.observe_seconds}

    def db(self, database=None):
        return psycopg2.connect(host=self.args.pg_host, port=self.args.pg_port,
                                user=self.args.pg_user, password=self.args.pg_password,
                                dbname=database or self.args.pg_database, connect_timeout=5)

    def reset_database(self):
        conn = self.db("postgres")
        conn.autocommit = True
        try:
            with conn.cursor() as cur:
                cur.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                            "WHERE datname=%s AND pid<>pg_backend_pid()", (self.args.pg_database,))
                cur.execute(sql.SQL("DROP DATABASE IF EXISTS {} ").format(sql.Identifier(self.args.pg_database)))
                cur.execute(sql.SQL("CREATE DATABASE {} OWNER {} ").format(
                    sql.Identifier(self.args.pg_database), sql.Identifier(self.args.pg_user)))
        finally:
            conn.close()

    def launch(self, name, args, env=None, cwd=None):
        path = self.out / (name + ".log")
        log = path.open("wb")
        self.logs.append(log)
        options = {"creationflags": subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == "nt" else {"start_new_session": True}
        proc = subprocess.Popen([str(a) for a in args], cwd=cwd, env=env,
                                stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, **options)
        self.processes.append((name, proc))
        self.report["processes"].append({"name": name, "pid": proc.pid, "log": path.name})
        self.save()
        return proc

    def alive(self):
        for name, proc in self.processes:
            if proc.poll() is not None:
                raise RuntimeError(f"{name} exited {proc.returncode}; see sanitized {name}.log")

    def api_call(self, path, body=None):
        bearer = access_jwt(self.jwt_secret, self.user_id, self.login)
        return http(self.api + path, bearer=bearer, body=body)

    def start(self):
        token = command(["gh", "auth", "token"])
        SECRETS.append(token)
        user = gh("user")
        self.login = user["login"]
        self.user_id = str(uuid.uuid4())
        api_port, worker_port = free_port(), free_port()
        while worker_port == api_port:
            worker_port = free_port()
        self.api = f"http://127.0.0.1:{api_port}"
        self.report["apiUrl"] = self.api
        self.report["workerUrl"] = f"http://127.0.0.1:{worker_port}"
        snapshot = self.out / "api.jar"
        shutil.copy2(self.args.api_jar, snapshot)
        self.report["apiJarSha256"] = hashlib.file_digest(snapshot.open("rb"), "sha256").hexdigest()
        self.reset_database()
        env = os.environ.copy()
        env.update(MODEL_NAME="none", ML_WORKER_SECRET=self.worker_secret, PYTHONUNBUFFERED="1")
        self.launch("worker", [self.args.python, "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", worker_port], env, self.args.worker_dir)
        def worker_ready():
            self.alive()
            status, payload = http(f"http://127.0.0.1:{worker_port}/ml/health")
            if status == 200:
                self.report["workerHealth"] = payload
                return True
            return False
        poll(worker_ready, self.args.startup_timeout, "worker HTTP readiness")
        self.launch("tunnel", [self.args.cloudflared, "tunnel", "--url", self.api, "--no-autoupdate"])
        def tunnel_ready():
            self.alive()
            match = re.search(r"https://[a-z0-9-]+\.trycloudflare\.com", (self.out / "tunnel.log").read_text(errors="replace"))
            return match.group(0) if match else None
        tunnel = poll(tunnel_ready, self.args.startup_timeout, "quick tunnel URL")
        self.report["tunnelUrl"] = tunnel
        env = os.environ.copy()
        env.update(SERVER_PORT=str(api_port), SPRING_DATASOURCE_URL=f"jdbc:postgresql://{self.args.pg_host}:{self.args.pg_port}/{self.args.pg_database}",
                   SPRING_DATASOURCE_USERNAME=self.args.pg_user, SPRING_DATASOURCE_PASSWORD=self.args.pg_password,
                   SPRING_REDIS_HOST=self.args.redis_host, SPRING_DATA_REDIS_PORT=str(self.args.redis_port), SPRING_REDIS_PASSWORD=self.args.redis_password,
                   JWT_SECRET=self.jwt_secret, ENCRYPTION_KEY=base64.b64encode(self.encryption_key).decode(),
                   ML_WORKER_SECRET=self.worker_secret, ML_WORKER_URL=f"http://127.0.0.1:{worker_port}",
                   COOKIE_SECURE="false", GITHUB_CLIENT_ID="e2e-no-oauth", GITHUB_CLIENT_SECRET="e2e-no-oauth",
                   WEBHOOK_CALLBACK_URL=tunnel + "/api/webhook/github")
        self.launch("api", [self.args.java, "-jar", snapshot], env, ROOT)
        def schema_ready():
            self.alive()
            with self.db() as conn, conn.cursor() as cur:
                cur.execute("SELECT to_regclass('public.users'), to_regclass('public.repositories')")
                return all(cur.fetchone())
        poll(schema_ready, self.args.startup_timeout, "Flyway user schema")
        ciphertext = encrypted_token(self.encryption_key, token)
        SECRETS.append(ciphertext)
        with self.db() as conn, conn.cursor() as cur:
            cur.execute("INSERT INTO users(id,github_id,github_username,avatar_url,access_token,created_at,updated_at) "
                        "VALUES(%s,%s,%s,%s,%s,now(),now()) ON CONFLICT(github_id) DO UPDATE SET "
                        "github_username=EXCLUDED.github_username,avatar_url=EXCLUDED.avatar_url," 
                        "access_token=EXCLUDED.access_token,updated_at=now() RETURNING id",
                        (self.user_id, user["id"], self.login, user.get("avatar_url"), ciphertext))
            self.user_id = str(cur.fetchone()[0])
        def api_ready():
            self.alive()
            return self.api_call("/api/repos")[0] == 200
        poll(api_ready, self.args.startup_timeout, "authenticated API readiness")
        def public_ready():
            self.alive()
            return http(tunnel + "/api/auth/me")[0] == 401
        poll(public_ready, self.args.startup_timeout, "public tunnel HTTP readiness", interval=2)
        self.report["readyAt"] = now()
        self.save()

    def assertion(self, name, passed, *, scenario=None, evidence=None, detail=None):
        self.report["assertions"].append({"name": name, "scenario": scenario,
            "passed": passed, "status": "PASS" if passed is True else "FAIL" if passed is False else "BLOCKED",
            "evidence": evidence, "detail": detail, "checkedAt": now()})
        self.save()

    def comments(self, number):
        comments = gh(f"repos/{self.args.repo}/issues/{number}/comments?per_page=100", paginate=True)
        # OAuth summaries use the human account; the exact hidden marker is
        # ReviewCommentFormatter.summaryMarker(repoGithubId, prNumber).
        marker = f"<!-- acrt:summary repo={self.github_repo_id} pr={number} -->"
        return [{"id": c["id"], "url": c["html_url"], "author": c["user"]["login"],
                 "createdAt": c["created_at"], "updatedAt": c["updated_at"],
                 "body": c["body"]} for c in comments if c["user"]["login"] == self.login and
                c["body"].startswith(marker + "\n")]

    def deliveries(self):
        rows = gh(f"repos/{self.args.repo}/hooks/{self.hook_id}/deliveries?per_page=100", paginate=True)
        # Never capture delivery request/response bodies (they contain HMAC and
        # could contain private code). List metadata is sufficient evidence.
        return [{k: row.get(k) for k in ("id", "guid", "delivered_at", "redelivery", "status", "status_code", "event", "action")} for row in rows]

    def queue_state(self, number, sha):
        with self.db() as conn, conn.cursor(cursor_factory=RealDictCursor) as cur:
            cur.execute("SELECT delivery_id,status,attempt_count,last_error,head_sha,completed_at FROM processed_webhooks "
                        "WHERE repo_id=%s AND github_pr_number=%s AND head_sha=%s", (self.repo_id, number, sha))
            return [dict(row) for row in cur.fetchall()]

    def snapshot(self, number, sha, with_github=True):
        code, page = self.api_call(f"/api/repos/{self.repo_id}/prs?size=100")
        if code != 200:
            raise RuntimeError(f"PR list returned HTTP {code}: {clean(page)}")
        rows = page.get("content", []) if isinstance(page, dict) else page
        pr = next((row for row in rows if row["githubPrNumber"] == number), None)
        result = {"observedAt": now(), "sha": sha, "pullRequest": pr, "review": None,
                  "queue": self.queue_state(number, sha)}
        if pr:
            code, review = self.api_call(f"/api/reviews/{pr['id']}")
            result["reviewHttpStatus"] = code
            result["review"] = review
            code, runs = self.api_call(f"/api/reviews/{pr['id']}/runs")
            result["runsHttpStatus"] = code
            result["runs"] = runs if code == 200 else None
        if with_github:
            result["comments"] = self.comments(number)
            result["deliveries"] = self.deliveries()
        return result

    def await_review(self, number, sha):
        deadline = time.monotonic() + self.args.review_timeout
        latest = None
        while time.monotonic() < deadline:
            self.alive()
            latest = self.snapshot(number, sha, with_github=False)
            queue = latest["queue"]
            runs = latest.get("runs") or []
            published = any(run.get("headSha") == sha and
                            (run.get("publication") or {}).get("status") == "published" for run in runs)
            if queue and all(row["status"] in ("completed", "dead_letter", "superseded") for row in queue) and (
                    published or any(row["status"] != "completed" for row in queue)):
                latest = self.snapshot(number, sha)
                latest["timedOut"] = False
                return latest
            time.sleep(self.args.poll_interval)
        latest = self.snapshot(number, sha)
        latest["timedOut"] = True
        return latest

    def push(self, work, branch, phase):
        command(["git", "checkout", "-q", branch], cwd=work)
        command(["git", "-c", "user.name=ACRT E2E", "-c", "user.email=acrt-e2e@users.noreply.github.com",
                 "commit", "--allow-empty", "-q", "-m", f"E2E {phase} {self.report['startedAt']}"], cwd=work)
        sha = command(["git", "rev-parse", "HEAD"], cwd=work)
        command(["git", "push", "-q", "origin", branch], cwd=work)
        return sha

    def assert_findings(self, scenario, snapshot, record):
        branch = scenario["branch"]
        review = snapshot.get("review") or {}
        findings = review.get("findings", [])
        record["findings"] = findings
        reviewed = review.get("status") == "reviewed" and review.get("headSha") == snapshot["sha"]
        self.assertion("review completes for pushed head", reviewed, scenario=branch,
                       evidence=record["prUrl"], detail={"status": review.get("status"), "timedOut": snapshot["timedOut"], "queue": snapshot["queue"]})
        for expected in scenario["expected"]:
            found = any(f.get("antiPattern") == expected["antiPattern"] and f.get("filePath") == expected["file"] for f in findings)
            self.assertion(f"expected {expected['antiPattern']} in {expected['file']}", found if reviewed else None,
                           scenario=branch, evidence=record["prUrl"], detail=expected)
        # Turn the prose fixture decoys into exact rule/file/line checks. The
        # SHA-256 decoy is unchanged base code: the report explicitly notes this
        # is a diff-scope exclusion, not evidence of standalone scanner accuracy.
        decoy_checks = {
            "demo/python-security": [("app/orders.py", 12, 12, "SECURITY_SQL_INJECTION")],
            "demo/typescript-logging": [
                ("web/client.ts", 9, 9, "MAINTAINABILITY_PRINT_STATEMENT"),
                ("web/client.ts", 10, 10, "MAINTAINABILITY_PRINT_STATEMENT"),
                ("web/client.ts", 2, 4, "RELIABILITY_MISSING_TIMEOUT")],
            "demo/java-crypto": [("src/main/java/demo/Checksums.java", 10, 10, "SECURITY_WEAK_CRYPTO")],
        }
        for description, (file, start, end, rule) in zip(scenario["decoys"], decoy_checks[branch], strict=True):
            offending = [f for f in findings if f.get("filePath") == file and f.get("antiPattern") == rule
                         and f.get("lineStart") is not None and f.get("lineEnd") is not None
                         and f["lineStart"] <= end and f["lineEnd"] >= start]
            self.assertion("decoy not flagged: " + description, not offending if reviewed else None,
                           scenario=branch, evidence=record["prUrl"] + f"/files#diff-{hashlib.sha256(file.encode()).hexdigest()}R{start}",
                           detail={"rule": rule, "file": file, "lineStart": start, "lineEnd": end,
                                   "offending": offending, "scope": "unchanged base line excluded from diff" if branch == "demo/java-crypto" else "added code"})

    def redeliver(self, number, sha, snapshot):
        guids = {q["delivery_id"] for q in snapshot["queue"]}
        candidates = [d for d in snapshot["deliveries"] if d["guid"] in guids and d["event"] == "pull_request"]
        if not candidates:
            return {"error": "No GitHub delivery matched the processed delivery GUID", "comments": snapshot["comments"]}
        latest = max(candidates, key=lambda d: d["id"])
        existing_ids = {d["id"] for d in snapshot["deliveries"]}
        requested = now()
        gh(f"repos/{self.args.repo}/hooks/{self.hook_id}/deliveries/{latest['id']}/attempts", method="POST")
        deadline = time.monotonic() + self.args.review_timeout
        observed = None
        while time.monotonic() < deadline:
            rows = self.deliveries()
            observed = next((d for d in rows if d["guid"] == latest["guid"] and d["id"] not in existing_ids and d["redelivery"]), None)
            if observed and observed.get("status_code") is not None:
                break
            time.sleep(max(3, self.args.poll_interval))
        # Observation window is intentionally bounded, not a claim about all
        # future time. Poll comments throughout it, retaining every observation.
        observations = []
        deadline = time.monotonic() + self.args.observe_seconds
        while True:
            comments = self.comments(number)
            observations.append({"observedAt": now(), "commentIds": [c["id"] for c in comments]})
            if time.monotonic() >= deadline:
                break
            time.sleep(min(self.args.poll_interval, max(0, deadline - time.monotonic())))
        after = self.snapshot(number, sha, with_github=False)
        # The webhook intentionally returns an empty 200 for duplicates, not
        # a JSON "duplicate" status. Unchanged durable runs/attempts/completion
        # prove the redelivery was ignored rather than reprocessed.
        ignored = (bool(observed and observed.get("status_code") == 200)
                   and after["queue"] == snapshot["queue"] and after.get("runs") == snapshot.get("runs"))
        return {"requestedAt": requested, "originalDelivery": latest, "redelivery": observed,
                "deliveryEvidenceUrl": f"https://github.com/{self.args.repo}/settings/hooks/{self.hook_id}",
                "observations": observations, "comments": comments, "after": after,
                "disposition": "duplicate/ignored" if ignored else "not proven"}

    def remove_stale_hooks(self):
        # Delete only callbacks recorded by this harness for this sandbox.
        # Other user-installed hooks (including other quick tunnels) are untouched.
        callbacks = set()
        for path in (HERE / "out").glob("*/report.json"):
            if path.parent == self.out:
                continue
            try:
                previous = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                continue
            tunnel = previous.get("tunnelUrl", "")
            if previous.get("repo") == self.args.repo and re.fullmatch(r"https://[a-z0-9-]+\.trycloudflare\.com", tunnel):
                callbacks.add(tunnel + "/api/webhook/github")
        deleted = []
        hooks = gh(f"repos/{self.args.repo}/hooks?per_page=100", paginate=True)
        for hook in hooks:
            callback = hook.get("config", {}).get("url")
            if callback in callbacks:
                gh(f"repos/{self.args.repo}/hooks/{hook['id']}", method="DELETE")
                deleted.append({"id": hook["id"], "callbackUrl": callback, "deletedAt": now()})
        self.report["staleHooksDeleted"] = deleted
        self.save()

    def connection_blocked(self, prs, detail):
        self.assertion("API installs real repository webhook", False,
                       evidence=self.report["repoUrl"] + "/settings/hooks", detail=detail)
        for path in sorted(SCENARIOS.glob("*/scenario.json")):
            scenario = json.loads(path.read_text(encoding="utf-8"))
            pr = next((p for p in prs if p["head"]["ref"] == scenario["branch"]), None)
            url = pr["html_url"] if pr else self.report["repoUrl"] + "/pulls"
            self.report["scenarios"].append({
                "scenario": scenario["branch"], "prNumber": pr["number"] if pr else None,
                "prUrl": url, "blockedBy": "repository webhook installation",
                "findings": None, "comments": None, "deliveries": None})
            names = ["review completes for pushed head"]
            names += [f"expected {e['antiPattern']} in {e['file']}" for e in scenario["expected"]]
            names += ["decoy not flagged: " + decoy for decoy in scenario.get("decoys", [])]
            names += ["exactly one summary after initial push", "GitHub redelivery reaches API",
                      "redelivery creates no second summary", "second push reviewed",
                      "second push keeps one sticky summary"]
            for name in names:
                self.assertion(name, None, scenario=scenario["branch"], evidence=url,
                               detail="Not exercised: API could not install the real GitHub webhook")
        self.save()

    def exercise(self):
        if not self.args.reuse:
            args = [sys.executable, ROOT / "examples/demo-repo/seed_demo_repo.py", "--repo", self.args.repo]
            if self.args.private:
                args.append("--private")
            self.report["seedOutput"] = command(args, timeout=300)
        self.github_repo_id = gh(f"repos/{self.args.repo}")["id"]
        self.report["githubRepoId"] = self.github_repo_id
        prs = gh(f"repos/{self.args.repo}/pulls?state=open&per_page=100", paginate=True)
        self.report["seededPullRequests"] = [
            {"number": pr["number"], "url": pr["html_url"], "branch": pr["head"]["ref"]}
            for pr in prs]
        self.remove_stale_hooks()
        code, repo = self.api_call("/api/repos/connect", {"fullName": self.args.repo})
        self.report["connection"] = {"requestedAt": now(), "httpStatus": code,
                                     "response": repo, "endpoint": "/api/repos/connect"}
        if code not in (200, 201):
            self.connection_blocked(prs, self.report["connection"])
            raise RuntimeError(f"Connect returned HTTP {code}: {clean(repo)}")
        self.repo_id = repo["id"]
        hooks = gh(f"repos/{self.args.repo}/hooks?per_page=100", paginate=True)
        hook = next((h for h in hooks if h.get("config", {}).get("url") == self.report["tunnelUrl"] + "/api/webhook/github"), None)
        if not hook:
            raise RuntimeError("API connect did not install the real callback webhook")
        self.hook_id = hook["id"]
        self.assertion("API installs real repository webhook", True,
                       evidence=f"https://github.com/{self.args.repo}/settings/hooks/{self.hook_id}")
        self.report.update(repoId=self.repo_id, hookId=self.hook_id,
                           hookUrl=f"https://github.com/{self.args.repo}/settings/hooks/{self.hook_id}")
        with tempfile.TemporaryDirectory(prefix="acrt-e2e-") as temporary:
            work = Path(temporary) / "sandbox"
            command(["gh", "repo", "clone", self.args.repo, work], timeout=180)
            for scenario_path in sorted(SCENARIOS.glob("*/scenario.json")):
                scenario = json.loads(scenario_path.read_text())
                pr = next((p for p in prs if p["head"]["ref"] == scenario["branch"]), None)
                if not pr:
                    self.assertion("scenario PR exists", False, scenario=scenario["branch"], evidence=self.report["repoUrl"])
                    continue
                record = {"scenario": scenario["branch"], "prNumber": pr["number"], "prUrl": pr["html_url"], "startedAt": now()}
                self.report["scenarios"].append(record)
                try:
                    baseline = self.comments(pr["number"])
                    record["baselineComments"] = baseline
                    sha = self.push(work, scenario["branch"], "initial")
                    record["initialCommitUrl"] = self.report["repoUrl"] + "/commit/" + sha
                    first = self.await_review(pr["number"], sha)
                    record["initial"] = first
                    self.assert_findings(scenario, first, record)
                    self.assertion("exactly one summary after initial push", len(first["comments"]) == 1,
                                   scenario=scenario["branch"], evidence=[c["url"] for c in first["comments"]], detail={"count": len(first["comments"])})
                    replay = self.redeliver(pr["number"], sha, first)
                    record["redelivery"] = replay
                    delivered = replay.get("redelivery")
                    self.assertion("GitHub redelivery reaches API", bool(delivered and 200 <= (delivered.get("status_code") or 0) < 300),
                                   scenario=scenario["branch"], evidence=self.report["hookUrl"], detail=replay.get("redelivery") or replay.get("error"))
                    self.assertion("redelivery is duplicate/ignored", replay.get("disposition") == "duplicate/ignored",
                                   scenario=scenario["branch"], evidence=self.report["hookUrl"],
                                   detail={"disposition": replay.get("disposition"),
                                           "beforeQueue": first["queue"], "afterQueue": replay.get("after", {}).get("queue")})
                    before_ids = [c["id"] for c in first["comments"]]
                    after_ids = [c["id"] for c in replay["comments"]]
                    no_extra = before_ids == after_ids and all(o["commentIds"] == before_ids for o in replay.get("observations", []))
                    self.assertion("redelivery creates no second summary", no_extra if delivered else None,
                                   scenario=scenario["branch"], evidence=[c["url"] for c in replay["comments"]],
                                   detail={"beforeIds": before_ids, "afterIds": after_ids, "observationSeconds": self.args.observe_seconds})
                    sha = self.push(work, scenario["branch"], "sticky")
                    record["stickyCommitUrl"] = self.report["repoUrl"] + "/commit/" + sha
                    second = self.await_review(pr["number"], sha)
                    record["sticky"] = second
                    reviewed = (second.get("review") or {}).get("status") == "reviewed" and (second.get("review") or {}).get("headSha") == sha
                    self.assertion("second push reviewed", reviewed, scenario=scenario["branch"], evidence=record["stickyCommitUrl"], detail=second["queue"])
                    sticky_ids = [c["id"] for c in second["comments"]]
                    self.assertion("second push keeps one sticky summary", len(sticky_ids) == 1 and sticky_ids == before_ids if reviewed else None,
                                   scenario=scenario["branch"], evidence=[c["url"] for c in second["comments"]],
                                   detail={"initialIds": before_ids, "afterIds": sticky_ids})
                    self.assertion("sticky summary body shows new head SHA",
                                   len(second["comments"]) == 1 and
                                   f"Reviewed commit `{sha[:12]}`." in second["comments"][0]["body"] if reviewed else None,
                                   scenario=scenario["branch"], evidence=[c["url"] for c in second["comments"]],
                                   detail={"headSha": sha, "displayedSha": sha[:12]})
                    expected_heads = {first["sha"], second["sha"]}
                    published_runs = [run for run in second.get("runs") or []
                                      if run.get("status") == "completed"
                                      and (run.get("publication") or {}).get("status") == "published"
                                      and (run.get("publication") or {}).get("publishedHeadSha") == run.get("headSha")
                                      and (run.get("publication") or {}).get("commentUrl") in
                                      [c["url"] for c in second["comments"]]]
                    self.assertion("runs expose both heads with published status",
                                   second.get("runsHttpStatus") == 200 and
                                   expected_heads <= {run.get("headSha") for run in published_runs},
                                   scenario=scenario["branch"], evidence=record["prUrl"],
                                   detail={"runs": second.get("runs"), "httpStatus": second.get("runsHttpStatus")})
                except Exception as exc:
                    record["error"] = clean(str(exc))
                    self.assertion("scenario execution", False, scenario=scenario["branch"], evidence=record["prUrl"], detail=record["error"])
                record["finishedAt"] = now()
                self.save()
        def outbox_drained():
            with self.db() as conn, conn.cursor() as cur:
                cur.execute("SELECT status,count(*) FROM ml.ingestion_outbox GROUP BY status")
                counts = dict(cur.fetchall())
                self.report["outboxStatusCounts"] = counts
                return counts and not any(status != "completed" for status in counts)
        try:
            poll(outbox_drained, self.args.review_timeout, "PostgreSQL outbox completion",
                 interval=self.args.poll_interval)
            drained = True
        except TimeoutError:
            drained = False
        self.assertion("PostgreSQL outbox processes events", drained,
                       detail=self.report.get("outboxStatusCounts"))

    def save(self):
        (self.out / "report.json").write_text(json.dumps(clean(self.report), indent=2, default=str), encoding="utf-8")
        lines = ["# Real GitHub E2E evidence", "", f"- Started: {self.report['startedAt']}",
                 f"- Finished: {self.report.get('finishedAt', 'running')}",
                 f"- Repository: {self.report['repoUrl']}", f"- API jar git SHA (caller supplied): `{self.args.api_sha}`",
                 f"- API jar SHA-256: `{self.report.get('apiJarSha256', 'pending')}`", "",
                 "The OAuth integration comments under the authenticated GitHub user; summary comments are identified by the exact hidden PR marker and author, not GitHub account type.",
                 "Redelivery non-duplication is observed for the bounded window in report.json. Decoy results are recorded, never used to abort later scenarios. Missing reviews block detector assertions rather than being counted as passes.", "",
                 "| Scenario | Assertion | Result | Evidence |", "|---|---|---|---|"]
        for item in self.report["assertions"]:
            evidence = item.get("evidence")
            if isinstance(evidence, list):
                evidence = " ".join(f"[GitHub]({url})" for url in evidence) or "No summary comment"
            elif isinstance(evidence, str):
                evidence = f"[GitHub]({evidence})"
            lines.append(f"| {item.get('scenario') or '-'} | {item['name']} | {item['status']} | {evidence or '-'} |")
        if self.report["errors"]:
            lines += ["", "## Blockers", ""] + ["- " + clean(error) for error in self.report["errors"]]
        if "apiLogErrorCounts" in self.report:
            lines += ["", "## API log error counts", "",
                      "```json", json.dumps(self.report["apiLogErrorCounts"], indent=2), "```"]
        if "outboxStatusCounts" in self.report:
            lines += ["", "PostgreSQL outbox status counts: `" + json.dumps(self.report["outboxStatusCounts"]) + "`."]
        lines += ["", "Detailed findings, comment IDs/bodies/URLs, delivery IDs, queue errors, API responses and timestamps: [report.json](report.json).",
                  "", "## Cleanup", "", json.dumps(clean(self.report.get("cleanup", {})), indent=2), ""]
        (self.out / "report.md").write_text(clean("\n".join(lines)), encoding="utf-8")

    def cleanup(self):
        stopped = []
        for name, proc in reversed(self.processes):
            if proc.poll() is None:
                if os.name == "nt":
                    subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"], capture_output=True, timeout=30)
                else:
                    os.killpg(proc.pid, signal.SIGTERM)
                try:
                    proc.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait(timeout=10)
            stopped.append({"name": name, "pid": proc.pid, "stopped": proc.poll() is not None})
        for stream in self.logs:
            stream.close()
        for path in self.out.glob("*.log"):
            path.write_text(clean(path.read_text(encoding="utf-8", errors="replace")), encoding="utf-8")
        api_log = self.out / "api.log"
        if api_log.exists():
            lines = api_log.read_text(encoding="utf-8", errors="replace").splitlines()
            errors = [line for line in lines if re.search(r"\bERROR\b", line)]
            self.report["apiLogErrorCounts"] = {
                "ERROR": len(errors),
                "OutboxProcessor": sum("OutboxProcessor" in line for line in errors),
                "OutboxClaimService": sum("OutboxClaimService" in line for line in errors),
                "SKIP_LOCKED": sum(bool(re.search(r"SKIP\s+LOCKED", line, re.I)) for line in errors),
                "SQL": sum(bool(re.search(r"SQL|SqlException|PSQLException|syntax error", line, re.I)) for line in errors)}
            self.report["apiLogErrorLines"] = errors
            self.assertion("API log contains no ERROR entries", not errors,
                           detail=self.report["apiLogErrorCounts"])
        cleanup = {"processes": stopped, "repoDeleted": False}
        if self.args.delete_repo:
            try:
                command(["gh", "repo", "delete", self.args.repo, "--yes"])
                cleanup["repoDeleted"] = True
            except Exception as exc:
                cleanup["deleteError"] = clean(str(exc))
        if not cleanup["repoDeleted"]:
            cleanup["manualDeleteCommand"] = f"gh repo delete {self.args.repo} --yes"
            print("Repository retained. Manual cleanup (requires delete_repo scope): " + cleanup["manualDeleteCommand"])
        self.report["cleanup"] = cleanup
        self.report["finishedAt"] = now()
        self.save()


def arguments():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--api-jar", type=Path, required=True)
    parser.add_argument("--api-sha", required=True, help="Git revision used to build the jar; recorded verbatim")
    parser.add_argument("--worker-dir", type=Path, required=True)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--reuse", action="store_true", help="Reuse existing seeded PRs; database is still reset")
    parser.add_argument("--private", action="store_true", help="Pass --private to the sandbox seed script")
    parser.add_argument("--delete-repo", action="store_true")
    parser.add_argument("--python", default=sys.executable)
    local = Path(os.environ.get("LOCALAPPDATA", str(Path.home() / ".local")))
    parser.add_argument("--java", default=str(local / "acrt-jdk/jdk-21.0.13+9/bin/java.exe") if os.name == "nt" else "java")
    parser.add_argument("--cloudflared", default=str(local / "acrt-e2e/cloudflared.exe") if os.name == "nt" else "cloudflared")
    parser.add_argument("--pg-host", default="127.0.0.1")
    parser.add_argument("--pg-port", type=int, default=55432)
    parser.add_argument("--pg-user", default="acrt")
    parser.add_argument("--pg-password", default=os.environ.get("E2E_PG_PASSWORD", "e2e-pg-pass"))
    parser.add_argument("--pg-database", default="acrt")
    parser.add_argument("--redis-host", default="127.0.0.1")
    parser.add_argument("--redis-port", type=int, default=56379)
    parser.add_argument("--redis-password", default=os.environ.get("E2E_REDIS_PASSWORD", "e2e-redis-pass"))
    parser.add_argument("--startup-timeout", type=int, default=180)
    parser.add_argument("--review-timeout", type=int, default=120)
    parser.add_argument("--poll-interval", type=float, default=5)
    parser.add_argument("--observe-seconds", type=int, default=10)
    args = parser.parse_args()
    if not re.fullmatch(r"[\w.-]+/[\w.-]+", args.repo):
        parser.error("--repo must be owner/name")
    if not args.api_jar.is_file() or not (args.worker_dir / "app/main.py").is_file():
        parser.error("--api-jar and --worker-dir must point to existing build/runtime inputs")
    if min(args.startup_timeout, args.review_timeout, args.poll_interval, args.observe_seconds) <= 0:
        parser.error("timeouts and poll interval must be positive")
    args.api_jar = args.api_jar.resolve()
    args.worker_dir = args.worker_dir.resolve()
    return args


def main():
    runner = Runner(arguments())
    exit_code = 2
    try:
        runner.start()
        runner.exercise()
        exit_code = 0 if runner.report["assertions"] and all(a["passed"] is True for a in runner.report["assertions"]) else 1
    except (Exception, KeyboardInterrupt) as exc:
        runner.report["errors"].append(clean(f"{type(exc).__name__}: {exc}"))
    finally:
        try:
            runner.cleanup()
        except Exception as exc:
            runner.report["errors"].append(clean(f"Cleanup: {exc}"))
            runner.report["finishedAt"] = now()
            runner.save()
            exit_code = 2
    if exit_code != 2:
        exit_code = 0 if runner.report["assertions"] and all(
            a["passed"] is True for a in runner.report["assertions"]) else 1
    counts = {state: sum(a["status"] == state for a in runner.report["assertions"]) for state in ("PASS", "FAIL", "BLOCKED")}
    print(json.dumps({"report": str(runner.out / "report.md"), "summary": counts, "errors": clean(runner.report["errors"])}, indent=2))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
