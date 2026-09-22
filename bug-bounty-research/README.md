# bug-bounty-research — the Zurp toolkit as MCP servers, so your coding agent can use the same tools your proxy does.

It solves the need for a bug bounty researcher to be the integration layer between their agent and
Meta's research APIs — pasting ids into a browser tab, copying findings back into a prompt, building
test accounts by hand. This package is the agent-facing half of [Zurp](../README.md): the same
three tools, over the same APIs, on the same token, so what your agent knows and what your Burp tab
shows never disagree. Each tool is its own MCP server; register one, or all three.

| Server | What it answers |
|---|---|
| **`meta-context`** | Resolves an identifier you saw in traffic — an object id, a URL, a `doc_id`, a GraphQL operation name, a Bloks id — to the code that serves it. |
| **`sparta`** | Reads the SPARTA scanner findings disclosed to you, finds the ones covering an endpoint you are looking at, and builds the request a finding's proof of concept describes. |
| **`fbdl`** | Writes, validates and explains FBDL scripts offline, then runs them to build a test environment — test users, pages, groups, apps — and hands back the ids and credentials it created. |

They pair. SPARTA points at an endpoint worth looking at; Meta Context tells you what the code
behind it is actually called; FBDL gets you accounts you are allowed to attack, which is what you
fill a disclosed proof of concept with.

You do not need Burp to use any of this. If you do run Burp, install
[Zurp](../README.md) alongside and both halves share one token and one budget.

## Examples

**Hand your agent a raw capture and ask what it is.** `meta_context_scan` scrapes every candidate
identifier out of arbitrary text and resolves them in a single request:

```
Extracted 3 candidates (1 doc_id, 1 url, 1 fbid). 1 named something, 2 matched nothing.

doc_id=9876543210987654  [doc_id]
  graphql  CometGroupsMallQuery
```

An identifier that resolves to nothing is a normal answer, not an error — most ids scraped out of
live traffic name nothing at all.

**Ask what the scanner already found against that endpoint.** `sparta_scan_traffic` takes the same
capture, pulls out the `doc_id` and `fb_api_req_friendly_name`, and reports every finding raised
against them:

```
[P2] 1234567890  Group member bio exposed to non-members
  doc_id=9876543210987654  ·  disclosed 2026-02-14
  An unauthenticated viewer can read the bio field of a private group's members.

Proof of concept available — sparta_build_poc({finding_id: "1234567890"}).
```

**Build something you are allowed to attack.** An FBDL script declares the environment; a run
creates it and hands back the ids:

```
[setup] User Alice User Bob Friendship with {sender: Alice, receivers: [Bob]}
Alice make_post_text AlicePost with {place: Alice, text: 'Just joined the platform!'}
Bob like_post AlicePost
```

`validate_fbdl` checks that against the real grammar before it ever reaches the API, and costs no
budget. Ten worked scripts, from a two-user follow to an IDOR boundary setup, are in
[`examples/`](examples/).

## Requirements

bug-bounty-research requires or works with

* **Node 22.19.0 or newer.**
* **An MCP client** — Meta Code, Claude Code, or anything else that speaks MCP over stdio.
* **A bug bounty researcher API token.** Mint one at
  <https://www.facebook.com/whitehat/fbdl/generate_api_token> — you must be logged in to
  facebook.com for the mint to work. Tokens last 60 days.
* Optionally, **[Zurp](../README.md) in Burp Suite**, which uses the same token and the same
  budget.

One token drives all three servers: the endpoints share an OAuth app and a capability.

### Getting access

Every server here is gated on the **same access criteria as FBDL**, and on the same researcher
allowlist. If you can use FBDL, you can use all three with the token you already have; if you
cannot, none of them will work and the APIs will answer `403`. The criteria, and how to qualify,
are documented at <https://www.facebook.com/whitehat/fbdl/>.

A `403` latches: once the API says the account is not enrolled, no further calls are attempted for
the life of the process, because the answer cannot change and each attempt still costs a slot.

## Building bug-bounty-research

```bash
npm install
npm run build       # tsc -> dist/
npm test            # tsc, then node --test
npm run typecheck
```

