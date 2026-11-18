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
  opens `/install-extension` on the website. The address is a constant,
  `BROWSER_EXTENSION_GUIDE_URL` in `shared`, and it is the only URL the app
  knows about the website.
