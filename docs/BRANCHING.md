# Branch and provenance policy

`main` is the only maintained integration branch for jolt-http. New work must
branch from `main`, and pull requests must target `main`.

The branch was established from reviewed carrier
`dcb3016fd2af289ce2cea23db6dc4b90d22c3f10`. That commit merged the empty
known-length chunked-body correction and carries the current HTTP aspect
manifest, Jolt 0.8 compiler pin, and jolt-tcp dependency line. The initial
canonical branch preserves that commit unchanged; it is not a rebased or
squashed copy. This floor is permanent: it identifies the beginning of the
maintained ancestry and must not be advanced to follow the latest `main` tip.

Run the local policy gate before publishing a branch:

```sh
script/check-canonical-branch.sh
script/test-canonical-branch.sh
```

The gate rejects a head that does not descend from the canonical floor. In a
GitHub pull-request job the workflow checks the actual pull-request head SHA,
not GitHub's synthetic merge commit, and additionally rejects any base other
than `main`. This makes ordinary clone, branch, and PR behavior deterministic
without requiring an agent to remember which feature-named integration branch
was most recent. The companion test covers unavailable-floor and non-commit
inputs, creates an unreferenced synthetic root commit, and simulates a pull
request against a legacy base without changing the checked-out branch.

## Legacy branches

Branches that predate `main`, including `codex/jolt-upstream-fork` and
`codex/cooperative-aspect-annotations-current`, are provenance records, not
integration targets. Do not add new commits to them. Do not delete or rewrite a
legacy branch until all unique commits, open pull requests, and exact downstream
pins have been classified.

The former GitHub default was `codex/jolt-upstream-fork` at
`3046a249e95876c53abb522b567e751d4f4a1634`. It is an ancestor of the canonical
floor. An earlier audit found `jolt-lang/ring-chez-adapter` pinned to that exact
commit, but current upstream `e49cc7699cdf50e182a63bf595a582bceda342b8`
no longer depends on jolt-http. It is not a migration obligation. Revalidate all
other downstream pins against live source before retiring any legacy ref.

## Compiler and dependency updates

Jolt and jolt-tcp remain exact-SHA dependencies. Update each pin in a focused
pull request based on `main`, record its upstream or fork provenance, and run
the complete repository suite at the proposed head. A green compiler build
does not by itself validate the HTTP dependency graph.

When a maintainer rewrites or rebases a submitted change, record four identities
where available: submitted head, reviewed/final head, merge commit, and the
current `main` commit carrying the patch-equivalent tree.
