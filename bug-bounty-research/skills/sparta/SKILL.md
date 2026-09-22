---
name: sparta
description: Read the SPARTA scanner findings disclosed to you as a Meta bug bounty researcher, find the ones covering an endpoint you are looking at, and build the proof-of-concept request a finding describes. Use when triaging what to research next, when you have a captured request and want to know whether a scanner already flagged it, or when someone names a bb_finding_id. Requires the sparta MCP server and a bug bounty researcher token.
---

# SPARTA findings

SPARTA is Meta's automated offensive-security scanner. Some of what it finds is disclosed
to researchers on a private bounty as **leads** — a title, a summary, and a proof of concept
with the identifiers taken out.

A lead is not a vulnerability. It is a machine's opinion that something looks wrong,
handed to you so you can go and find out. Confirming one is your work, and the report you
submit is your report.

Same data as the **SPARTA findings** panel in the Zurp Burp extension, over the same
`/bug_bounty/sparta_findings` API, so the two agree — and `bb_finding_id` is the same id
in both, so a finding carries between Burp and here.

## Which tool

| You want | Use |
|---|---|
| To know what is disclosed to you at all | `sparta_list_findings` |
| To know if a request you are looking at is already flagged | `sparta_scan_traffic` |
| The full summary of a finding you have the id for | `sparta_get_finding` |
| The request the finding's PoC describes | `sparta_build_poc` |

**Reach for `sparta_scan_traffic` when you have traffic.** Paste the request, the HAR
excerpt, the log — it pulls the `doc_id` and `fb_api_req_friendly_name` out itself and
reports every finding on them. Reading ids out by hand and looking them up one at a time
finds less and costs more.

## The PoC reproduces nothing as written

Every value in a disclosed PoC is a `{{TOKEN}}` placeholder, a boolean, or null. That is
enforced where the finding is published, not filtered on the way out — a concrete
identifier cannot be in there.

So `sparta_build_poc` gives you a **template**. Before it does anything you have to
substitute every placeholder, including `fb_dtsg`.

**Fill them from a test environment you built.** The `fbdl` server exists for exactly
this: create the users, pages and groups the PoC needs, and use those ids. Never
substitute an identifier belonging to a real account, a real group, or anyone else's
object — a lead is a hypothesis about a missing check, and testing it against a stranger's
data is the thing the bounty programme forbids.

`fb_dtsg` is a live CSRF token from your own session. Inside Burp, Zurp fills it at send
time; here you supply it.

## Reading a finding

- **`priority` is `high` or `medium`.** There is no `low`, and it is a collapsed view of
  an internal severity you do not see.
- **`target_type` says how to read `target_id`**: `published_doc_id` is a persisted GraphQL
  document, `endpoint_name` is an operation shortname. A finding is raised against one of
  the two, and the PoC may name the other.
- **The summary is all the prose there is.** Repository paths, task and diff numbers,
  internal hostnames, symbol names and long ids are stripped before publication, so a
  summary that reads vaguely is redacted, not truncated. Do not ask the API for more; there
  is no more.
- **`published_at` is not the list order.** The catalog comes back in an order seeded from
  your own account, on purpose, so researchers do not all work the same head of the same
  list. `sparta_list_findings` sorts it worst-first for you.

## An empty answer is an answer

Nothing disclosed to you is a normal state. Findings are published to specific private
bounties, and you see one only if you are enrolled in that bounty and it is still active.
An empty catalog can also mean the disclosure feature is switched off centrally. Neither
is an error and neither is worth retrying.

Likewise a target with no findings: most endpoints have never been scanned, and most scans
find nothing worth disclosing.

## What this is not

It is not a list of open vulnerabilities at Meta — it is the subset a scanner raised, that
survived triage, that was chosen for disclosure, on a bounty you are on.

It does not tell you whether a finding is still live, whether someone else is working it,
or whether it has already been reported. Those fields exist internally and are deliberately
not disclosed. If you confirm a lead, submit it as you would any finding.

## Errors worth handling differently

| `kind` | What it means | Do |
|---|---|---|
| `no-token` | Neither `BB_RESEARCH_TOKEN` nor `ZURP_ACCESS_TOKEN` is set | Tell the user to set one and restart the server. Do not retry. |
| `forbidden` | The account is not on the researcher allowlist | Stop. Every later call is latched off for the session. The same allowlist gates `meta-context`, so that will be refused too. |
| `unauthorized` | Token expired, or not a real-person token | Researcher tokens last 60 days. Re-mint at facebook.com/whitehat/fbdl/generate_api_token/. A page or system-user token is rejected outright. |
| `not-found` | No such finding, or it is not disclosed to you | The two are indistinguishable on purpose. Check the id against `sparta_list_findings`. |
| `rate-limited` | Hourly budget spent | Wait. The wait is the rest of the hour. |
| `local-budget` | The client refused before sending, to protect the same budget | The message says when the next call can run. |
| `network`, `server-error` | Transient | Already retried with backoff before you saw it. Report it; do not loop. |

## Budget

Metered per researcher, per hour, and shared with anything else on the same token —
including Zurp in Burp, which spends up to 500 an hour here on its own background sweep.

| Endpoint | Per researcher, per hour |
|---|---|
| the findings list | 1000 |
| one finding by id | 1000 |

Counted separately, so paging the list cannot exhaust your ability to read a finding.
Separate again from the `meta-context` and `fbdl` endpoints.

In practice you will not come near it. The server fetches the whole disclosed catalog once
and answers every later lookup from it, so a session of scanning traffic usually costs a
handful of requests no matter how many endpoints you look at. `sparta_get_finding` on an id
you have not seen is one request; on one already in the catalog, none.

The catalog is capped at the 2,000 most recent findings visible to you. If you have more
than that, the older tail is not served — nothing the client can do about it, but worth
knowing before concluding a finding was revoked.