No network is touched by the test suite: the HTTP client takes its `fetch` by injection, the clock
and the sleep are injectable so retry and pacing are tested without waiting, and the MCP-protocol
tests drive a real client against a real server over an in-memory transport.

## Installing bug-bounty-research

**Meta Code** — register the servers you want:

```bash
export BB_RESEARCH_TOKEN='EAAB…'

metacode mcp add meta-context -- npx -y bug-bounty-research meta-context-mcp
metacode mcp add sparta       -- npx -y bug-bounty-research sparta-mcp
metacode mcp add fbdl         -- npx -y bug-bounty-research fbdl-mcp
```

Add `--scope project` to write into the repo's config instead of your global one, or
`--env BB_RESEARCH_TOKEN=EAAB…` to pin the token per server rather than inheriting it from the
shell. `metacode mcp list` shows what registered and whether it connected.

**Claude Code**, where the plugin registers all three servers *and* their agent skills in one step:

```bash
claude plugin install bug-bounty-research
export BB_RESEARCH_TOKEN='EAAB…'
```

**Any other MCP client** — the three binaries speak stdio and can be run directly:

```bash
npx -y bug-bounty-research meta-context-mcp
npx -y bug-bounty-research sparta-mcp
npx -y bug-bounty-research fbdl-mcp
```

### Configuration

```bash
export BB_RESEARCH_TOKEN=EAAB…
```

That is the whole required setup. Every other setting has a working default.

Each setting is read from a server-specific variable first and a shared `BB_RESEARCH_*` variable
second, so you can tune one server without touching the others.

| Setting | Shared | Per-server | Default |
|---|---|---|---|
| Token | `BB_RESEARCH_TOKEN` | `FBDL_API_TOKEN`, `ZURP_ACCESS_TOKEN` | — |
| API host | `BB_RESEARCH_API_BASE_URL` | `FBDL_API_BASE_URL`, `ZURP_API_BASE_URL`, `SPARTA_API_BASE_URL` | `https://api.facebook.com` |
| Request timeout (ms) | `BB_RESEARCH_HTTP_TIMEOUT_MS` | `…_HTTP_TIMEOUT_MS` | `30000` |
| Ceiling on every endpoint's hourly budget | `BB_RESEARCH_RATE_LIMIT_PER_HOUR` | `…_RATE_LIMIT_PER_HOUR` | `1000` |
| Burst before pacing | `BB_RESEARCH_RATE_LIMIT_BURST` | `…_RATE_LIMIT_BURST` | `20` |
| Longest pacing wait (ms) | `BB_RESEARCH_RATE_LIMIT_MAX_WAIT_MS` | `…_RATE_LIMIT_MAX_WAIT_MS` | `5000` |
| Attempts per request | `BB_RESEARCH_RETRY_ATTEMPTS` | `…_RETRY_ATTEMPTS` | `3` |
| Backoff base / cap (ms) | `BB_RESEARCH_RETRY_BASE_DELAY_MS`, `…_MAX_DELAY_MS` | `…` | `500`, `20000` |
| Requests in flight | `BB_RESEARCH_MAX_CONCURRENCY` | `…_MAX_CONCURRENCY` | `4` |
| Log level | `BB_RESEARCH_LOG_LEVEL` | `…_LOG_LEVEL` | `warn` |
| Skip TLS verification | `BB_RESEARCH_INSECURE_TLS` | `…_INSECURE_TLS` | `false` |

A missing token is reported when you call a tool, not at startup — a server that exits during the
handshake shows up in your client as "server failed to start", which tells you nothing about what
to fix.

> The token is a bearer credential. Prefer whatever your client offers for reading it from the
> environment over writing it into a config file.

### Running through Burp

The standard proxy variables are honoured, so these servers show up in your proxy history next to
everything else:

```bash
export HTTPS_PROXY=http://127.0.0.1:8080
export NODE_EXTRA_CA_CERTS=$HOME/burp-ca.pem
```

`NO_PROXY` is respected, including `*`, bare domains, leading-dot domains and `host:port` entries.
`BB_RESEARCH_PROXY_URL` overrides all of them if you want these servers proxied and nothing else.

