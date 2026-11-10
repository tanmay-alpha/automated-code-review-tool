"""Create a disposable GitHub repository with seeded pull requests.

The repository is a sandbox for demonstrating and end-to-end testing the
reviewer: it contains a small base project and opens one pull request per
scenario under ``scenarios/``. Each scenario lists the findings a reviewer is
expected to produce (``expected``) and the decoys it must not flag.

Requirements: ``git`` and an authenticated ``gh`` CLI. Nothing here needs the
reviewer's credentials; connect the repository from the dashboard afterwards
(or with ``POST /api/repos/connect``) so the webhook is installed, then push or
reopen a PR to trigger a review.

Usage::

    python examples/demo-repo/seed_demo_repo.py --repo <owner>/<name> [--private]
    python examples/demo-repo/seed_demo_repo.py --repo <owner>/<name> --delete

``--delete`` removes the repository (needs the ``delete_repo`` scope).
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
BASE_DIR = HERE / "base"
SCENARIO_DIR = HERE / "scenarios"


def run(cmd: list[str], cwd: Path | None = None, capture: bool = False) -> str:
    result = subprocess.run(
        cmd, cwd=cwd, check=True, text=True,
        stdout=subprocess.PIPE if capture else None,
    )
    return result.stdout.strip() if capture else ""


def repo_exists(repo: str) -> bool:
    probe = subprocess.run(
        ["gh", "repo", "view", repo, "--json", "name"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False,
    )
    return probe.returncode == 0


def load_scenarios() -> list[dict]:
    scenarios = []
    for path in sorted(SCENARIO_DIR.glob("*/scenario.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        data["dir"] = path.parent
        scenarios.append(data)
    return scenarios


def seed(repo: str, private: bool) -> None:
    if repo_exists(repo):
        sys.exit(f"{repo} already exists; delete it first or choose another name")
    visibility = "--private" if private else "--public"
    run(["gh", "repo", "create", repo, visibility,
         "--description", "Disposable sandbox for automated-code-review-tool"])

    owner_login = run(["gh", "api", "user", "--jq", ".login"], capture=True)
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp) / "sandbox"
        shutil.copytree(BASE_DIR, work)
        run(["git", "init", "-q", "-b", "main"], cwd=work)
        run(["git", "add", "-A"], cwd=work)
        run(["git", "commit", "-q", "-m", "Base project"], cwd=work)
        run(["git", "remote", "add", "origin", f"https://github.com/{repo}.git"], cwd=work)
        run(["git", "push", "-q", "-u", "origin", "main"], cwd=work)

        for scenario in load_scenarios():
            branch = scenario["branch"]
            run(["git", "checkout", "-q", "-b", branch, "main"], cwd=work)
            for src in (scenario["dir"] / "files").rglob("*"):
                if src.is_file():
                    dest = work / src.relative_to(scenario["dir"] / "files")
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(src, dest)
            run(["git", "add", "-A"], cwd=work)
            run(["git", "commit", "-q", "-m", scenario["title"]], cwd=work)
            run(["git", "push", "-q", "-u", "origin", branch], cwd=work)
            body = scenario["description"] + "\n\nExpected findings:\n" + "\n".join(
                f"- `{e['antiPattern']}` in `{e['file']}`" for e in scenario["expected"]
            ) + ("\n\nDecoys that must not be flagged:\n" + "\n".join(
                f"- {d}" for d in scenario.get("decoys", [])
            ) if scenario.get("decoys") else "")
            run(["gh", "pr", "create", "--repo", repo, "--base", "main", "--head", branch,
                 "--title", scenario["title"], "--body", body])
            run(["git", "checkout", "-q", "main"], cwd=work)

    print(f"Seeded https://github.com/{repo} as {owner_login}. Connect it in the dashboard, "
          "then push to a PR branch or close/reopen a PR to trigger a review.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--repo", required=True, help="owner/name of the sandbox repository")
    parser.add_argument("--private", action="store_true", help="create a private repository")
    parser.add_argument("--delete", action="store_true", help="delete the sandbox repository")
    args = parser.parse_args()
    if args.delete:
        run(["gh", "repo", "delete", args.repo, "--yes"])
        return
    seed(args.repo, args.private)


if __name__ == "__main__":
    main()
