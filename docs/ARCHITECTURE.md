# How Zurp works

Three independent data planes share one `HttpHandler`. They were built years apart and differ
noticeably in maturity.

```
┌──────────────── Burp Suite JVM ────────────────┐
│                                                 │
│   Proxy · Repeater · Intruder · Scanner         │
│        │                    ▲                   │
│        │ requests           │ responses         │
│        ▼                    │                   │
│  ┌──────────────────────────────────────┐       │
│  │            ZURP (extension)          │       │
│  │                                      │       │
│  │  HttpHandler ── Editor tabs ── Suite │       │
│  │       │            │           tab   │       │
│  │  ┌────┼────┐  ┌────┴─────┐           │       │
│  │  │ CSRF    │  │ Enrich   │  FBDL     │       │
│  │  │ plane   │  │ plane    │  plane    │       │
│  │  │(in-mem) │  │(persist) │ (persist) │       │
│  │  └─────────┘  └────┬─────┘───┬───────┘       │
│  └────────────────────┼─────────┼───────────────┘
└───────────────────────┼─────────┼───────────────┘
      │                 │         │
      ▼                 ▼         ▼
  facebook.com    api.facebook.com/<bug_bounty path>
   meta.com          Authorization: Bearer …
  (researcher's        (Zurp's own
   live session)        side-channel)
```

## Package map

```
burp.zurp                        ── lifecycle & policy
  Zurp .................. BurpExtension entry point; holds the public statics
                          (preferences, extensionData, logger, http, requester,
                           organizer, 4 fetchers + the list of them,
                           csrfTokenStore)
                          = the service locator
  MetaHttpHandler ....... the single HttpHandler; every plane hangs off it
  ZurpTabComponent ...... the suite tab: Settings / SPARTA / FBDL
  ZurpUtils ............. isMetaUrl() + preference reads w/ defaults
  ZurpPrefEnum .......... every preference key originates here (by convention)
  ZurpLog ............... the only route to Burp's log; NONE by default,
                          DEBUG keeps stack traces and raises Burp events
  ZurpEnv ............... seeds any preference from -Dzurp.* or ZURP_*,
                          snapshotted once; the tab still wins
  SpartaTargetExtractor . request → the target a finding is keyed on
  ZurpUnloadingHandler .. teardown

burp.csrf                        ── plane A
  CsrfScraper ........... pulls fb_dtsg / fb_dtsg_ag / lsd + SprinkleConfig
                          out of ServerJS payloads, keyed by name not position
  CsrfTokenStore ........ per-host and per-account, in-memory only
  CsrfRewriter .......... {{placeholder}} substitution + auto-refresh
  SprinkleChecksum ...... mirrors the server's sprinkle hash

burp.fetcher                     ── plane B
  ZurpDataFetcher ....... abstract: queue + 5-thread pool + 10s scheduler,
                          each one individually switchable off in the tab
  ├ MetaObjectInfoFetcher .... FBID     → ent_or_node asset + vanity
  ├ MetaUrlInfoFetcher ....... URL      → xcontroller asset
  ├ SpartaFindingFetcher ..... target   → findings disclosed to me
  └ FbdlRunFetcher ........... periodic sweep of my own FBDL runs
  AssetResolver ......... /bug_bounty/assets for the first two; one instance
                          for the extension, so one budget and one 403 latch
  GraphApiRequester ..... API calls via Burp's own HTTP stack (api.http())
  HourlyCallBudget ...... per-endpoint self-throttle
  SpartaPocRequest ...... finding → the GraphQL call its PoC describes
  SpartaFindingOrganizer  hands each one to Burp's Organizer, once ever
  SpartaFindingStore / FbdlRunStore .... the persisted shape of each

burp.fbdl                        ── plane C
  FbdlContextMenuProvider  "Pin FBDL run…" in Repeater and Intruder
  FbdlRewriter ......... {{fbdl.<run id>.<label>}} → a value that run created

burp.models / burp.ui / burp.editor   ── Swing presentation
  MetaHttpEditor (abstract) → MetaHttp{Request,Response}Editor
    renders the "Meta View" tab, itself three tabs:
    [Meta Objects | Meta Context | SPARTA Findings]
```