Point `NODE_EXTRA_CA_CERTS` at your interception CA rather than reaching for
`BB_RESEARCH_INSECURE_TLS=1`. The insecure flag disables certificate verification outright and logs
a warning every time it starts.

## Features and capabilities

### `meta-context`

- **`meta_context_scan`** — the one to reach for. Give it a report, a raw request/response, a HAR
  excerpt or a log, and it scrapes every candidate identifier out and resolves them in a single
  request. Caps at 200 candidates by default, raisable to 1000.
- **`meta_context_resolve_batch`** — up to 200 already-isolated identifiers per request, chunked
  automatically.
- **`meta_context_resolve`** — one identifier; the only tool that returns a vanity name.

One identifier can name several assets: a URL carrying an object id in its query string is both an
XController and an Ent.

This maps identifiers to **symbol names**. It is not code search — no file paths, no line numbers,
no source — and it says nothing about who owns an asset.

### `sparta`

SPARTA is Meta's automated offensive-security scanner. Some of what it finds is disclosed to
researchers on a private bounty as **leads** — a title, a summary, and a proof of concept with the
identifiers taken out. A lead is not a confirmed vulnerability; confirming one is your work, and
the report is yours.

- **`sparta_scan_traffic`** — the one to reach for. Give it a captured request, a HAR excerpt or a
  log; it pulls out the `doc_id` and `fb_api_req_friendly_name` and reports every finding raised
  against them.
- **`sparta_list_findings`** — everything disclosed to you, worst first. The endpoint returns the
  catalog in an order seeded per researcher rather than by date, so that sort is the server's doing
  being undone on purpose.
- **`sparta_get_finding`** — one finding by its `bb_finding_id`, the same id Zurp shows.
- **`sparta_build_poc`** — the GraphQL call the proof of concept describes.

The whole catalog is fetched once and reused, so a session of scanning traffic costs a handful of
requests however many endpoints you look at.

**A disclosed PoC reproduces nothing as written.** Every value in it is a `{{TOKEN}}` placeholder, a
boolean, or null — enforced where the finding is published, so a concrete identifier cannot be in
there. Substitute from a test environment you built with `fbdl`, never an identifier belonging to a
real account.

This is not a list of open vulnerabilities at Meta. It is the subset a scanner raised, that survived
triage, that was chosen for disclosure, on a bounty you are enrolled in. It says nothing about
whether a lead is still live or whether someone else is working it.

### `fbdl`

The language tools cost nothing — no API call, no budget — because the grammar is fetched once at
startup from `/bug_bounty/fbdl_reference/` and cached on disk:

- **`validate_fbdl`** — check a script against the real grammar: known entities and actions,
  required params, enum values, block structure.
- **`list_entities`**, **`list_actions`** — what you can declare and do, with parameters and
  examples.
- **`explain_fbdl`** — describe a script in plain English, line by line.
- Resource **`fbdl://reference`** and prompt **`generate_fbdl`**.

These spend budget:

- **`create_fbdl_run`** — submit a script. Validated locally first, so a script that will not parse
  never reaches the API. One submission in flight at a time, with a 30s cooldown after success and
  60s after failure — validation failures trigger neither.
- **`get_fbdl_run`** — status, created assets, or the failure, for one run.
- **`list_fbdl_runs`** — your runs, newest first, forward-paged.
- **`archive_fbdl_run`** — release a run's assets when you are done with it. If you hit the
  active-run cap, the error points you here.

`validate_fbdl` checks a script against the published grammar. It is not the server's parser, so a
script it passes can still be rejected on submission — but everything it catches is caught for free.

Runs created here show up in Zurp's **FBDL** tab, where `Pin FBDL run…` makes their labels available
as `{{fbdl.<run id>.<label>}}` placeholders in Repeater and Intruder.

### Budget

Requests are metered per researcher **per endpoint**, and the allowance is shared with anything else
using the same token — the other servers here, and Zurp in Burp. Each server keeps a local bucket
per endpoint mirroring the server's, so an agent fanning out over a large report cannot spend the
hour in seconds:

| Endpoint | Per researcher, per hour |
|---|---|
| `GET /bug_bounty/assets` | 1000 |
| `POST /bug_bounty/assets/batch` | **100** |
| each `/bug_bounty/fbdl_runs` route | 1000 |
| each `/bug_bounty/sparta_findings` route | 1000 |

The batch cap is an order of magnitude tighter because one batch is worth up to 200 singles — 100
full batches is still 20,000 identifiers an hour. Lowering `RATE_LIMIT_PER_HOUR` tightens every
bucket; it cannot loosen one past the server's own limit. Spending one endpoint's budget leaves the
others untouched.

Zurp is on the same budget when it is running: it holds itself to 500 an hour per endpoint, leaving
the other half for whatever you and your agent do.

- **Retries** use exponential backoff with full jitter on 429, 408 and 5xx. A 401 or a 403 is never
  retried — the answer will not change. Neither is a 429 whose `Retry-After` exceeds the backoff
  ceiling: these endpoints set it to the whole hour-long window, and retrying into that just spends
  two more requests against a budget the server has already said is gone. It comes back as
  `rate-limited` with the wait instead.
- **A 403 latches.** Once the API says the account is not enrolled, no further calls are attempted
  for the life of the process.
- **`meta_context_scan` caps at 200 candidates** per call. Raise its `limit` only for a large report
  you trust — a report is text someone else wrote, and padding it with thousands of plausible ids is
  an easy way to burn your quota.

### Agent skills

Everything under [`skills/`](skills/) ships with the package and is registered automatically by the
Claude Code plugin. They teach an agent which tool to reach for, the gotchas that cost requests to
discover, and which errors are worth retrying. Copy them into your agent's skill directory if your
client does not pick them up automatically.

### Layout

```
src/core/          shared by every server: config, HTTP, proxy, rate limiting, retries, logging
src/fbdl/          the FBDL server, ported from fbdl-mcp
src/meta-context/  the Meta Context server
src/sparta/        the SPARTA findings server
skills/            agent skills
examples/          worked FBDL scripts
```

Everything that is a property of *being a researcher's client* lives in `src/core/` — the bearer
token, the hourly budget, retry pacing, the shared RFC 7807 error envelope, and the rule that a
credential never reaches a transcript. The servers above it only know their own routes and payload
shapes.

## Full documentation

* [`skills/`](skills/) — the agent skills, which double as the most practical per-tool
  documentation.
* [`examples/`](examples/) — ten worked FBDL scripts, with [`examples/README.md`](examples/README.md)
  explaining what each one covers.
* [Zurp's README](../README.md) — the Burp Suite half of the toolkit, and how the two share a token.
* [FBDL access criteria](https://www.facebook.com/whitehat/fbdl/) — who can use these tools, and how
  to qualify.
* [Model Context Protocol specification](https://modelcontextprotocol.io) — the protocol these
  servers speak.
* [fbdl-mcp](https://github.com/GangGreenTemperTatum/fbdl-mcp) — the upstream the `fbdl` server was
  ported from.

## Join the bug-bounty-research community

* Website: <https://bugbounty.meta.com/>
* Facebook page: <https://www.facebook.com/BugBounty>
* GitHub: <https://github.com/facebookincubator/Zurp> — issues and pull requests
* Mailing list: none — use GitHub Issues
* irc: none — use GitHub Issues

Found a security bug in Meta's products while using this? That goes through the
[bounty program](https://bugbounty.meta.com/), not a GitHub issue.

See the [CONTRIBUTING](../CONTRIBUTING.md) file for how to help out.

## License

bug-bounty-research is MIT licensed, as found in the [LICENSE](LICENSE) file.

`src/fbdl/`, `skills/{generate-fbdl,use-fbdl-mcp,validate-fbdl}/`, `examples/` and the reference
fixture are ported from [fbdl-mcp](https://github.com/GangGreenTemperTatum/fbdl-mcp), created by Ads Dawson

Its tool surface, wording and JSON envelope are unchanged, so anything written against upstream
keeps working, and the ported files are kept close to upstream on purpose so fixes flow both ways.
