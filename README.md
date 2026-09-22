# Zurp — a toolkit of Meta bug bounty research tools, usable from Burp Suite or from your coding agent.

It solves the need for bug bounty researchers to string together a different tool, a different
login and a different token for every question they have about a Meta endpoint. **Zurp is the
umbrella, not the tool**: FBDL, SPARTA and Meta Context are each their own tool with their own API,
and Zurp is where they meet. The Burp extension puts all of them in one suite tab, on the traffic
you are already proxying. The MCP servers and agent skills expose the same tools, over the same
APIs, on the same token, to whatever agentic setup you prefer — [Muse Code](#installing-the-mcp-servers),
MetaCode, Claude Code, or any MCP client.

| Tool | What it answers | In Burp | For an agent |
|---|---|---|---|
| **Meta Context** | "what *is* this id, URL, `doc_id` or operation?" | **Meta View** editor tab on every Meta request and response | `meta-context` MCP server |
| **SPARTA** | "what has the scanner already found against this endpoint?" | **SPARTA** tab, plus PoCs queued into Burp's **Organizer** | `sparta` MCP server |
| **FBDL** | "where do I get accounts I am allowed to attack?" | **FBDL** tab, `Pin FBDL run…`, `{{fbdl.*}}` placeholders | `fbdl` MCP server |
| **CSRF / sprinkle** | "why won't this captured request replay?" | automatic on the request path | — proxy-path only |

Use one, use all four. Nothing here depends on anything else here, and every one of them works
against your own account's data only.

## Examples

**Replay a captured request without hunting for a token.** Paste a request into Repeater, replace
the token with a placeholder, and send:

```http
POST /api/graphql/ HTTP/2
Host: www.facebook.com
Content-Type: application/x-www-form-urlencoded

fb_dtsg={{fb_dtsg}}&jazoest=0&doc_id=9876543210987654&variables={}
```

Zurp substitutes the live token scraped from your own browsing, per host *and per logged-in
account*, and recomputes `jazoest` — swap a token without recomputing it and the request fails
sprinkle validation *and* flags as scripted traffic.

**Point a saved request at a test environment you built.** After `Pin FBDL run…` from the editor's
context menu, labels from that run resolve on send:

```http
POST /api/graphql/ HTTP/2

variables={"pageID":"{{fbdl.1234567890123456.PageOne}}","actorID":"{{fbdl.1234567890123456.UserTwo.uid}}"}
```

**See what you are looking at.** Any Meta request or response gets a **Meta View** editor tab, with
a pane each for the objects named in it, the code serving it, and what has already been found
against that endpoint:

```
[ Meta Objects ]  [ Meta Context ]  [ SPARTA Findings ]

 10006412345678   USER    Test User 42
 61237498765432   GROUP   Test Group 7
```

**Ask your agent instead.** With the MCP servers installed, hand a raw capture to Muse Code or
Claude Code and let it scrape the identifiers out for you:

```
Extracted 3 candidates (1 doc_id, 1 url, 1 fbid). 1 named something, 2 matched nothing.

doc_id=9876543210987654  [doc_id]
  graphql  CometGroupsMallQuery
```

**Build the test environment from a script.** An FBDL script declares the accounts and objects you
want, and a run creates them and hands back their ids:

```
[setup] User Alice User Bob Friendship with {sender: Alice, receivers: [Bob]}
Alice make_post_text AlicePost with {place: Alice, text: 'Just joined the platform!'}
Bob like_post AlicePost
```

Ten worked scripts, up to an IDOR boundary setup, are in
[`bug-bounty-research/examples/`](bug-bounty-research/examples/).

## Requirements

Zurp requires or works with

* macOS or Linux (anywhere Burp Suite runs, including Windows — only the file paths in the docs
  assume the first two)
* **Burp Suite Professional `2024.7` or newer**, for the Burp extension. It parses JSON through
  `burp.api.montoya.utilities.json`, which landed in that release.
* **Java 17**, to build the extension. Burp supplies its own JVM to run it.
* **Node 22.19.0 or newer**, for the MCP servers. The Burp extension does not need it.
* **An MCP client**, for the agent side — Muse Code, MetaCode, Claude Code, or anything else that
  speaks MCP over stdio.
* **A bug bounty researcher API token.** Mint one at
  <https://www.facebook.com/whitehat/fbdl/generate_api_token> — you must be logged in to
  facebook.com for the mint to work. Tokens last 60 days, and **one token drives everything here**:
  the Burp extension and all three MCP servers share it.

### Getting access

Every tool in this toolkit is gated on the **same access criteria as FBDL**, and on the same
researcher allowlist. If you can use FBDL, you can use all of this with the token you already have;
if you cannot, none of it will work and the APIs will answer `403`.

The criteria, and how to qualify, are documented at <https://www.facebook.com/whitehat/fbdl/>.

Once enrolled, a `403` from any endpoint latches for the rest of the session rather than retrying
into a refusal — so a token that has not been granted access costs you one rejection per tool and
then nothing.

## Building Zurp

### The Burp extension

The [Montoya API][montoya] jar is the only thing on the compile classpath, and it is deliberately
not bundled: Burp installs the implementation at load time, so a copy inside our jar would shadow
Burp's and break the interfaces we implement.

```bash
javac -cp montoya-api-2024.7.jar -d out $(find burp -name '*.java')
jar cf zurp.jar -C out .
```

That is the whole build — Zurp has no third-party dependencies beyond `burp.api.montoya.*`.

To run the JUnit 5 suite in `test/`, add `montoya-api`, `junit-jupiter` and
`junit-platform-console` to the classpath. The tests touch no network. Note that JSON cannot be
parsed outside Burp — the Montoya jar is interfaces only — so they cover everything up to and
around that boundary.

### The MCP servers

```bash
cd bug-bounty-research
npm install
npm run build      # tsc -> dist/
npm test           # tsc, then node --test
```

## Installing Zurp

### The Burp extension

In Burp: **Extensions → Installed → Add → Extension type `Java` → `zurp.jar`**. To pick up a
rebuild, untick and retick **Loaded**.

Then open the **Zurp** suite tab and fill in **Access Token** under **Settings**. That is the only
required setting; everything else has a working default. The token is stored per user rather than
per project, so it survives opening a new Burp project.

Every setting can also be supplied before Burp starts, as an environment variable or a JVM
property, so a fresh install comes up already configured:

```bash
export ZURP_ACCESS_TOKEN='EAAB…'
exec /opt/BurpSuitePro/BurpSuitePro
```

See [docs/CONFIGURATION.md](docs/CONFIGURATION.md) for every setting, both spellings of each, and
the precedence rules.

### Installing the MCP servers

[`bug-bounty-research/`](bug-bounty-research/README.md) is a standalone Node package that shares no
code with the Java here, so a researcher who does not run Burp can install it on its own. Each tool
is a separate server — register the ones you want.

The package is not on a public registry, so build it once and register the servers by path:

```bash
cd bug-bounty-research
npm install
npm run build          # tsc -> dist/
pwd                    # the absolute path used below
```

That leaves three entry points, each one a stdio MCP server: `dist/meta-context/index.js`,
`dist/sparta/index.js` and `dist/fbdl/index.js`. Run them with `node`, by absolute path.

**Muse Code** has no `mcp add` subcommand — `muse mcp` only logs in to remote servers over OAuth.
Register a server by editing `${XDG_CONFIG_HOME:-$HOME/.config}/muse/settings.json` and adding it to
the `mcpServers` object, creating the file as `{"schema_version": 1, "mcpServers": {…}}` if it is not
there yet:

```json
{
  "schema_version": 1,
  "mcpServers": {
    "meta-context": {
      "type": "stdio",
      "command": "node",
      "args": ["/path/to/Zurp/bug-bounty-research/dist/meta-context/index.js"],
      "env": { "BB_RESEARCH_TOKEN": "EAAB…" },
      "mode": "optional"
    },
    "sparta": {
      "type": "stdio",
      "command": "node",
      "args": ["/path/to/Zurp/bug-bounty-research/dist/sparta/index.js"],
      "env": { "BB_RESEARCH_TOKEN": "EAAB…" },
      "mode": "optional"
    },
    "fbdl": {
      "type": "stdio",
      "command": "node",
      "args": ["/path/to/Zurp/bug-bounty-research/dist/fbdl/index.js"],
      "env": { "BB_RESEARCH_TOKEN": "EAAB…" },
      "mode": "optional"
    }
  }
}
```

Four things about that file are easy to get wrong, and three of them fail quietly:

* **The token has to be inside `env`.** Muse starts a stdio server with a fixed allowlist of your
  environment — `HOME`, `LANG`, `LOGNAME`, `PATH`, `PWD`, `SHELL`, `SHLVL`, `TERM`, `USER` — plus
  whatever that entry's `env` map holds, and it does not expand `${VAR}`. An exported
  `BB_RESEARCH_TOKEN` never reaches the server, and neither do `HTTPS_PROXY`, `NO_PROXY` or
  `NODE_EXTRA_CA_CERTS`: to run these through Burp, put those in `env` too.
* **Keep `"mode": "optional"`.** A server is required by default, so one that fails to start takes
  Muse down with it. Never write `required` next to `mode` — the pair is an ambiguous-alias fault
  that drops your *whole* `mcpServers` member, so every server you already had stops loading and
  plugin installs start failing with `MCP configuration error`.
* **The key is `mcpServers`, camelCase.** A legacy `mcp_servers` key sitting next to it makes the
  loader drop the MCP member entirely; rename it rather than adding beside it.
* **Changes apply on the next launch.** The running session does not pick up a new server.

The agent skills install one at a time, from the package:

```bash
cd bug-bounty-research
for s in meta-context sparta use-fbdl-mcp generate-fbdl validate-fbdl; do
  muse skills install "skills/$s" --scope user
done
```

**MetaCode** — a different product, with an `mcp` subcommand that writes the config for you:

```bash
export BB_RESEARCH_TOKEN='EAAB…'

metacode mcp add meta-context -- node /path/to/Zurp/bug-bounty-research/dist/meta-context/index.js
metacode mcp add sparta       -- node /path/to/Zurp/bug-bounty-research/dist/sparta/index.js
metacode mcp add fbdl         -- node /path/to/Zurp/bug-bounty-research/dist/fbdl/index.js
```

Unlike Muse, MetaCode hands a stdio server your whole environment, so an exported token reaches it.
Add `--scope project` to write into the repo's config instead of your global one — it needs the
directory to be a git repository — or `--env BB_RESEARCH_TOKEN=EAAB…` to pin the token per server.
`metacode mcp list` shows what registered and whether it connected.

**Claude Code and any other MCP client** — point it at the same three commands:

```bash
node /path/to/Zurp/bug-bounty-research/dist/meta-context/index.js
node /path/to/Zurp/bug-bounty-research/dist/sparta/index.js
node /path/to/Zurp/bug-bounty-research/dist/fbdl/index.js
```

The agent skills in [`bug-bounty-research/skills/`](bug-bounty-research/skills/) teach an agent
which tool to reach for and which errors are worth retrying. They ship with the package; copy them
into your agent's skill directory if your client does not pick them up automatically.

## Features and capabilities

### Meta Context — identifiers to the code behind them

Resolves an object id, a URL, a persisted `doc_id`, a GraphQL operation name or a `com.bloks.*` id
to the asset that serves it: an Ent or Node type, an XController, a GraphQL operation.

* **In Burp**, it runs on its own. Every Meta response you proxy is scanned for URLs and for
  15–18 digit numbers, resolved in the background on a 10s tick, and rendered in the **Meta View**
  editor tab that Zurp adds to every Meta request and response.
* **For an agent**, `meta_context_scan` takes a whole report, HAR excerpt or log and extracts and
  resolves every candidate in one request; `meta_context_resolve_batch` takes up to 200 already
  isolated identifiers; `meta_context_resolve` takes one and is the only tool that returns a vanity
  name.
* An identifier that resolves to nothing is a normal answer, not an error — most ids scraped out of
  live traffic name nothing at all. One identifier can also name several assets: a URL carrying an
  object id in its query string is both an XController and an Ent.

This is a map from identifiers to **symbol names**. It is not code search: no file paths, no line
numbers, no source, and nothing about who owns an asset.

### SPARTA — findings the scanner already raised

SPARTA is Meta's automated offensive-security scanner. Some of what it finds is disclosed to
researchers on a private bounty as **leads**: a title, a summary, and a proof of concept with the
identifiers taken out. A lead is not a confirmed vulnerability — confirming one is your work, and
the report is yours.

* **In Burp**, findings for the endpoint you are looking at appear in the **Meta View** tab and in
  the **SPARTA** tab. **Send PoCs to Organizer** (on by default) queues each newly disclosed PoC
  into Burp's **Organizer** as a ready-to-send request, once per finding, remembered in the project
  file — so you work them from your own queue. Only findings for endpoints you actually browsed are
  sent; the hourly catalog sweep stays out of your Organizer.
* **For an agent**, `sparta_scan_traffic` pulls the `doc_id` and `fb_api_req_friendly_name` out of a
  capture and reports every finding raised against them; `sparta_list_findings` gives the whole
  disclosed catalog worst-first; `sparta_get_finding` and `sparta_build_poc` take one finding to the
  GraphQL call it describes.
* **A disclosed PoC reproduces nothing as written.** Every value is a `{{TOKEN}}` placeholder, a
  boolean, or null — enforced where the finding is published, so a concrete identifier cannot be in
  there. Substitute from a test environment you built with FBDL, never an identifier belonging to a
  real account. In Burp, `fb_dtsg` is a placeholder too, filled in by the CSRF plane on send.

This is not a list of open vulnerabilities at Meta. It is the subset a scanner raised, that survived
triage, that was chosen for disclosure, on a bounty you are enrolled in.

### FBDL — test environments you are allowed to attack

FBDL is a small declarative language for building a test environment: users, pages, groups, apps,
and the relationships between them. A run creates them and hands back the ids and credentials.

* **In Burp**, the **FBDL** tab lists your runs and what each one created, swept periodically from
  the API — so a run you created in the FBDL web UI or from an agent shows up just the same.
  `Pin FBDL run…` in the Repeater and Intruder context menu writes a run id into the request, and
  `{{fbdl.<run id>.<label>}}` placeholders then resolve to that run's values on send.
* **For an agent**, the language tools cost no API budget at all — the grammar is fetched once and
  cached, so `validate_fbdl`, `list_entities`, `list_actions` and `explain_fbdl` are free. The run
  tools are `create_fbdl_run` (validated locally first, so a script that will not parse never
  reaches the API), `get_fbdl_run`, `list_fbdl_runs` and `archive_fbdl_run`.
* Placeholders name their run on purpose. Two runs of the same script produce the same labels with
  different values, so a placeholder that resolved on its own would silently repoint a saved request
  at another engagement's user — a request that succeeds against the wrong thing.

### CSRF and sprinkle — making a captured request replay

Burp-only, and the one piece with no agent equivalent: it is a property of sitting on the proxy
path. Zurp scrapes `fb_dtsg`, `fb_dtsg_ag`, `lsd` and the sprinkle configuration out of the
responses you browse, keyed per host *and per logged-in account*, then on the way out substitutes
`{{fb_dtsg}}`, `{{fb_dtsg_ag}}`, `{{lsd}}` and `{{csrf}}` and recomputes `jazoest`. Enabled per tool;
placeholders default on for Repeater and Intruder, auto-refresh defaults off everywhere.

**There is no token-minting backend, and there cannot be one.** `fb_dtsg` is session-bound: the
server appends the session id and creation time, and validation requires both to match the
validating request's session. A call authenticated by a bearer token has no browser session, so a
minted token is guaranteed to fail when replayed from cookie-authenticated traffic. Scraping
client-side sidesteps this and is self-refreshing — which means this plane adds no credential
surface at all. Tokens live in memory and never reach the project file.

### Across the whole toolkit

* **One token, one budget.** The APIs allow each researcher 1000 requests an hour *per endpoint*,
  keyed on your account rather than on the app — so no other researcher can spend your budget, and
  you cannot spend theirs. Spending one endpoint's budget leaves the others untouched. Zurp holds
  itself to half of each endpoint's allowance, leaving the rest for what you fire by hand and for
  an agent on the same token; the MCP servers pace themselves against the full allowance, so a
  fan-out over a large report cannot spend the hour in seconds.
* **Every background lookup has a kill switch**, one checkbox per fetcher in the Burp tab, so a tool
  you are not using costs nothing.
* **Zurp is silent by default.** Its log level starts at `NONE`: Burp's extension output belongs to
  the researcher. Its own HTTP goes through Burp's stack, so it shows up in **Logger** under tool
  `Extensions` and obeys Burp's proxy and timeout settings.
* **Nothing is ever rethrown.** Zurp sits on the request path, so an unexpected request or response
  shape must never break your traffic.

For the full picture — package map, request and response paths, the persistence model and the
constraints that forced it, and the current known gaps — see
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Full documentation

* [docs/CONFIGURATION.md](docs/CONFIGURATION.md) — every Burp setting, environment and JVM seeding,
  log levels, and the `SSLProtocolException` fix.
* [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — how the pieces fit together, the data model, and
  known gaps.
* [bug-bounty-research/README.md](bug-bounty-research/README.md) — the three MCP servers, their full
  tool surface, configuration and shared budget.
* [FBDL access criteria](https://www.facebook.com/whitehat/fbdl/) — who can use these tools, and how
  to qualify. The same criteria gate every tool here.
* [PortSwigger's Montoya API documentation][montoya] — the extension API the Burp side is written
  against.
* [Meta Bug Bounty program terms](https://www.facebook.com/whitehat) — what is in scope, and the
  rules every one of these tools assumes you are working under.

## Join the Zurp community

* Website: <https://bugbounty.meta.com/>
* Facebook page: <https://www.facebook.com/BugBounty>
* GitHub: <https://github.com/facebookincubator/Zurp> — issues and pull requests
* Mailing list: none — use GitHub Issues
* irc: none — use GitHub Issues

Found a security bug in Meta's products while using this? That goes through the
[bounty program](https://bugbounty.meta.com/), not a GitHub issue.

See the [CONTRIBUTING](CONTRIBUTING.md) file for how to help out.

## License

Zurp is MIT licensed, as found in the [LICENSE](LICENSE) file.

The FBDL server in `bug-bounty-research/` is ported from
[fbdl-mcp](https://github.com/GangGreenTemperTatum/fbdl-mcp), created by Ads Dawson

[montoya]: https://portswigger.github.io/burp-extensions-montoya-api/javadoc/burp/api/montoya/package-summary.html
