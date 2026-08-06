# Draft patches against upstream Capra

Held here **only** because this session could not push to the `casselc/capra`
fork (the git proxy declined to inject a credential for a repository outside
the session's authorized set). They belong on the fork, not in this repo —
drop this directory once they are pushed there.

Base: `weavejester/capra` @ `f7b01e5f3179e50739ea108a6c1178ceec07eaa0`, which
is also where `casselc/capra`'s `master` sits, so they apply cleanly.
Branch: `claude/fix-http-conformance`, 11 commits, one per finding.

| Patch | Finding |
| --- | --- |
| 0001 | 1 — Content-Length with Transfer-Encoding |
| 0002 | 2 — Content-Length grammar |
| 0003 | 15 — repeated Content-Length |
| 0004 | 3 — chunked trailer section |
| 0005 | 14 — chunk-size grammar |
| 0006 | 13 — duplicate Host |
| 0007 | 12 — header field-name tokens |
| 0008 | none — see below |
| 0009 | 4 — response validation (response splitting) |
| 0010 | 10 — sink finalization |
| 0011 | 7 — HEAD / 1xx / 204 / 304 framing |

Patch 0008 does not correspond to a finding. `write-error-response` returns
`nil`, so the parse loop keeps `::step :error` and re-enters it, re-queueing
`tcp/close` until the control queue fills. It is reproducible on the base
commit, but the other patches multiply the mid-message error paths, so leaving
it makes every new rejection noisy.

```sh
git checkout -b claude/fix-http-conformance f7b01e5
git am docs/upstream-patches/*.patch
```

**Not reviewed by a second model, and no pull request has been opened.** See
[`../UPSTREAM-CAPRA-FINDINGS.md`](../UPSTREAM-CAPRA-FINDINGS.md). Known open
questions are recorded there and in the session notes: the hoisting of
`handled` into the body state (patch 0004) is the only change to a control-flow
invariant rather than a parse, and `async?` is left as a dead parameter on the
`ResponseBody` protocol rather than removed.
