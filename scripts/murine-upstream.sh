#!/bin/bash
# Lists Murine Launcher's upstream commits since shell-default's base (shell-default/UPSTREAM.md) that UPSTREAM.md's
# table does not decide yet, oldest first. Usage: scripts/murine-upstream.sh [work-dir]
# The upstream clone is kept in work-dir (default: $TMPDIR or /tmp, murine-upstream) and fetched again on each run;
# nothing is ever deleted.
set -euo pipefail
repo_root=$(cd "$(dirname "$0")/.." && pwd)
notes="$repo_root/shell-default/UPSTREAM.md"
work="${1:-${TMPDIR:-/tmp}/murine-upstream}"
url="https://github.com/alesimula/Murine-launcher"

base=$(grep -o '\*\*Upstream `[0-9a-f]*`' "$notes" | grep -o '[0-9a-f]\{7,\}' | head -n 1)
if [ -z "$base" ]; then
  echo "No base commit found in $notes" >&2
  exit 1
fi

if [ -d "$work/.git" ]; then
  git -C "$work" fetch -q origin main
else
  git clone -q --filter=blob:none --no-checkout "$url" "$work"
fi

decided=$(grep -o '^| `[0-9a-f]\{7,\}`' "$notes" | grep -o '[0-9a-f]\{7,\}' || true)
git -C "$work" log --reverse --format='%h %ad %s' --date=short "$base..origin/main" | while read -r sha rest; do
  if ! printf '%s\n' "$decided" | grep -q "^${sha:0:7}"; then
    printf '%s %s\n' "$sha" "$rest"
  fi
done