## Response path

```
 HttpResponseReceived
        │
        ▼
  ZurpUtils.isMetaUrl(url)?          exclude-list first (bootloader-endpoint,
        │  no → pass through          bulk-route-definitions, /ajax/qm/, …),
        │ yes                         then (*.)facebook.com|meta.com
        ├──────────────────────────────────────────────┐
        ▼                                              ▼
  ── PLANE A: CSRF ──                          ── PLANE B: ENRICH ──
  CsrfScraper.scrape(store, host, body)        metaUrlInfoFetcher.addToQueue(url)
    finds  "DTSGInitData",[],{…}                FBID_PATTERN = \b\d{14,18}\b
           "DTSGInitialData",[],{…}             over BOTH request + response text
           "LSD",[],{…}                           │
           name="fb_dtsg" <input>                 └→ metaObjectInfoFetcher
           "SprinkleConfig",[],{…}                     .addToQueue(fbid)
        │                                              │
        ▼                                              ▼
  CsrfTokenStore (RAM only)                    PersistedList: queued
    keyed host + account                               │  every 10s
        │                                              ▼
        └→ logs only what changed                 5-thread pool
           (lsd churns; reported once)            AssetResolver
                                                  GET /bug_bounty/assets?q=…
                                                       │
                                                  one id → many assets:
                                                  ent_or_node → Meta Objects
                                                  xcontroller → Meta Context
                                                               → SPARTA target
                                                  graphql, bloks: no model yet
                                                       │
                                                       ▼
                                                  stored / failed / requeued
```

`FbdlRunFetcher` is the exception to the shape above: it is not fed by proxied traffic. An FBDL
run is the researcher's own and only the API knows it exists, so the queue is filled by a periodic
sweep of the list endpoint — which means a run created in the FBDL web UI is picked up just as
well as one created from an agent.

## Request path

```
 HttpRequestToBeSent
   │
   ├─ FbdlRewriter.rewrite(request)                 ── PLANE C
   │     {{fbdl.<run id>.<label>}} → the value that run created
   │     unpinned placeholders are left alone; only a pinned run resolves
   │
   ├─ not a Meta URL? ─────────────────────────────► forward untouched
   ├─ both CSRF toggles off for this tool? ────────► forward untouched
   ├─ store.peek(host, account) == null ? ─────────► forward untouched
   │      (peek, not forHost — never create an entry for an unseen host)
   ▼
 CsrfRewriter.rewrite(request, tokens, placeholders, autoRefresh)   ── PLANE A
   │
   │  guard: pathIsSafeToRewrite = path().contains('?') || !url().contains('?')
   │         └─ Montoya doesn't specify whether path() carries the query;
   │            rewriting blind could silently drop it
   │  guard: encodeBody = contentType == URL_ENCODED
   │         └─ JSON/multipart take the token verbatim
   │
   ├─(1) placeholders:  {{fb_dtsg}} {{fb_dtsg_ag}} {{lsd}} {{csrf}}
   │        in path, body, and header values
   │        {{csrf}} → fb_dtsg_ag on GET, fb_dtsg otherwise, lsd if logged out
   │        unresolvable → left in place (blank is harder to debug)
   │
   ├─(2) auto-refresh:  name=value pairs already present get the live value
   │        + x-fb-lsd header
   │
   └─(3) IF AND ONLY IF something moved:
            effectiveToken ← precedence fb_dtsg ▸ fb_dtsg_ag ▸ lsd, url-decoded
            jazoest ← version ++ Σ(UTF-8 bytes)    ◄── concatenated, not added
            written back into path and body
```

Step 3 is what makes replay work at all: swap a token without recomputing `jazoest` and the
request fails sprinkle validation *and* flags as scripted traffic.

