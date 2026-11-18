#!/bin/sh
# The `changes` job's guard step, kept here so a test can run it.
# Environment: RANGE (diff range of the classify step, may be empty), EVENT_NAME, PR_HEAD, HEAD_SHA,
# RUNNER_TEMP, optional PYTHON (python3 by default). Runs in the repository root and exits non-zero when the guard fails.
set -eu
guard="$(dirname "$0")/repo_guard.py"
# Sizes come from the committed blobs of the head commit, not from the working tree.
# On a pull_request the checkout is a merge commit; the change itself is PR_HEAD.
head="$HEAD_SHA"
if [ "$EVENT_NAME" = pull_request ] && [ -n "${PR_HEAD:-}" ]; then head="$PR_HEAD"; fi
listing="$RUNNER_TEMP/tree-listing"
only="$RUNNER_TEMP/guard-paths"
git ls-tree -r -l -z "$head" > "$listing"
# No usable range (manual run, first push of a branch) or a failed diff: check every tracked file
# of the head commit instead of passing without a look.
if [ -n "${RANGE:-}" ] && git diff --name-only --no-renames --diff-filter=d -z "$RANGE" > "$only"; then
  "${PYTHON:-python3}" "$guard" --only "$only" < "$listing"
else
  echo "No usable diff range; checking every tracked file of $head."
  "${PYTHON:-python3}" "$guard" < "$listing"
fi
