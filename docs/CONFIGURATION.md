# Configuring Zurp

Everything here is set in the **Zurp** suite tab, under **Settings**. Every one of them can also be
seeded from outside Burp — see [Setting all of it before Burp starts](#setting-all-of-it-before-burp-starts).

## The two that matter

- **API Base URL** — defaults to `https://api.facebook.com/`. A trailing slash is optional.
- **Access Token** — sent as `Authorization: Bearer`. Mint one at
  <https://www.facebook.com/whitehat/fbdl/generate_api_token/>; you must be logged in to
  facebook.com for the mint to work, and tokens last 60 days. The `?access_token=` query parameter
  is *not* read — the header is the only place it is looked for.

  The endpoints want a real-person user token carrying the bug bounty research capability, and they
  are gated on the researcher allowlist. A `403` from any of them latches: Zurp stops asking for
  the rest of the session rather than spending your quota being refused.

## Background lookups

One checkbox per fetcher, all on by default. Unticking one stops its 10s tick and stops it queueing
anything new, so a fetcher you have no token for costs nothing rather than retrying forever.

It is deliberately not a pause button: re-ticking picks up what you browse next, not a replay of
everything you browsed while it was off.

| Fetcher | Fed by | Produces |
|---|---|---|
| Meta Object Info | every 15–18 digit number in proxied traffic | Ent / Node names in the **Meta View** editor tab |
| Meta Url Info | every Meta URL you proxy | the XController serving it |
| Sparta Findings | the controller names above | findings disclosed to you for that endpoint |
| FBDL Runs | a periodic sweep of your own runs | run ids and the assets each one created |

## SPARTA findings in the Organizer

**Send PoCs to Organizer**, in the SPARTA findings panel, is on by default. When a finding is
disclosed for a target you have just browsed, its proof of concept is queued in Burp's
**Organizer** as a ready-to-send request, so you work it from your own queue rather than reading it
out of Zurp's table and rebuilding it by hand.

- **Only what you browse.** The hourly catalog sweep, which fetches every finding disclosed to you
  including ones for products you have never opened, sends nothing. Emptying that into the
  Organizer at startup would bury the findings for whatever you are actually looking at.
- **Once per finding**, remembered in the project file. One you have triaged and deleted does not
  come back the next time you hit the same endpoint.
- **It will not reproduce anything as sent.** The disclosed variables carry `{{TOKEN}}` placeholders
  rather than identifiers — substitute your own test-user values first. The tokens are left
  unescaped in the body so they are findable, and the Organizer's notes column repeats them
  alongside what each one wants.
- **`fb_dtsg` is a placeholder too**, filled in by the CSRF plane when the request is finally sent.
  That is on by default for Repeater and Intruder, which is where these end up.

A finding whose PoC names neither a persisted document id nor an operation produces no request. It
is still recorded as handled, at DEBUG, rather than reconsidered on every request to its endpoint.

## Log Level

What Zurp itself writes to Burp's extension output, which is a different thing from Logger.
Defaults to **NONE**: that output belongs to the researcher, and an extension narrating itself into
it uninvited is noise.

| | |
|---|---|
| `NONE` | Nothing. Not even errors. |
| `VERBOSE` | A line per fetch, per tick and per failure — `GET <url> -> <status>`, what each fetcher resolved, which CSRF tokens moved. Exceptions appear as a one-line message. |
| `DEBUG` | VERBOSE, plus per-item outcomes, response sizes and the body of any non-200 (truncated at 512 bytes — it is where the API names the capability it wanted). Every caught exception keeps its **full stack trace** and is additionally raised as a Burp error event, so it shows on the Dashboard. |

Nothing is ever rethrown at any level. Zurp sits on the request path, so an unexpected request or
response shape must never break the researcher's traffic; DEBUG makes those swallowed failures
loud, not fatal.

Everything routes through `ZurpLog`, and `Zurp.logger` is package private so it cannot be dodged.
The level is cached in a `volatile` field rather than read from `Preferences` per line, and takes
effect the moment it is changed — no reload.

## Setting all of it before Burp starts

Every setting above can be supplied from outside Burp, so a fresh install or a Burp started against
a different user-config file comes up already configured instead of waiting to be typed into.

The name is mechanical: take the setting's label, drop the punctuation, and either uppercase it
with underscores for an environment variable or lowercase it with dots for a JVM property. So a
setting added after this was written is configurable without anything here being updated.

Booleans take `true/false`, `1/0`, `yes/no` or `on/off`; anything else falls back to the built-in
default rather than reading as off. Both sources are read once at startup, so editing either needs
a Burp restart.

### Every setting, as environment variables

Environment variables are only inherited by a Burp launched from a shell that has them, so this is
a launcher script rather than something to put in `.bashrc` and forget. Values below are the
built-in defaults, so in practice you would delete most of it and keep the two lines you care
about.

```bash
#!/bin/bash
# ~/burp.sh -- launch Burp with Zurp already configured

# Credentials and endpoint.
export ZURP_ACCESS_TOKEN='EAAB…'                       # no default; nothing works without it
export ZURP_API_BASE_URL='https://api.facebook.com/'

# Zurp's own chatter in Burp's extension output: NONE | VERBOSE | DEBUG
export ZURP_LOG_LEVEL='NONE'

# Background lookups, one per fetcher.
export ZURP_FETCH_SPARTA_FINDINGS='true'
export ZURP_FETCH_META_OBJECT_INFO='true'
export ZURP_FETCH_META_URL_INFO='true'
export ZURP_FETCH_FBDL_RUNS='true'

# Queue the PoC for each newly disclosed SPARTA finding in Burp's Organizer.
export ZURP_SEND_POCS_TO_ORGANIZER='true'

# Replace {{fb_dtsg}} placeholders, per tool.
export ZURP_CSRF_PLACEHOLDER_PROXY='false'
export ZURP_CSRF_PLACEHOLDER_REPEATER='true'
export ZURP_CSRF_PLACEHOLDER_INTRUDER='true'
export ZURP_CSRF_PLACEHOLDER_SCANNER='false'

# Auto-refresh tokens already present in the request, per tool.
export ZURP_CSRF_AUTO_REFRESH_PROXY='false'
export ZURP_CSRF_AUTO_REFRESH_REPEATER='false'
export ZURP_CSRF_AUTO_REFRESH_INTRUDER='false'
export ZURP_CSRF_AUTO_REFRESH_SCANNER='false'

exec /opt/BurpSuitePro/BurpSuitePro "$@"
```

### Every setting, as a `user.vmoptions` file

Create `user.vmoptions` in the Burp installation folder. That is [the file PortSwigger documents
for your own JVM options][burp-java-options], and it is the one to use: it is yours, separate from
the `vmoptions` file Burp ships and replaces, so an update leaves it alone.

| | |
|---|---|
| macOS | `/Applications/Burp Suite Professional.app/Contents/user.vmoptions` |
| Linux | `/opt/BurpSuitePro/user.vmoptions` |

One flag per line, no quotes, no spaces around the `=` — the launcher splits the file on
whitespace, so a value containing a space cannot be expressed here at all. None of Zurp's need one.

```
-Dzurp.access.token=EAAB…
-Dzurp.api.base.url=https://api.facebook.com/
-Dzurp.log.level=DEBUG

-Dzurp.fetch.sparta.findings=true
-Dzurp.fetch.meta.object.info=true
-Dzurp.fetch.meta.url.info=true
-Dzurp.fetch.fbdl.runs=true

-Dzurp.send.pocs.to.organizer=true

-Dzurp.csrf.placeholder.proxy=false
-Dzurp.csrf.placeholder.repeater=true
-Dzurp.csrf.placeholder.intruder=true
-Dzurp.csrf.placeholder.scanner=false

-Dzurp.csrf.auto.refresh.proxy=false
-Dzurp.csrf.auto.refresh.repeater=false
-Dzurp.csrf.auto.refresh.intruder=false
-Dzurp.csrf.auto.refresh.scanner=false
```

### Precedence

These are **defaults, not overrides.** Precedence is the Zurp tab, then the JVM property, then the
environment variable, then the built-in default. Whatever is in the tab always wins, and it is
per-user rather than per-project, so a token typed in once already survives opening a new project.
Clearing a field in the tab counts as unset, which is how you get back to the seeded value after
rotating one.

> A token in `user.vmoptions` reaches `/proc/<pid>/cmdline`, readable by any local process, and
> `System.getProperty`, readable by every other extension loaded into the same Burp. An environment
> variable is only in `/proc/<pid>/environ`, which is owner-readable — so prefer
> `ZURP_ACCESS_TOKEN` for the token and keep `user.vmoptions` for the rest. Either way it is a
> bearer token sitting on disk in plaintext.

## Troubleshooting: `SSLProtocolException` on handshake

Some hosts offer client certificates during the handshake, and a large CA list makes that message
exceed the JDK's 32 KB ceiling. Burp is a Java application, so this hits everything it does — Zurp
and the built-in browser alike:

```
javax.net.ssl.SSLProtocolException: The size of the handshake message (48638)
  exceeds the maximum allowed size (32768)
```

`curl` reaches the same URL fine, because OpenSSL has no such ceiling — so the endpoint looking
healthy from a shell says nothing about Burp. Raise the ceiling on Burp's JVM, in the same
`user.vmoptions` as above:

```bash
echo '-Djdk.tls.maxHandshakeMessageSize=131072' >> /opt/BurpSuitePro/user.vmoptions
```

[burp-java-options]: https://portswigger.net/burp/documentation/desktop/troubleshooting/setting-java-options