### Why there is no token-minting backend

There cannot be one. `fb_dtsg` is session-bound — the server appends the session id and its
creation time to the token, and validation requires both to match the *validating* request's
session. A call authenticated by an OAuth bearer token has no browser session, so the session id
is 0 and a minted token is guaranteed to fail when replayed from cookie-authenticated traffic.
Scraping client-side sidesteps this and is self-refreshing. The consequence is that this plane
adds no credential surface and has nothing to gate on access control.

### Why FBDL placeholders name their run

A placeholder is `{{fbdl.<run id>.<label>}}`, and nothing resolves without the run id. An FBDL run
mints its own test assets, so two runs of the same script produce the same labels with different
values — researchers name them conventionally, `UserOne`, `PageOne`. Left to resolve on their own,
an unrelated run would silently repoint a saved request at another engagement's user, which fails
as a request that succeeds against the wrong thing.

The run id is written by the **Pin FBDL run…** action rather than typed, because Burp exposes no
identity for a Repeater tab: `EditorCreationContext` carries only a tool and a mode, and
`HttpRequestToBeSent` only a per-send message id. Writing the run id into the request is what makes
the choice stick — the request *is* the tab, it survives a project reload, and it is visible to the
researcher rather than hidden in extension state.

## Data model, and why it is shaped this way

Almost every structural decision here is downstream of what Montoya offers for storage.

### What Burp gives you

```
api.persistence()
   ├── preferences()    → Preferences      the Java preference store
   └── extensionData()  → PersistedObject  a node in the .burp project file

...and that is the entire menu. No table, no query, no index, no Set,
no transaction, no TTL.
```

`Preferences` supports `String Boolean Byte Short Integer Long` — flat scalars only, no list, no
nesting. `PersistedObject` adds `ByteArray`, `HttpRequest` / `HttpResponse` /
`HttpRequestResponse`, `PersistedList<T>` of most of those, and `childObject`, which makes it
recursive. Both expose `*Keys()` enumeration per type, and each type has its own independent key
namespace.

Per the Montoya javadoc, `extensionData()` stores into the Burp project file — and when Burp is
started without a project file, into memory instead.

### The three tiers

```
┌─ TIER 1 · CONFIG ───────┐┌─ TIER 2 · CACHE ────────┐┌─ TIER 3 · SECRETS ──────┐
│ Preferences             ││ PersistedObject tree    ││ plain ConcurrentHashMap │
├─────────────────────────┤├─────────────────────────┤├─────────────────────────┤
│ Access Token            ││ Meta Object Info records││ CsrfTokenStore          │
│ API Base URL            ││ Meta Url Info records   ││   fb_dtsg / _ag / lsd   │
│ Log Level               ││ SPARTA finding records  ││   sprinkle param+version│
│ 8 × CSRF checkboxes     ││ FBDL run records        ││                         │
│ 4 × fetcher checkboxes  ││ queue-state lists       ││                         │
│ Send PoCs to Organizer  ││ Organizer sent-ids list ││                         │
├─────────────────────────┤├─────────────────────────┤├─────────────────────────┤
│ Java pref store, per    ││ inside the .burp file   ││ RAM. Dies on unload.    │
│ USER not per project.   ││ (or RAM if Burp was     ││                         │
│ Survives Burp restart   ││ opened with no project) ││                         │
│ and project switch.     ││ Dies with the project.  ││                         │
├─────────────────────────┤├─────────────────────────┤├─────────────────────────┤
│ WHY HERE: settings must ││ WHY HERE: an engagement'││ WHY *NOT* IN THE API:   │
│ outlive any one         ││ s resolved FBIDs are    ││ extensionData is written│
│ engagement.             ││ worth keeping, cheap to ││ to disk. These are live │
│                         ││ refetch if lost.        ││ session credentials.    │
└─────────────────────────┘└─────────────────────────┘└─────────────────────────┘
```

Tier 3 is the one to understand: Burp would happily persist those tokens, and that is exactly why
the code refuses to use the API for them.

