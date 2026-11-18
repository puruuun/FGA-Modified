#!/bin/sh
# The `changes` job's guard step, kept here so a test can run it.
# Environment: RANGE (diff range of the classify step, may be empty), EVENT_NAME, PR_HEAD, HEAD_SHA,
# RUNNER_TEMP, optional PYTHON (python3 by default). Runs in the repository root and exits non-zero
# when the guard fails. Without set -e a failed git command would leave an empty file and pass.
set -eu
guard="$(dirname "$0")/repo_guard.py"
py="${PYTHON:-python3}"
# Sizes come from the committed blobs of the head commit, not from the working tree.
# On a pull_request the checkout is a merge commit; the change itself is PR_HEAD.
head="$HEAD_SHA"
if [ "$EVENT_NAME" = pull_request ] && [ -n "${PR_HEAD:-}" ]; then head="$PR_HEAD"; fi
listing="$RUNNER_TEMP/tree-listing"
only="$RUNNER_TEMP/guard-paths"
allow="$RUNNER_TEMP/allow-list"
# The allow-list of the head commit, so that a PR is judged by the list it brings.
git show "$head:tools/ci/large-files-allow.txt" > "$allow"
git ls-tree -r -l -z "$head" > "$listing"
status=0
# Two-dot form of the range: the classify step gives A...B for a pull request and A..B for a push,
# and in both the commits to check are those reachable from B but not from A. Commits that a merge
# of the base brought in are reachable from A and so are not checked again.
commit_range=$(printf '%s' "${RANGE:-}" | sed 's/\.\.\.*/../')
# No usable range (manual run, first push of a branch) or a failed git command: check every tracked
# file of the head commit instead of passing without a look.
if [ -n "${RANGE:-}" ] \
  && git diff --name-only --no-renames --diff-filter=d -z "$RANGE" > "$only" \
  && commits=$(git rev-list --no-merges "$commit_range"); then
  "$py" "$guard" --allow "$allow" --only "$only" < "$listing" || status=1
  # A file added in one commit and deleted in a later one is gone from the net diff, but the
  # blob stays in the history that a fast-forward merge keeps.
  for c in $commits; do
    git diff-tree --no-commit-id -r --no-renames --diff-filter=d --name-only -z "$c" > "$only"
    git ls-tree -r -l -z "$c" > "$RUNNER_TEMP/commit-listing"
    "$py" "$guard" --allow "$allow" --only "$only" --label "$(git rev-parse --short "$c")" \
      < "$RUNNER_TEMP/commit-listing" || status=1
  done
else
  echo "No usable diff range; checking every tracked file of $head."
  "$py" "$guard" --allow "$allow" < "$listing" || status=1
fi
exit "$status"
