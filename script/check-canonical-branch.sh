#!/bin/sh
set -eu

canonical_branch=main
canonical_floor=dcb3016fd2af289ce2cea23db6dc4b90d22c3f10
subject=${1:-HEAD}

if ! git cat-file -e "$canonical_floor^{commit}" 2>/dev/null; then
  echo "canonical floor is unavailable: $canonical_floor" >&2
  exit 1
fi

if ! git cat-file -e "$subject^{commit}" 2>/dev/null; then
  echo "subject is not a commit: $subject" >&2
  exit 1
fi

if ! git merge-base --is-ancestor "$canonical_floor" "$subject"; then
  echo "subject does not descend from the canonical jolt-http line" >&2
  echo "canonical floor: $canonical_floor" >&2
  echo "subject: $subject" >&2
  exit 1
fi

if [ "${GITHUB_EVENT_NAME:-}" = pull_request ] &&
   [ "${GITHUB_BASE_REF:-}" != "$canonical_branch" ]; then
  echo "pull requests must target $canonical_branch, not ${GITHUB_BASE_REF:-<unset>}" >&2
  exit 1
fi

echo "PASS canonical branch: $subject descends from $canonical_floor"
