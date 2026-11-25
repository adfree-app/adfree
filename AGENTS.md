<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **adfree** (18546 symbols, 47403 relationships, 619 execution flows).

> Index stale? Run `node .gitnexus/run.cjs analyze --index-only` from the project root — it auto-selects an available runner. No `.gitnexus/run.cjs` yet? Bootstrap with `npx`, `bunx`, or `pnpm dlx` — e.g. `bunx gitnexus@latest analyze` (npm 11 npx crash; #1939).

## Always Do

- **MUST run impact before editing.** Use `impact({target: "symbolName", direction: "upstream"})` or `node .gitnexus/run.cjs impact "symbolName" --direction upstream --repo .`; report callers, processes, and risk. Never substitute grep for graph analysis.
- **MUST analyze graph changes before committing.** Use `detect_changes({scope: "all"})` (MCP) or `node .gitnexus/run.cjs detect-changes --scope all --repo .` (CLI fallback). `partial: true` or `truncated: true` is not a clean check — a zero means unseen, not unaffected; re-run it. For regression review: `detect_changes({scope: "compare", base_ref: "main"})` or `node .gitnexus/run.cjs detect-changes --scope compare --base-ref "main" --repo .`.
- MUST warn on HIGH/CRITICAL `risk` pre-edit; never use `riskSharedAxes` to waive a HIGH/CRITICAL `risk` warning. Compare File/symbol: MCP File omits axes; Graph-RAG expands File.
- **MUST treat `risk: UNKNOWN` as unresolved, not as low.** An empty caller set is not evidence the symbol is unused — it can also mean the callers are not resolvable by the index (plain-object property access, dynamic dispatch, cross-language calls). `impact` pairs `UNKNOWN` with a `riskNote` saying so. Confirm with a text search before treating the symbol as safe to change or delete; do not proceed on the strength of a zero.
- **MUST use `query({search_query: "concept"})` for concepts/flows, `context({name: "symbolName"})` for a named symbol, or `impact` for blast radius, on read-only callers, dependencies, imports, or execution flow.** Graph first; text search only for empty/`UNKNOWN`/literals.
- For security review, `explain({target: "fileOrSymbol"})` lists taint findings (source→sink flows; needs `analyze --pdg`).

## Never Do

- NEVER edit a function, class, or method before MCP/CLI impact analysis.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis, and never read `UNKNOWN` as an all-clear — it means the walk could not answer, which is the one verdict that requires confirming by other means.
- NEVER rename symbols with find-and-replace — use `rename` which understands the call graph.
- NEVER commit before MCP/CLI graph change analysis.

## Resources

| Resource | Use for |
| --- | --- |
| `gitnexus://repo/adfree/context` | Codebase overview, check index freshness |
| `gitnexus://repo/adfree/clusters` | All functional areas |
| `gitnexus://repo/adfree/processes` | All execution flows |
| `gitnexus://repo/adfree/process/{name}` | Step-by-step execution trace |

## CLI

| Task | Read this skill file |
| --- | --- |
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->

## The commit hook

`core.hooksPath` points at `.githooks/`, and `pre-commit` runs the two checks CI
holds the line on — `cargo clippy --all-targets -- -D warnings` and
`cargo test --lib` — whenever a Rust file is staged. A clone that has not run

```sh
git config core.hooksPath .githooks
```

gets nothing, which is the state that let six clippy warnings reach the default
branch as six red builds. `git commit --no-verify`, or `ADFREE_SKIP_HOOKS=1`,
skips it when that is the deliberate choice.

## The verification tools

The core is 88 000 lines of Rust with 334 `unsafe` blocks, all of them at the
operating-system seam: sockets, `epoll`, WinDivert, the packet ring. Two of the
six checks below look at that seam, and each is aimed at what the others cannot
reach. Nothing here is a release gate; a red run is a finding, and the finding
is the point.

| Where | What | Command |
| --- | --- | --- |
| `ci.yml` — every push | advisories, licences, bans, sources | `cd rust && cargo deny check` |
| `verify.yml` — Mondays | the suite under AddressSanitizer | `RUSTFLAGS="-Zsanitizer=address -Cdebuginfo=1" ADFREE_SKIP_ALLOCATOR_TEST=1 cargo +nightly test --lib --target x86_64-unknown-linux-gnu` |
| `verify.yml` — Mondays | the pure parsers under Miri | `cargo +nightly miri test --lib -- 'dns::tests' --skip 'proxy::'` |
| `verify.yml` — Mondays | five parsers, a minute each | `cd rust/fuzz && RUSTUP_TOOLCHAIN=nightly cargo fuzz run dns_query -- -max_total_time=60` |
| `verify.yml` — Mondays | does the suite notice a wrong line | `cd rust && cargo mutants --file src/dns.rs --timeout 60` |
| `verify.yml` — Mondays | a bounded Kani proof of the DNS decoder | `cd rust && cargo kani --lib` |

What each one can and cannot see, so nobody asks the wrong one:

- **Miri has no sockets, no threads and no FFI**, so it is pointed at the
  modules that open none: `dns`, `pathmask`, `htmlfilter`, `safesearch`. It is
  the only check that reads every pointer access in the parsers, and it is
  roughly a thousand times slower than the machine.
- **A sanitizer replaces the allocator**, so `mem::tests`' arena reading is
  skipped through `ADFREE_SKIP_ALLOCATOR_TEST` rather than reported as a
  failure, and LeakSanitizer's exit report is switched off
  (`ASAN_OPTIONS=detect_leaks=0`) because the process-lifetime singletons the
  tests create are not leaks.
- **AddressSanitizer is the only sanitizer here.** Rust has no
  `-Zsanitizer=undefined`, and ThreadSanitizer refuses a precompiled standard
  library, which would mean `-Zbuild-std` over bundled SQLite and `ring`'s
  assembly.
- **Kani needs the proof to fit in a bound.** `src/proofs.rs` states its own
  unwind limits, and a harness that stops converging is a signal to lower the
  bound, not to wait.
- **A new parser gets a fuzz target.** `rust/fuzz/Cargo.toml` lists them; the
  corpus under `rust/fuzz/corpus/` is committed, so each run starts from the
  inputs the last one found. The `dns_query` target found a stack overflow and
  a `&str` built from non-ASCII bytes within ten thousand runs of each other.

## The website

The landing page, the extension and this app are three repositories now
(`adfree-website`, `adfree-extension`, `adfree`), split out of this tree. This
repository no longer carries either — `website/` and `extension/` are gone from
here. A feature that only lands in `shared/`, `desktop/`, `app/` or `rust/`
leaves the public page describing a build that no longer exists, and there is
no copy here to edit: the page, its four languages and its screenshots live in
their own repos.

Two things still cross the boundary:

- **The changelog.** The website's `/changelog` page reads this repository's
  `CHANGELOG.md` from `raw.githubusercontent.com` at request time — there is no
  copied file and no build step to keep in sync.
- **The extension.** The app's **Extensions → Open the install guide** button
  opens `https://adfree-app.github.io/install-extension` — the organisation's
  root site. The address is a constant, `BROWSER_EXTENSION_GUIDE_URL` in
  `shared`, and it is the only URL the app knows about the website. Moving the
  site means changing that one line and the repo that serves it
  (`adfree-app/adfree-app.github.io`).
