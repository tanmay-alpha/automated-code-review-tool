"""Repo-root conftest: make sure ``import taxonomy`` and ``import app``
work regardless of where pytest is invoked from."""

import sys
from pathlib import Path

_HERE = Path(__file__).resolve().parent
for _p in (str(_HERE), str(_HERE / "apps" / "ml-worker")):
    if _p not in sys.path:
        sys.path.insert(0, _p)
