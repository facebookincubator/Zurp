# bug-bounty-research — the Zurp toolkit as MCP servers, so your coding agent can use the same tools your proxy does.

Zurp is Meta's open-source bug bounty research toolkit, designed from the start to be driven by an
agent as much as by hand. So that research can be done by hand, by an agent, or by both at once, the
repository carries every tool in two forms:

* **As a Burp extension**, which annotates the traffic already flowing through your proxy and gives
  each tool its own tab.
* **As MCP servers** — this package — which hand an agent the same capabilities over the same APIs
  and on the same token, so what your agent knows and what your Burp tab shows never disagree.

It solves the need for a bug bounty researcher to be the integration layer between their agent and
Meta's research APIs: pasting ids into a browser tab, copying leads back into a prompt, building
test accounts by hand.

| Tool | What it answers | In Burp | For an agent |
|---|---|---|---|
| **Meta Context** | what *is* this id, URL, `doc_id` or operation? | **Meta View** tab on every Meta request | `meta-context` server |
| **FBDL** | where do I get accounts I am allowed to attack? | **FBDL** tab, `{{fbdl.*}}` placeholders | `fbdl` server |
| **CSRF / sprinkle** | why won't this captured request replay? | automatic on the request path | n/a |
| **SPARTA** | what are the interesting leads in the targeted scope? | **SPARTA** tab | `sparta` server |
| **Whitehat Device** | how do I enable inspected traffic on my local app? | n/a | `whitehat-device` server |

