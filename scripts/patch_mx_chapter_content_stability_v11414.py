#!/usr/bin/env python3
from __future__ import annotations

import os
import runpy
import sys
from pathlib import Path

helper_script = Path(__file__).with_name("patch_mx_chapter_content_reliability_v11414.py")
if not helper_script.exists():
    raise SystemExit(f"missing reliability helper: {helper_script}")

# Keep sys.argv unchanged so the delegated helper receives the mx-app checkout path.
runpy.run_path(str(helper_script), run_name="__main__")

# The original v94 workflow predates the ReaderViewModel/shared matcher additions and therefore
# has a deliberately narrow `git add` list. A local pre-commit hook stages only the extra files
# introduced by the reliability patch after Spotless has formatted them. The hook lives inside
# the ephemeral checkout and is never committed to mx-app.
root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
hook = root / ".git" / "hooks" / "pre-commit"
hook.write_text(
    """#!/usr/bin/env bash
set -euo pipefail
git add \\
  app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt \\
  app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReplacementMangaMatcher.kt \\
  app/src/test/java/eu/kanade/tachiyomi/ui/reader/ReplacementMangaMatcherTest.kt
""",
    encoding="utf-8",
)
os.chmod(hook, 0o755)

print("v94 compatibility runner delegated to chapter replacement reliability patch")