### The persisted tree

```
extensionData()                                   ← root, serialized into project.burp
│
├── childObject "Meta Object Info"        ◄─ one per ZurpDataFetcher subclass,
│   │                                        named by getDataTypeName()
│   ├── stringList "data_queued"  ["10006412345678", "61237498765432", …]  ┐
│   ├── stringList "data_failed"  ["10008899887766", …]                    ├ STATE
│   ├── stringList "data_stored"  ["10006412345678", …]                    ┘
│   │
│   ├── childObject "10006412345678"                     ┐
│   │     ├── string "object_id"    "10006412345678"     │ RECORD
│   │     ├── string "object_name"  "Test User 42"       │ keyed by FBID
│   │     └── string "object_type"  "USER"               ┘
│   ├── childObject "61237498765432"  → { … }
│   └── … one child per resolved FBID
│
├── childObject "Meta Url Info"
│   ├── stringList "data_queued" / "data_failed" / "data_stored"
│   └── childObject "https://www.facebook.com/ajax/foo/?id=123&__a=1"
│         ├── string "url"
│         └── string "controller_name"  "FooAjaxController"
│
└── childObject "FBDL Runs"
    ├── childObject "runs"            one child per run, keyed by run id
    └── childObject "results"         label → value, plus an ordered label list
```

URL records are keyed by the entire URL including query string, so every distinct query string
becomes its own node. FBDL results are a keyed child object rather than a list so a single label
reads back in one call — the placeholder rewriter needs that on the request path — with the labels
additionally kept as an ordered list, because server order is not a property of a keyed object.

### Constraint to consequence

| Montoya gives you… | …so Zurp has to |
|---|---|
| `Preferences` has no list or map type | Synthesize composite keys by string prefix: `"CSRF Placeholder: " + tool.name()` gives 8 flat booleans. `ZurpPrefEnum` exists to keep that key-minting in one place. |
| `getBoolean` returns a nullable `Boolean`, no default concept | Resolve defaults *on read* (`ZurpUtils.readBoolPref`). A pref never written comes back `null` and would otherwise silently read as `false`. That null is also the hook `ZurpEnv` seeding hangs off: unset means "ask the environment", so it costs no extra state. |
| child-object keys are the only index | The FBID **is** the primary key: `setChildObject(fbid, record)`. Lookup by ID is the only access path that exists. |
| No `Set` type, only `PersistedList` | Membership is a linear `contains()` scan. |
| No transactions, no compare-and-set | Every multi-list mutation needs an application-level lock (`synchronized (this)`). |
| `extensionData` writes to disk | CSRF tokens bypass the whole API. |
| No TTL, no eviction, no size cap | Nothing ever shrinks. `data_failed` in particular is terminal. |

### One redundancy that is not forced by the API

```
FBID "10006412345678" seen in a response
  │
  ├─► data_queued  ─ append
  │        …10s later, worker resolves it…
  ├─► data_queued  ─ remove
  ├─► data_stored  ─ append              ┐
  └─► childObject["10006412345678"]      │ the same fact, stored three times
        └── string "object_id" = "10006412345678"
                                         ┘
```

`data_stored.contains(id)` is equivalent to `getChildObject(id) != null`, and `object_id` inside
the record duplicates the key it is filed under. Montoya does provide `childObjectKeys()`, so
unlike the queue lists, `data_stored` is not forced by the API — it is a hand-rolled parallel index
over data the tree already keys. It also grows monotonically for the life of the project file. The
lookup itself is cheap — the three sets are `ConcurrentHashMap` key sets rather than
`PersistedList`s, so membership is O(1), not the linear scan the shape suggests.

## Your request budget

The API gives **you** 1000 requests an hour on each endpoint, keyed on your own account rather than
on the app — so no other researcher can spend your budget, and you cannot spend theirs. Zurp holds
itself to half of that per endpoint (`HourlyCallBudget`), which leaves the other half for whatever
you fire by hand, and for an agent running the
[`bug-bounty-research`](../bug-bounty-research/README.md) MCP servers on the same token. When its
half runs out the fetchers idle until the hour rolls; nothing is dropped, and nothing you type is
throttled by what Zurp was doing in the background.

