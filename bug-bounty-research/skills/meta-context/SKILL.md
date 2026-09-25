---
name: meta-context
description: Resolve identifiers seen in Meta traffic — object ids/FBIDs, URLs and endpoints, persisted GraphQL doc_ids, operation names, com.bloks.* ids — to the code assets behind them (Ent/Node types, XControllers, GraphQL resolvers, Graph edges, Distillery views, Bloks definitions). Use when triaging or writing a bug bounty report, reading a captured HTTP request/response or HAR, or answering "what code serves this endpoint / what is this id". Requires the meta-context MCP server and a bug bounty researcher token.
---

# Meta Context

An identifier in proxied traffic tells you nothing on its own. `1000641…` could be a user, a
group, a comment or a clock reading. This turns those identifiers into the code that serves
them, which is what makes a finding writable-up and reviewable.

Same data as the **Meta Context** panel in the Zurp Burp extension, over the same
`/bug_bounty/assets` API, so the two agree.

## Which tool

| You have | Use |
|---|---|
| A report, a captured request/response, a HAR excerpt, a log — anything unstructured | `meta_context_scan` |
| A handful of identifiers you already isolated | `meta_context_resolve_batch` |
| Exactly one identifier, and you want its vanity name too | `meta_context_resolve` |

**Reach for `meta_context_scan` first.** It scrapes the identifiers itself, in the exact
shapes the endpoint accepts, and resolves them in a single request. Picking ids out by hand
and looping `meta_context_resolve` costs one request each and misses shapes.

Do not loop `meta_context_resolve`. Batch instead — 200 identifiers per request.

## Identifier shapes

The endpoint infers the kind from the value, so the spelling matters:

- **Object id / Instagram media id** — 15–18 digits, bare. Shorter values are usually
  timestamps, not ids.
- **URL or path** — on `facebook.com`, `meta.com`, `instagram.com` or `threads.com`. A
  `graph.` host resolves to a Graph edge; Instagram and Threads paths resolve to Distillery
  views; everything else to an XController.
- **Persisted GraphQL document** — write it `doc_id=1234…`, **with the prefix**. A bare
  number is read as an object id and resolves to the wrong thing or to nothing.
- **GraphQL operation name** — only recognised when it ends in `Query` or `Mutation`.
- **Bloks** — a `com.bloks.*` id.

## Reading the answer

- **Empty is a normal answer, not an error.** Most ids scraped from traffic name nothing —
  they are timestamps, counters, or objects the endpoint cannot map. Do not retry an empty
  result, and do not report it as a failure.
- **One identifier can name several assets.** A URL carrying an object id in its query
  string is both an XController and an Ent. Both come back.
- **`type` is open.** Known values are `ent_or_node`, `xcontroller`, `graphql`,
  `graph_edge`, `distillery`, `bloks`. Treat anything else as a new kind worth reporting,
  not as an error.
- **A vanity name is a convenience, never the authority.** Only the single lookup returns
  one, only when the object has one and you could already see it.

## What this is not

It maps identifiers to **symbol names**. It is not code search, and it does not return file
paths, line numbers or source. Take the name it gives you and look the symbol up separately
if you need the implementation.

It also does not tell you who owns an asset, whether a finding is valid, or whether an
endpoint is vulnerable.

## Errors worth handling differently

| Error | What it means | Do |
|---|---|---|
| `no-token` | Neither `BB_RESEARCH_TOKEN` nor `ZURP_ACCESS_TOKEN` is set | Tell the user to set one and restart the MCP server. Do not retry. |
| `forbidden` | The account is not on the bug bounty researcher allowlist | Stop. Every later lookup is latched off for the session; retrying cannot help. |
| `unauthorized` | Token expired, or not a real-person token | Researcher tokens last 60 days. Tell the user to re-mint. Do not retry. |
| `rate-limited` | The server refused: hourly budget spent | Resolve fewer identifiers, or wait for the hour to roll. |
| `local-budget` | The client refused before sending, to protect the same budget | Same as above. The message says when the next lookup can run. |
| `network`, `server-error` | Transient | Already retried with backoff before you saw it. Report it; do not loop. |

A batch that fails partway returns what it already resolved plus the error. Use those
results; do not discard them and start over.

## Budget

Lookups are metered per researcher, per hour, and shared with anything else using the same
token — including Zurp running in Burp. The two endpoints have **separate budgets, and the
batch one is much tighter**:

- single lookups (`meta_context_resolve`) — 1000/hour
- batches (`meta_context_resolve_batch`, and every `meta_context_scan`) — **100/hour**

That is still 20,000 identifiers an hour through batching, because one batch carries up to
200. But it means roughly a hundred scans an hour, not a thousand — so one scan of a whole
report beats five scans of its sections, and looping single lookups to "save" the batch
budget just spends the other one instead.

`meta_context_scan` resolves at most 200 candidates by default. Raise its `limit` only for
a large report you trust: a report is attacker-supplied text, and padding it with thousands
of plausible ids is an easy way to burn someone's budget.

Exhausting one endpoint does not touch the other, and a `rate-limited` error says how long
the wait is. There is no point retrying inside that window.
