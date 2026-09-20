#!/bin/bash
#
# Keeps this fork in sync with upstream Dolphin (dolphin-emu/dolphin).
#
# 'master' is a pristine mirror of upstream/master: it is only ever
# fast-forwarded, never committed to, so branches cut from it for upstream pull
# requests carry no fork-specific changes. Fork work lives on 'development'.
#
# Usage:
#   Tools/sync-upstream.sh              # fast-forward master, merge it into the current branch
#   Tools/sync-upstream.sh --dry-run    # only show what would come in
#   Tools/sync-upstream.sh --ref upstream/stable-2412
#
# Run it from master to update the mirror alone. Nothing is pushed; review the
# result and push yourself.

set -euo pipefail

UPSTREAM_URL="https://github.com/dolphin-emu/dolphin.git"
MIRROR_BRANCH="master"
REF="upstream/master"
DRY_RUN=0

while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    --ref) REF="$2"; shift 2 ;;
    -h|--help) sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

cd "$(git rev-parse --show-toplevel)"

# The remote is read-only by design: pushing to dolphin-emu/dolphin is never wanted.
if ! git remote get-url upstream > /dev/null 2>&1; then
  echo "Adding 'upstream' remote -> $UPSTREAM_URL"
  git remote add upstream "$UPSTREAM_URL"
  git remote set-url --push upstream DISABLED_read_only
fi

BRANCH=$(git rev-parse --abbrev-ref HEAD)
if [ "$BRANCH" = "HEAD" ]; then
  echo "error: detached HEAD; check out a branch first." >&2
  exit 1
fi

# A merge on top of local edits makes it very hard to tell which change broke
# what. A dry run changes nothing, so it does not need a clean tree.
if [ "$DRY_RUN" -eq 0 ] && [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "error: you have uncommitted changes. Commit or 'git stash' them first." >&2
  git status --short --untracked-files=no >&2
  exit 1
fi

# Tags come along because Source/Android/app/build.gradle.kts derives versionName
# from 'git describe'; without them every build is versioned off the wrong base.
echo "Fetching $UPSTREAM_URL ..."
git fetch --tags upstream

INCOMING=$(git rev-list --count "HEAD..$REF")
MIRROR_BEHIND=$(git rev-list --count "$MIRROR_BRANCH..$REF")

if [ "$INCOMING" -eq 0 ] && [ "$MIRROR_BEHIND" -eq 0 ]; then
  echo "Already up to date with $REF."
  exit 0
fi

if [ "$INCOMING" -gt 0 ]; then
  echo
  echo "$INCOMING new upstream commit(s) on $REF:"
  git log --oneline --no-decorate --max-count=15 "HEAD..$REF"
  [ "$INCOMING" -gt 15 ] && echo "  ... and $((INCOMING - 15)) more"
fi

# Three dots: compare against the merge base, so local commits are not mistaken
# for incoming upstream changes.
if git diff --quiet "HEAD...$REF" -- Source/Core Externals CMakeLists.txt CMake Data/Sys; then
  NATIVE_CHANGED=0
else
  NATIVE_CHANGED=1
fi

if [ "$DRY_RUN" -eq 1 ]; then
  echo
  echo "Incoming changes under Source/Android:"
  git diff --stat "HEAD...$REF" -- Source/Android || true
  echo "(dry run; nothing merged)"
  exit 0
fi

# Step 1: keep the mirror honest. Anything but a fast-forward means someone
# committed to master, which breaks the premise that PR branches cut from it are
# free of fork-specific changes.
if [ "$MIRROR_BEHIND" -gt 0 ]; then
  if ! git merge-base --is-ancestor "$MIRROR_BRANCH" "$REF"; then
    echo "error: $MIRROR_BRANCH has commits that are not in $REF, so it cannot be" >&2
    echo "       fast-forwarded. Move them to a feature branch, then re-run:" >&2
    git log --oneline --no-decorate "$REF..$MIRROR_BRANCH" >&2
    exit 1
  fi
  if [ "$BRANCH" = "$MIRROR_BRANCH" ]; then
    git merge --ff-only "$REF"
  else
    # Moves the branch pointer without touching the working tree.
    git branch -f "$MIRROR_BRANCH" "$REF"
  fi
  echo "Fast-forwarded $MIRROR_BRANCH to $REF."
fi

# Step 2: merge the refreshed mirror into the branch you are actually on.
if [ "$BRANCH" != "$MIRROR_BRANCH" ]; then
  echo
  echo "Merging $MIRROR_BRANCH into $BRANCH ..."
  if ! git merge --no-edit "$MIRROR_BRANCH"; then
    cat >&2 <<'EOF'

Merge conflicts. Resolve them, then:
    git add <resolved files>
    git commit
    Tools/sync-upstream.sh       # re-run to finish the submodule update

To back out instead: git merge --abort
EOF
    exit 1
  fi
fi

# Upstream regularly bumps Externals/*; a stale submodule checkout shows up as a
# confusing C++ compile error rather than as an obvious "you forgot to update".
echo
echo "Updating submodules ..."
git submodule sync --recursive
git submodule update --init --recursive

echo
echo "Done. $BRANCH is up to date with $REF."
if [ "$NATIVE_CHANGED" -eq 1 ]; then
  echo "Native sources changed - the next Android build will rebuild libmain.so (minutes)."
fi
echo "Nothing was pushed. Review, then:"
echo "    git push origin $MIRROR_BRANCH"
[ "$BRANCH" != "$MIRROR_BRANCH" ] && echo "    git push origin $BRANCH"
