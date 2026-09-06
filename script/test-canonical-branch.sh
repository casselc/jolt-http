#!/bin/sh
set -eu

checker=script/check-canonical-branch.sh

fail() {
  echo "FAIL canonical branch policy: $1" >&2
  exit 1
}

if ! "$checker" HEAD >/dev/null; then
  fail "the canonical HEAD was rejected"
fi

empty_objects=$(mktemp -d)
if GIT_OBJECT_DIRECTORY="$empty_objects" "$checker" HEAD >/dev/null 2>&1; then
  rmdir "$empty_objects"
  fail "an unavailable canonical floor was accepted"
fi
rmdir "$empty_objects"

if "$checker" not-a-commit >/dev/null 2>&1; then
  fail "a non-commit subject was accepted"
fi

if GITHUB_EVENT_NAME=pull_request GITHUB_BASE_REF=legacy "$checker" HEAD \
     >/dev/null 2>&1; then
  fail "a pull request against a legacy base was accepted"
fi

tree=$(git rev-parse 'HEAD^{tree}')
unrelated=$(
  printf '%s\n' 'canonical branch negative control' |
    GIT_AUTHOR_NAME=branch-policy \
    GIT_AUTHOR_EMAIL=branch-policy@example.invalid \
    GIT_COMMITTER_NAME=branch-policy \
    GIT_COMMITTER_EMAIL=branch-policy@example.invalid \
    git commit-tree "$tree"
)

if "$checker" "$unrelated" >/dev/null 2>&1; then
  fail "a commit outside the canonical ancestry was accepted"
fi

echo "PASS canonical branch policy controls"