Asset lookups do not spend that budget one identifier at a time. Each tick resolves everything
queued through the batch endpoint — 200 identifiers a call, on its own allowance of 100 calls an
hour — so a page load that yields sixty candidates costs one call rather than sixty. Only an
identifier that actually resolved, and whose vanity name is wanted, costs a second single lookup.
The two paths are separate buckets server side and are tracked separately here, so a spent
single-lookup budget does not stop batching.

If the server rate-limits anyway, Zurp stops asking for five minutes rather than retrying on the
next tick. A 429 reaches the server and so counts against you: retrying through one spends the very
budget it is waiting for. Note that reloading the extension resets Zurp's own counter but not the
server's, so a reload is not a way out of a 429.

Turning a fetcher off is the lever if you want the whole budget for yourself. The **Meta Object
Info** fetcher is the hungriest: it queues every 14–18 digit number it sees in a request or a
response. Much of that is not an FBID at all — on one page load, two thirds of the candidates were
microsecond clock readings — so identifiers that cannot resolve are dropped before they are queued:
a leading zero, a `doc_id` the GraphQL fetcher already claimed, and a 16-digit value whose leading
digits read as a millisecond timestamp near now.

Zurp's own calls go through `api.http()`, so they appear in Burp's **Logger** under tool
`Extensions` and obey Burp's network settings, including its timeouts and upstream proxy. Logger is
the first place to look when a fetch misbehaves, and it works regardless of Zurp's own log level.

## Status and known gaps

- **Two asset kinds have no model yet.** `/bug_bounty/assets` resolves `graphql` and `bloks` as
  well as the two kinds Zurp stores, and both are dropped on the floor today. Adding one is a new
  model and a new reader — `AssetResolver` already carries every kind the endpoint returns, so
  nothing upstream of the model has to change. A future `graphql` model is expected to carry the
  operation's allowed variables, which is why the per-kind detail belongs in the model rather than
  in the flat `{type, name}` pair.
- **A model holds one asset of its kind.** One identifier can name several — a URL carrying an FBID
  in its query string is both an XController and an Ent — and where the endpoint returns two of the
  same kind, the first wins and the rest are named at DEBUG. Joining them is not an option:
  `controllerName` is handed straight to `SpartaTarget.endpointName`.
- **Upstream TLS is not verified.** `Http::sendRequest` does not verify by default, and Zurp does
  not pass `RequestOptions::withUpstreamTLSVerification`, so a non-public CA is accepted. Unlike
  the `TrustEverythingTrustManager` this replaced, the leniency is scoped to Zurp's own requests
  rather than installed over the whole JVM.
- **`dataFailed` is terminal.** A fetch that fails blacklists that FBID for the life of the project
  file, so only a definite answer may land there. Everything that says nothing about the identifier
  — no token typed in yet, a `403`, a `429`, the first two of three attempts — requeues instead.
- **The Organizer notes are best-effort.** They ride on an `HttpRequestResponse` built with a null
  response, which is the only way Montoya lets an extension attach `Annotations` to something it is
  sending. Nothing in the API says a null response is accepted, and it cannot be tried outside a
  running Burp, so `SpartaFindingOrganizer` catches the first failure and drops to the plain
  `sendToOrganizer(HttpRequest)` for the rest of the session. The request is the same either way;
  only the notes are lost.
- **Never use a `PersistedList` as a live collection.** It hands Burp's own element type back out,
  so reading an element as a `String` throws `ClassCastException` and `contains`/`remove` against a
  `String` never match. `ZurpDataFetcher` keeps its three sets in memory and treats the persisted
  lists as a write sink; anything new that persists a list should do the same.
- **Burp `2024.7` or newer is required**, for `burp.api.montoya.utilities.json`.