Four of the five have an agent-side server, and each is its own MCP server; register one, or all
four. CSRF substitution has no agent equivalent and is covered in
[Zurp's README](../README.md) instead.

> **SPARTA is not always on.** It is made available during specific engagements we organise — a
> private bounty, a live hacking event — to give guidance on a targeted scope. Outside one, the
> catalog comes back empty, which is a normal answer rather than an error.

You do not need Burp to use any of this. If you do run Burp, install [Zurp](../README.md) alongside
and both halves share one token and one budget.

## How these tools fit together

They pair, in roughly a fixed order.

**Meta Context says what you are looking at.** A bare `1000641…` in a response body could be a user,
a group, a comment. Meta Context resolves object ids, ad accounts written `act_<digits>`, URLs and
persisted GraphQL `doc_id`s to their object type and their GraphQL or URL friendly name. It is a map
from identifiers to names and types; it is not code search.

**FBDL gets you accounts you are allowed to attack.** FBDL sets up security bug reproduction steps
through a standard "bug" description language, which is the long-standing hard part of demonstrating
a security issue — you declare the environment you need and it builds it.

**SPARTA says where to look**, when an engagement has it switched on. `sparta_scan_traffic` takes a
capture, pulls the `doc_id` and `fb_api_req_friendly_name` out of it, and reports every lead raised
against them.

If you also run Burp, the two halves meet at the placeholders: a run created by `create_fbdl_run`
shows up in the **FBDL** tab like any other, and CSRF substitution is what makes a request you
parked there still replay later. Both are
[Zurp's side of the toolkit](../README.md).

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

* **Node 22.19.0 or newer**, and npm.
* **An MCP client** — Muse Code, Claude Code, or anything else that speaks MCP over stdio.
* For local device configuration, **[idb](https://fbidb.io/)** with an iOS simulator, or `adb`
  with a debuggable Android app or a root adb shell (`adb root`).
* **A bug bounty researcher API token.** Mint one at
  <https://www.facebook.com/whitehat/fbdl/generate_api_token> — you must be logged in to
  facebook.com for the mint to work. Tokens last 60 days.
* Optionally, **[Zurp](../README.md) in Burp Suite**, which uses the same token and the same
  budget. The extension loads in Community Edition, but getting the full use of it needs Burp
  Professional.

One token drives the extension and the three API-backed MCP servers. Their endpoints share an OAuth
app and a capability. The local `whitehat-device` server touches only your own device and needs no
token.

### Getting access

Every API-backed server here is gated on the **same access criteria as FBDL**, and on the same
researcher allowlist. If you can use FBDL, you can use all three with the token you already have;
if you cannot, those APIs answer `403`. The local device server is independent. The criteria, and
how to qualify, are documented at <https://www.facebook.com/whitehat/fbdl/>.

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

Confirm the toolchain first — this is the one requirement with no graceful failure:

```bash
node --version      # 22.19.0 or newer
npm --version
```

This package is not on a public registry, so clone the repository, build it once, and register the
servers by path. Clone it into the standard per-user data directory, `~/.local/share/zurp`:

```bash
git clone https://github.com/facebookincubator/Zurp.git ~/.local/share/zurp
cd ~/.local/share/zurp/bug-bounty-research
npm install
npm run build          # tsc -> dist/
```

That leaves four entry points, each one a stdio MCP server:

```
dist/device/index.js
dist/meta-context/index.js
dist/sparta/index.js
dist/fbdl/index.js
```

Run them with `node`, by absolute path. Every example below uses the clone path above.

### Muse Code

Muse has no `mcp add` subcommand — `muse mcp` only logs in to remote servers over OAuth. Register a
server by editing `${XDG_CONFIG_HOME:-$HOME/.config}/muse/settings.json` and adding it to the
`mcpServers` object, creating the file as `{"schema_version": 1, "mcpServers": {…}}` if it is not
there yet:

```json
{
  "schema_version": 1,
  "mcpServers": {
    "whitehat-device": {
      "type": "stdio",
      "command": "node",
      "args": ["/Users/<name>/.local/share/zurp/bug-bounty-research/dist/device/index.js"],
      "env": { "IDB_UDID": "<simulator-udid>" },
      "mode": "optional"
    },
    "meta-context": {
      "type": "stdio",
      "command": "node",
      "args": ["/Users/<name>/.local/share/zurp/bug-bounty-research/dist/meta-context/index.js"],
      "env": { "BB_RESEARCH_TOKEN": "<your-researcher-api-token>" },
      "mode": "optional"
    },
    "sparta": {
      "type": "stdio",
      "command": "node",
      "args": ["/Users/<name>/.local/share/zurp/bug-bounty-research/dist/sparta/index.js"],
      "env": { "BB_RESEARCH_TOKEN": "<your-researcher-api-token>" },
      "mode": "optional"
    },
    "fbdl": {
      "type": "stdio",
      "command": "node",
      "args": ["/Users/<name>/.local/share/zurp/bug-bounty-research/dist/fbdl/index.js"],
      "env": { "BB_RESEARCH_TOKEN": "<your-researcher-api-token>" },
      "mode": "optional"
    }
  }
}
```

Then the skills, which teach the agent which tool to reach for and which errors are worth retrying.
Muse Code takes them one at a time:

```bash
cd ~/.local/share/zurp/bug-bounty-research
for skill in meta-context sparta use-fbdl-mcp generate-fbdl validate-fbdl; do
  muse skills install "skills/$skill" --scope user
done
```

### Any other MCP client

Point it at the same four commands; they speak stdio and can be run directly:

```bash
node ~/.local/share/zurp/bug-bounty-research/dist/device/index.js
node ~/.local/share/zurp/bug-bounty-research/dist/meta-context/index.js
node ~/.local/share/zurp/bug-bounty-research/dist/sparta/index.js
node ~/.local/share/zurp/bug-bounty-research/dist/fbdl/index.js
```

### Enabling whitehat inspection on local mobile apps

`whitehat_configure` turns whitehat inspection on or off for Facebook, Instagram, Messenger and
Threads, then restarts the app. It needs either:

* an iOS simulator running a simulator build of the app, through `idb` (App Store builds do not
  run on simulators); or
* an Android device or emulator through `adb`, with a debuggable build or a root adb shell. For
  store builds, use an emulator image without Google Play and run `adb root`.

It enables the app's whitehat settings and writes the same certificate settings as the in-app
Whitehat Settings screen. `enabled: false` turns inspection off and removes the tool's overrides.
`duration_seconds` limits inspection on Android and on Threads for iOS (24 hours by default, 7 days
at most). Instagram and Threads on iOS need `account_id`, the logged-in Instagram user id.

The tool does **not** configure a proxy or install a certificate. Set up the proxy and install its
CA first (in the user store on Android). Facebook and Threads on iOS also need the proxy's root
imported in the app's Whitehat Settings.

### Verifying it end to end

Start a new session and ask the agent what it can see, without letting it spend any budget to find
out:

```
List every MCP tool you have available, grouped by which server provides it.
Do not call any of them.
```

A healthy stack answers with the servers grouped and their tools namespaced under each. That one
prompt catches the common failures at once: a server that did not start shows up as a startup
warning and a missing group, a typo'd transport fails validation before the session appears, and a
`required` server that is down aborts the run outright.

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
> environment over writing it into a config file — except in Muse Code, which passes a stdio server
> almost none of your environment, so the literal `env` map in `settings.json` is the only way in.

### Running through Burp

The standard proxy variables are honoured, so these servers show up in your proxy history next to
everything else:

```bash
export HTTPS_PROXY=http://127.0.0.1:8080
export NODE_EXTRA_CA_CERTS=$HOME/burp-ca.pem
```

In Muse Code an export does not reach the server — put both in that server's `env` map in
`settings.json` instead.

`NO_PROXY` is respected, including `*`, bare domains, leading-dot domains and `host:port` entries.
`BB_RESEARCH_PROXY_URL` overrides all of them if you want these servers proxied and nothing else.

Point `NODE_EXTRA_CA_CERTS` at your interception CA rather than reaching for
`BB_RESEARCH_INSECURE_TLS=1`. The insecure flag disables certificate verification outright and logs
a warning every time it starts.

## Pointing it at Meta

> **The programme's rules apply from here.** Everything above is setup; past this point you are
> doing real research against Meta's products. Work only against accounts you own — the ones FBDL
> created for you — and make sure you have read and acknowledged the Meta Bug Bounty scope and
> terms. A finding goes to <https://bugbounty.meta.com/report/>, not a GitHub issue.

### Driving it from an agent

The division of labour is the point. The agent is the orchestrator: it creates the accounts, drives
the browsers, generates the traffic, searches the resulting proxy history, and parks the candidates
it likes in named Repeater tabs. You define the hypothesis, and you reproduce the candidate before
deciding a vulnerability exists.

Leaving the evidence in Repeater rather than in a transcript is what makes that second half
possible, and a request parked there still replays when you get to it.

A goal-shaped prompt, with the tools named and the boundary drawn:

```
Investigate contact point disclosure vulnerabilities in Facebook.

You can use:
- fbdl MCP to create controlled Whitehat test accounts.
- Burp MCP to search captured traffic, inspect requests and responses, and
  preserve tested requests in Repeater.
- meta-context MCP to determine what Meta identifiers, URLs, GraphQL document
  IDs, operation names, and Bloks IDs represent.
- sparta MCP to find GraphQL operations flagged by Meta's automated security
  scanner as potentially vulnerable, and generate PoC templates for validation.

A SPARTA lead is a starting point, not a confirmed vulnerability. Use
controlled FBDL values when testing a SPARTA proof of concept.

Investigation strategy:
Start from disclosed SPARTA leads. For promising ones, use FBDL to create
the account states and relationships needed to exercise the suspected
authorization boundary, then adapt and investigate the SPARTA PoC. Consider
adult and minor accounts, pending, current and revoked friendships, blocked
profiles, and primary and additional profiles belonging to the same account.

Every swap test needs a working unmodified baseline first. If the baseline
does not return the attacker's own data cleanly, the test proves nothing.

GraphQL mutations are writes: capture them, but replay nothing that changes
state without asking me first.

Stop after finding one reproducible positive. Send the relevant requests to
clearly named Burp Repeater tabs and preserve enough evidence for human
verification.
```

Two constraints are worth carrying into any version of that prompt: budget the replays explicitly,
and require control-vs-test evidence per verdict, so a run that finds nothing says so plainly
instead of burning the hour.

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

### Agent skills

Everything under [`skills/`](skills/) ships with the package. They teach an agent which tool to
reach for, the gotchas that cost requests to discover, and which errors are worth retrying. Muse
Code takes them one at a time with `muse skills install skills/<name> --scope user`; for any other
client, copy them into its skill directory.

### Layout

```
src/core/          shared by every server: config, HTTP, proxy, rate limiting, retries, logging
src/fbdl/          the FBDL server, ported from fbdl-mcp
src/meta-context/  the Meta Context server
src/sparta/        the SPARTA leads server
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
* [Meta Bug Bounty program terms](https://www.facebook.com/whitehat) — what is in scope, and the
  rules all of this assumes you are working under.
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
[bounty program](https://bugbounty.meta.com/report/), not a GitHub issue.

See the [CONTRIBUTING](../CONTRIBUTING.md) file for how to help out.

## License

bug-bounty-research is MIT licensed, as found in the [LICENSE](LICENSE) file.

`src/fbdl/`, `skills/{generate-fbdl,use-fbdl-mcp,validate-fbdl}/`, `examples/` and the reference
fixture are ported from [fbdl-mcp](https://github.com/GangGreenTemperTatum/fbdl-mcp), created by Ads Dawson

Its tool surface, wording and JSON envelope are unchanged, so anything written against upstream
keeps working, and the ported files are kept close to upstream on purpose so fixes flow both ways.
